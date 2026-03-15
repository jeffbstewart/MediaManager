package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
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

        // Compute semantic disk path via storage layer
        val diskPath = if (upc != null && upc.length >= 10) {
            val uniqueId = UpcUniqueId(upc)
            val title = resolveTitleName(mediaItemId)
            OwnershipPhotoStorage.computePath(uniqueId, title, contentType)
        } else {
            // Fallback to legacy UUID-based path for items without a valid UPC
            OwnershipPhotoStorage.legacyPath(uuid, contentType)
        }

        OwnershipPhotoStorage.writeFile(diskPath, bytes)

        OwnershipPhoto(
            id = uuid,
            media_item_id = mediaItemId,
            upc = upc,
            content_type = contentType,
            orientation = orientation,
            disk_path = diskPath,
            captured_at = LocalDateTime.now()
        ).create()

        log.info("Ownership photo stored: id={} diskPath={} mediaItemId={} upc={} size={} bytes", uuid, diskPath, mediaItemId, upc, bytes.size)
        return uuid
    }

    fun getFile(uuid: String): File? {
        val record = OwnershipPhoto.findById(uuid) ?: return null
        if (record.disk_path != null) {
            return OwnershipPhotoStorage.getFile(record.disk_path!!)
        }
        // Legacy fallback for records without disk_path (pre-migration)
        val legacyPath = OwnershipPhotoStorage.legacyPath(uuid, record.content_type)
        return OwnershipPhotoStorage.getFile(legacyPath)
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
        val record = OwnershipPhoto.findById(uuid)
        if (record != null) {
            if (record.disk_path != null) {
                OwnershipPhotoStorage.deleteFile(record.disk_path!!)
            } else {
                // Legacy fallback
                val legacyPath = OwnershipPhotoStorage.legacyPath(uuid, record.content_type)
                OwnershipPhotoStorage.deleteFile(legacyPath)
            }
            record.delete()
            log.info("Ownership photo deleted: id={}", uuid)
        }
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
