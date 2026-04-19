package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataFormatImpl

/**
 * Media-specific wrapper around [OwnershipPhotoStorage].
 * Knows about MediaItem, UPC, and title resolution.
 */
object OwnershipPhotoService {
    private val log = LoggerFactory.getLogger(OwnershipPhotoService::class.java)

    /** Store a photo linked to an existing media item. */
    fun store(bytes: ByteArray, contentType: String, mediaItemId: Long): String {
        val item = MediaItem.findById(mediaItemId)
        return storeInternal(bytes, contentType, mediaItemId, item?.upc)
    }

    /** Store a photo for a UPC that may not have a media item yet (novel scan). */
    fun storeForUpc(bytes: ByteArray, contentType: String, upc: String, mediaItemId: Long? = null): String {
        return storeInternal(bytes, contentType, mediaItemId, upc)
    }

    private fun storeInternal(bytes: ByteArray, contentType: String, mediaItemId: Long?, upc: String?): String {
        val uuid = UUID.randomUUID().toString()
        val orientation = readExifOrientation(bytes)
        val title = resolveTitleName(mediaItemId)
        val capturedAt = LocalDateTime.now()

        // Primary write lands in the unified FirstPartyImageStore. The
        // legacy on-disk path is no longer populated for new uploads —
        // reads fall back to the legacy tree only for photos taken before
        // phase 3. See docs/IMAGE_CACHE_MIGRATION.md.
        val extension = extensionFor(contentType)
        val newPath = FirstPartyImageStore.commitOwnershipPhoto(
            photoId = uuid,
            bytes = bytes,
            contentType = contentType,
            storageKey = upc,
            mediaItemId = mediaItemId,
            slugHint = title?.let { OwnershipPhotoStorage.slugify(it) },
            sequence = null,
            capturedAt = capturedAt,
            extension = extension
        )

        // disk_path remains populated with the LEGACY-shape relative path
        // so the one-way copy updater in phase 3 has a breadcrumb it can
        // use to find old files. For new writes the breadcrumb points at a
        // location that doesn't exist yet — getFile() tries the store
        // first, then falls back to this path.
        val diskPath = if (upc != null && upc.length >= 10) {
            val uniqueId = UpcUniqueId(upc)
            OwnershipPhotoStorage.computePath(uniqueId, title, contentType)
        } else {
            OwnershipPhotoStorage.legacyPath(uuid, contentType)
        }

        OwnershipPhoto(
            id = uuid,
            media_item_id = mediaItemId,
            upc = upc,
            content_type = contentType,
            orientation = orientation,
            disk_path = diskPath,
            captured_at = capturedAt
        ).create()

        log.info("Ownership photo stored: id={} newPath={} diskPath={} mediaItemId={} upc={} size={} bytes",
            uuid, newPath, diskPath, mediaItemId, upc, bytes.size)
        return uuid
    }

    fun getFile(uuid: String): File? {
        val record = OwnershipPhoto.findById(uuid) ?: return null
        val extension = extensionFor(record.content_type)
        val legacyFilePath = record.disk_path?.let { OwnershipPhotoStorage.resolveAbsolute(it) }
            ?: OwnershipPhotoStorage.resolveAbsolute(
                OwnershipPhotoStorage.legacyPath(uuid, record.content_type)
            )
        val resolved = FirstPartyImageStore.getImage(
            category = FirstPartyImageStore.Category.OWNERSHIP_PHOTOS,
            identifier = uuid,
            legacyPath = legacyFilePath,
            extension = extension
        )
        return resolved?.toFile()
    }

    private fun extensionFor(contentType: String): String = when {
        contentType.contains("png") -> "png"
        contentType.contains("webp") -> "webp"
        contentType.contains("heic") -> "heic"
        contentType.contains("heif") -> "heif"
        else -> "jpg"
    }

    fun getContentType(uuid: String): String? {
        return OwnershipPhoto.findById(uuid)?.content_type
    }

    fun findByMediaItem(mediaItemId: Long): List<OwnershipPhoto> {
        return OwnershipPhoto.findAll()
            .filter { it.media_item_id == mediaItemId }
            .sortedBy { it.captured_at }
    }

    fun findByUpc(upc: String): List<OwnershipPhoto> {
        return OwnershipPhoto.findAll()
            .filter { it.upc == upc }
            .sortedBy { it.captured_at }
    }

    /** Find photos for a media item, including any linked by UPC only (not yet resolved). */
    fun findAllForItem(mediaItemId: Long, upc: String?): List<OwnershipPhoto> {
        val all = OwnershipPhoto.findAll()
        val byItem = all.filter { it.media_item_id == mediaItemId }
        val byUpc = if (upc != null) {
            all.filter { it.upc == upc && it.media_item_id == null }
        } else emptyList()
        return (byItem + byUpc).distinctBy { it.id }.sortedBy { it.captured_at }
    }

    /**
     * Link orphaned UPC-only photos to their media item.
     * Called after UPC lookup creates a MediaItem.
     * Also moves files to slug-based directories if a title is now available.
     */
    fun resolveOrphans(upc: String, mediaItemId: Long): Int {
        val orphans = OwnershipPhoto.findAll()
            .filter { it.upc == upc && it.media_item_id == null }
        for (photo in orphans) {
            photo.media_item_id = mediaItemId
            photo.save()
        }
        if (orphans.isNotEmpty()) {
            log.info("Resolved {} orphan ownership photos for UPC {} -> mediaItemId {}", orphans.size, upc, mediaItemId)
        }

        // Try to move files to slug-based dirs if we now have a title
        if (upc.length >= 10) {
            val title = resolveTitleName(mediaItemId)
            if (title != null) {
                val moved = OwnershipPhotoStorage.moveToSlug(UpcUniqueId(upc), title)
                if (moved > 0) {
                    log.info("Moved {} ownership photos to slug dirs for UPC {}", moved, upc)
                }
            }
        }

        return orphans.size
    }

