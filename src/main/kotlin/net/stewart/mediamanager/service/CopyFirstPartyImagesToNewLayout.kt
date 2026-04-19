package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.LocalImage
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Phase-3 migration helper: **copies** (never moves) every ownership
 * photo and local image from its legacy location to the new
 * [FirstPartyImageStore] layout, writing a fresh sidecar alongside.
 *
 * Legacy files are left untouched on purpose. The dual-read policy in
 * [FirstPartyImageStore.getImage] uses the new path as primary and
 * falls back to the legacy path, so the only failure mode during a
 * partial run is "slower read on a few entries" — never data loss.
 *
 * Not registered in [net.stewart.mediamanager.Bootstrap] yet. A later
 * commit registers this updater to trigger the copy on next deploy.
 * Separating the code landing from the migration trigger lets us roll
 * back the trigger without reverting the code, and vice-versa.
 *
 * Idempotent: skips entries that already have bytes at the new path.
 * Bump [version] to force a re-sweep after a sidecar schema change
 * or a broken prior run.
 */
class CopyFirstPartyImagesToNewLayout : SchemaUpdater {
    override val name: String = "copy_first_party_images_to_new_layout"
    override val version: Int = 1

    private val log = LoggerFactory.getLogger(CopyFirstPartyImagesToNewLayout::class.java)

    override fun run() {
        val ownership = copyOwnershipPhotos()
        val local = copyLocalImages()
        log.info("Phase-3 first-party copy complete: {} ownership photos, {} local images",
            ownership, local)
    }

    // -------------------------------------------------------------------
    // Ownership photos
    // -------------------------------------------------------------------

    private fun copyOwnershipPhotos(): Int {
        val photos = OwnershipPhoto.findAll()
        if (photos.isEmpty()) return 0

        val itemTitles = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val titles = Title.findAll().associateBy { it.id }

        var copied = 0
        var skippedExisting = 0
        var missingSource = 0
        var failed = 0

        for (photo in photos) {
            val uuid = photo.id ?: continue
            val diskPath = photo.disk_path ?: OwnershipPhotoStorage.legacyPath(uuid, photo.content_type)
            val legacyFile = OwnershipPhotoStorage.resolveAbsolute(diskPath).toFile()
            if (!legacyFile.exists()) {
                missingSource++
                continue
            }

            val extension = extensionFor(photo.content_type)
            val destPath = FirstPartyImageStore.pathFor(
                FirstPartyImageStore.Category.OWNERSHIP_PHOTOS, uuid, extension
            )
            if (Files.exists(destPath)) {
                skippedExisting++
                continue
            }

            val titleName = resolveTitleName(photo.media_item_id, itemTitles, titles)
            val slug = titleName?.let { OwnershipPhotoStorage.slugify(it) }
            val seq = OwnershipPhotoStorage.extractSeq(diskPath).takeIf { it > 0 }

            try {
                Files.createDirectories(destPath.parent)
                val tmp = Files.createTempFile(destPath.parent, ".copy-", ".tmp")
                Files.copy(legacyFile.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING)
                Files.move(tmp, destPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)

                MetadataWriter.writeSidecar(
                    destPath,
                    ImageMetadata.ownershipPhoto(
                        photoId = uuid,
                        storageKey = photo.upc,
                        mediaItemId = photo.media_item_id,
                        slugHint = slug,
                        sequence = seq,
                        capturedAt = photo.captured_at,
                        contentType = photo.content_type
                    )
                )
                copied++
            } catch (e: Exception) {
                failed++
                log.warn("Ownership copy failed for {} ({} -> {}): {}",
                    uuid, legacyFile, destPath, e.message)
            }
        }

        log.info("Ownership copy: copied={} skipped-existing={} missing-source={} failed={}",
            copied, skippedExisting, missingSource, failed)
        return copied
    }

    private fun resolveTitleName(
        mediaItemId: Long?,
        itemTitles: Map<Long, List<MediaItemTitle>>,
        titles: Map<Long?, Title>
    ): String? {
        if (mediaItemId == null) return null
        val link = itemTitles[mediaItemId]?.firstOrNull() ?: return null
        return titles[link.title_id]?.name
    }

    // -------------------------------------------------------------------
    // Local images
    // -------------------------------------------------------------------

    private fun copyLocalImages(): Int {
        val records = LocalImage.findAll()
        if (records.isEmpty()) return 0

        var copied = 0
        var skippedExisting = 0
        var missingSource = 0
        var failed = 0

        for (record in records) {
            val uuid = record.id ?: continue
            val ext = extensionFor(record.content_type)
            val legacyFile = legacyLocalImagePath(uuid, ext).toFile()
            if (!legacyFile.exists()) {
                missingSource++
                continue
            }

            val destPath = FirstPartyImageStore.pathFor(
                FirstPartyImageStore.Category.LOCAL_IMAGES, uuid, ext
            )
            if (Files.exists(destPath)) {
                skippedExisting++
                continue
            }

            try {
                Files.createDirectories(destPath.parent)
                val tmp = Files.createTempFile(destPath.parent, ".copy-", ".tmp")
                Files.copy(legacyFile.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING)
                Files.move(tmp, destPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)

                MetadataWriter.writeSidecar(
                    destPath,
                    ImageMetadata.localImage(
                        uuid = uuid,
                        subjectType = record.source_type,
                        subjectId = null,
                        uploadedByUserId = null,
                        uploadedAt = record.created_at,
                        contentType = record.content_type
                    )
                )
                copied++
            } catch (e: Exception) {
                failed++
                log.warn("Local-image copy failed for {}: {}", uuid, e.message)
            }
        }

        log.info("Local-image copy: copied={} skipped-existing={} missing-source={} failed={}",
            copied, skippedExisting, missingSource, failed)
        return copied
    }

    private fun legacyLocalImagePath(uuid: String, ext: String): Path {
        val ab = uuid.substring(0, 2)
        val cd = uuid.substring(2, 4)
        return File("data/local-images/$ab/$cd/$uuid.$ext").toPath()
    }

    private fun extensionFor(contentType: String): String = when {
        contentType.contains("png") -> "png"
        contentType.contains("webp") -> "webp"
        contentType.contains("heic") -> "heic"
        contentType.contains("heif") -> "heif"
        else -> "jpg"
    }
}
