package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.OwnershipPhoto
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataFormatImpl

object OwnershipPhotoService {
    private val log = LoggerFactory.getLogger(OwnershipPhotoService::class.java)
    private const val CACHE_DIR = "data/ownership-photos"

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
        val ext = extensionFor(contentType)

        val file = fileForId(uuid, ext)
        file.parentFile.mkdirs()
        file.writeBytes(bytes)

        val orientation = readExifOrientation(bytes)

        OwnershipPhoto(
            id = uuid,
            media_item_id = mediaItemId,
            upc = upc,
            content_type = contentType,
            orientation = orientation,
            captured_at = LocalDateTime.now()
        ).create()

        log.info("Ownership photo stored: id={} mediaItemId={} upc={} size={} bytes orientation={}", uuid, mediaItemId, upc, bytes.size, orientation)
        return uuid
    }

    fun getFile(uuid: String): File? {
        val record = OwnershipPhoto.findById(uuid) ?: return null
        val file = fileForId(uuid, extensionFor(record.content_type))
        return if (file.exists()) file else null
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

    /** Link orphaned UPC-only photos to their media item. Called after UPC lookup creates a MediaItem. */
    fun resolveOrphans(upc: String, mediaItemId: Long): Int {
        val orphans = OwnershipPhoto.findAll()
            .filter { it.upc == upc && it.media_item_id == null }
        for (photo in orphans) {
            photo.media_item_id = mediaItemId
            photo.save()
        }
        if (orphans.isNotEmpty()) {
            log.info("Resolved {} orphan ownership photos for UPC {} → mediaItemId {}", orphans.size, upc, mediaItemId)
        }
        return orphans.size
    }

    fun delete(uuid: String) {
        val record = OwnershipPhoto.findById(uuid)
        if (record != null) {
            val file = fileForId(uuid, extensionFor(record.content_type))
            if (file.exists()) file.delete()
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

    private fun extensionFor(contentType: String): String = when {
        contentType.contains("png") -> "png"
        contentType.contains("webp") -> "webp"
        contentType.contains("heic") -> "heic"
        contentType.contains("heif") -> "heif"
        else -> "jpg"
    }

    private fun fileForId(uuid: String, ext: String): File {
        val ab = uuid.substring(0, 2)
        val cd = uuid.substring(2, 4)
        return File("$CACHE_DIR/$ab/$cd/$uuid.$ext")
    }

    /**
     * Read EXIF orientation tag from JPEG image bytes.
     * Returns EXIF orientation value (1-8), default 1 (normal).
     * Values: 1=normal, 3=180°, 6=90°CW (portrait right), 8=90°CCW (portrait left)
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
            // Walk the standard metadata tree looking for Orientation
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

            // Try native JPEG metadata tree for EXIF orientation
            val nativeFormats = metadata.nativeMetadataFormatName
            if (nativeFormats != null) {
                val nativeTree = metadata.getAsTree(nativeFormats)
                val nativeNodes = mutableListOf(nativeTree)
                while (nativeNodes.isNotEmpty()) {
                    val node = nativeNodes.removeFirst()
                    // JPEG native metadata: markerSequence > unknown (APP1/EXIF)
                    // This is hard to parse generically, so we fall back to raw EXIF parsing
                    val children = node.childNodes
                    for (i in 0 until children.length) {
                        nativeNodes.add(children.item(i))
                    }
                }
            }

            // Direct EXIF byte scanning as fallback for JPEG
            return readExifOrientationFromBytes(bytes)
        } catch (e: Exception) {
            log.debug("Could not read EXIF orientation: {}", e.message)
            return 1
        }
    }

    /**
     * Parse EXIF orientation directly from JPEG bytes.
     * Scans for the APP1/EXIF marker and reads the orientation tag (0x0112).
     */
    private fun readExifOrientationFromBytes(bytes: ByteArray): Int {
        // Find EXIF APP1 marker (FF E1)
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xE1.toByte()) {
                break
            }
            i++
        }
        if (i >= bytes.size - 1) return 1

        // Skip marker (2 bytes) + length (2 bytes)
        val exifStart = i + 4
        if (exifStart + 6 > bytes.size) return 1

        // Check "Exif\0\0" header
        val exifHeader = String(bytes, exifStart, 6, Charsets.US_ASCII)
        if (!exifHeader.startsWith("Exif")) return 1

        val tiffStart = exifStart + 6
        if (tiffStart + 8 > bytes.size) return 1

        // Determine byte order
        val bigEndian = bytes[tiffStart] == 0x4D.toByte() && bytes[tiffStart + 1] == 0x4D.toByte()

        fun readShort(offset: Int): Int {
            return if (bigEndian) {
                ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            } else {
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)
            }
        }

        // Read IFD0 offset
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
            if (tag == 0x0112) { // Orientation tag
                return readShort(entryOffset + 8)
            }
        }

        return 1
    }
}