    fun delete(uuid: String) {
        val record = OwnershipPhoto.findById(uuid) ?: return

        // Delete from the new store (if the copy updater already landed
        // a copy there or phase-3 writes put it there directly).
        FirstPartyImageStore.deleteNewLayout(
            category = FirstPartyImageStore.Category.OWNERSHIP_PHOTOS,
            identifier = uuid,
            extension = extensionFor(record.content_type)
        )

        // Delete from the legacy path too. User-initiated deletion should
        // actually remove the bytes — the dual-read policy is only a
        // safety net for phase 3's copy, not a reason to keep orphans
        // when the user has said "delete this".
        if (record.disk_path != null) {
            OwnershipPhotoStorage.deleteFile(record.disk_path!!)
        } else {
            val legacyPath = OwnershipPhotoStorage.legacyPath(uuid, record.content_type)
            OwnershipPhotoStorage.deleteFile(legacyPath)
        }

        record.delete()
        log.info("Ownership photo deleted: id={}", uuid)
    }

    fun countByMediaItem(): Map<Long, Int> {
        return OwnershipPhoto.findAll()
            .filter { it.media_item_id != null }
            .groupBy { it.media_item_id!! }
            .mapValues { (_, photos) -> photos.size }
    }

    fun totalCount(): Int {
        return OwnershipPhoto.findAll().size
    }

    fun itemsWithPhotos(): Int {
        return OwnershipPhoto.findAll().mapNotNull { it.media_item_id }.distinct().size
    }

    fun orphanCount(): Int {
        return OwnershipPhoto.findAll().count { it.media_item_id == null }
    }

    /**
     * Find the best available title name for a media item.
     * Prefers TMDB-enriched title name, falls back to product_name.
     */
    internal fun resolveTitleName(mediaItemId: Long?): String? {
        if (mediaItemId == null) return null
        val link = MediaItemTitle.findAll().firstOrNull { it.media_item_id == mediaItemId }
        if (link != null) {
            val title = Title.findById(link.title_id)
            if (title?.name != null) return title.name
        }
        return MediaItem.findById(mediaItemId)?.product_name
    }

    /**
     * Read EXIF orientation tag from JPEG image bytes.
     * Returns EXIF orientation value (1-8), default 1 (normal).
     */
    internal fun readExifOrientation(bytes: ByteArray): Int {
        try {
            val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return 1
            val reader = readers.next()
            reader.input = stream
            val metadata = reader.getImageMetadata(0) ?: return 1

            val standardTree = metadata.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName)
            val nodes = mutableListOf(standardTree)
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.nodeName == "Orientation") {
                    val value = node.attributes?.getNamedItem("value")?.nodeValue
                    if (value != null) return value.toIntOrNull() ?: 1
                }
                val children = node.childNodes
                for (i in 0 until children.length) {
                    nodes.add(children.item(i))
                }
            }

            val nativeFormats = metadata.nativeMetadataFormatName
            if (nativeFormats != null) {
                val nativeTree = metadata.getAsTree(nativeFormats)
                val nativeNodes = mutableListOf(nativeTree)
                while (nativeNodes.isNotEmpty()) {
                    val node = nativeNodes.removeFirst()
                    val children = node.childNodes
                    for (i in 0 until children.length) {
                        nativeNodes.add(children.item(i))
                    }
                }
            }

            return readExifOrientationFromBytes(bytes)
        } catch (e: Exception) {
            log.debug("Could not read EXIF orientation: {}", e.message)
            return 1
        }
    }

    private fun readExifOrientationFromBytes(bytes: ByteArray): Int {
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xE1.toByte()) {
                break
            }
            i++
        }
        if (i >= bytes.size - 1) return 1

        val exifStart = i + 4
        if (exifStart + 6 > bytes.size) return 1

        val exifHeader = String(bytes, exifStart, 6, Charsets.US_ASCII)
        if (!exifHeader.startsWith("Exif")) return 1

        val tiffStart = exifStart + 6
        if (tiffStart + 8 > bytes.size) return 1

        val bigEndian = bytes[tiffStart] == 0x4D.toByte() && bytes[tiffStart + 1] == 0x4D.toByte()

        fun readShort(offset: Int): Int {
            return if (bigEndian) {
                ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            } else {
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)
            }
        }

        val ifdOffset = tiffStart + if (bigEndian) {
            ((bytes[tiffStart + 4].toInt() and 0xFF) shl 24) or
            ((bytes[tiffStart + 5].toInt() and 0xFF) shl 16) or
            ((bytes[tiffStart + 6].toInt() and 0xFF) shl 8) or
            (bytes[tiffStart + 7].toInt() and 0xFF)
        } else {
            ((bytes[tiffStart + 7].toInt() and 0xFF) shl 24) or
            ((bytes[tiffStart + 6].toInt() and 0xFF) shl 16) or
            ((bytes[tiffStart + 5].toInt() and 0xFF) shl 8) or
            (bytes[tiffStart + 4].toInt() and 0xFF)
        }

        if (ifdOffset + 2 > bytes.size) return 1
        val entryCount = readShort(ifdOffset)

        for (e in 0 until entryCount) {
            val entryOffset = ifdOffset + 2 + (e * 12)
            if (entryOffset + 12 > bytes.size) return 1
            val tag = readShort(entryOffset)
            if (tag == 0x0112) {
                return readShort(entryOffset + 8)
            }
        }

        return 1
    }
}
