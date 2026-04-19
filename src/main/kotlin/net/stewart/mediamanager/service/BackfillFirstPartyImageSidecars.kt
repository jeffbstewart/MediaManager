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

/**
 * Phase 1a backfill — writes the per-file `.meta.json` sidecar next to every
 * ownership photo and local image that doesn't already have one. See
 * docs/IMAGE_CACHE_MIGRATION.md.
 *
 * Everything written by this updater is reconstructable from the database.
 * If the DB is lost later, a future backfill can't recover what was here —
 * that's why we run this NOW, while the DB is authoritative.
 *
 * Idempotent: skips files that already have a sidecar. Bump [version] to
 * force a rewrite (e.g., after a sidecar schema change).
 */
class BackfillFirstPartyImageSidecars : SchemaUpdater {
    override val name: String = "backfill_first_party_image_sidecars"
    override val version: Int = 1

    private val log = LoggerFactory.getLogger(BackfillFirstPartyImageSidecars::class.java)

    override fun run() {
        val ownership = backfillOwnershipPhotos()
        val local = backfillLocalImages()
        log.info("Phase 1a backfill complete: {} ownership sidecars, {} local-image sidecars",
            ownership, local)
    }

    // -------------------------------------------------------------------
    // Ownership photos
    // -------------------------------------------------------------------

    private fun backfillOwnershipPhotos(): Int {
        val photos = OwnershipPhoto.findAll()
        if (photos.isEmpty()) return 0

        // Pre-join title lookups once rather than per-photo — saves ~N²
        // against larger collections.
        val itemTitles = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val titles = Title.findAll().associateBy { it.id }

        var written = 0
        var skipped = 0
        var missing = 0

        for (photo in photos) {
            val diskPath = photo.disk_path
            val photoId = photo.id
            if (diskPath == null || photoId == null) continue

            val imagePath = OwnershipPhotoStorage.resolveAbsolute(diskPath)
            if (!Files.exists(imagePath)) {
                missing++
                continue
            }
            if (Files.exists(MetadataWriter.sidecarFor(imagePath))) {
                skipped++
                continue
            }

            val titleName = resolveTitleName(photo.media_item_id, itemTitles, titles)
            val slug = titleName?.let { OwnershipPhotoStorage.slugify(it) }
            val seq = OwnershipPhotoStorage.extractSeq(diskPath).takeIf { it > 0 }

            val meta = ImageMetadata.ownershipPhoto(
                photoId = photoId,
                storageKey = photo.upc,
                mediaItemId = photo.media_item_id,
                slugHint = slug,
                sequence = seq,
                capturedAt = photo.captured_at,
                contentType = photo.content_type
            )
            if (MetadataWriter.writeSidecar(imagePath, meta)) written++
        }

        log.info("Ownership-photo sidecars: {} written, {} skipped (existing), {} missing on disk",
            written, skipped, missing)
        return written
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

    private fun backfillLocalImages(): Int {
        val records = LocalImage.findAll()
        if (records.isEmpty()) return 0

        var written = 0
        var skipped = 0
        var missing = 0

        for (record in records) {
            val uuid = record.id ?: continue
            val ext = extensionFor(record.content_type)
            val imageFile = fileForLocalImage(uuid, ext)
            if (!imageFile.exists()) {
                missing++
                continue
            }
            val imagePath = imageFile.toPath()
            if (Files.exists(MetadataWriter.sidecarFor(imagePath))) {
                skipped++
                continue
            }

            // subject_type / subject_id aren't stored on LocalImage — we
            // could chase them via every caller's back-reference, but the
            // authoritative answer is "this local image has source_type X".
            // That preserves enough provenance (FRAME_EXTRACT / UPLOAD /
            // etc.) to be useful without assuming a join that may not exist.
            val meta = ImageMetadata.localImage(
                uuid = uuid,
                subjectType = record.source_type,
                subjectId = null,
                uploadedByUserId = null,
                uploadedAt = record.created_at,
                contentType = record.content_type
            )
            if (MetadataWriter.writeSidecar(imagePath, meta)) written++
        }

        log.info("Local-image sidecars: {} written, {} skipped (existing), {} missing on disk",
            written, skipped, missing)
        return written
    }

    // Duplicates the layout that LocalImageService uses. Kept private here
    // because lifting this into the service would leak File-level concerns
    // back into a store API we're about to replace in phase 3.
    private fun fileForLocalImage(uuid: String, ext: String): File {
        val ab = uuid.substring(0, 2)
        val cd = uuid.substring(2, 4)
        return File("data/local-images/$ab/$cd/$uuid.$ext")
    }

    private fun extensionFor(contentType: String): String = when {
        contentType.contains("png") -> "png"
        contentType.contains("webp") -> "webp"
        else -> "jpg"
    }
}
