package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.LocalImage
import net.stewart.mediamanager.entity.LocalImageSourceType
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * Stores and retrieves locally-sourced images (user uploads and FFmpeg frame extracts).
 * Uses the same UUID-sharded directory layout as the poster/headshot caches:
 * data/local-images/{ab}/{cd}/{uuid}.{ext}
 */
object LocalImageService {
    private val log = LoggerFactory.getLogger(LocalImageService::class.java)
    private const val CACHE_DIR = "data/local-images"

    /** Stores image bytes, creates a LocalImage record, and returns the UUID. */
    fun store(bytes: ByteArray, sourceType: LocalImageSourceType, contentType: String): String {
        val uuid = UUID.randomUUID().toString()
        val ext = when {
            contentType.contains("png") -> "png"
            contentType.contains("webp") -> "webp"
            else -> "jpg"
        }

        val file = fileForId(uuid, ext)
        file.parentFile.mkdirs()
        file.writeBytes(bytes)

        LocalImage(
            id = uuid,
            source_type = sourceType.name,
            content_type = contentType,
            created_at = LocalDateTime.now()
        ).create()

        log.info("Local image stored: id={} type={} size={} bytes", uuid, sourceType, bytes.size)
        return uuid
    }

    /** Returns the image file for a given UUID, or null if not found. */
    fun getFile(uuid: String): File? {
        val record = LocalImage.findById(uuid) ?: return null
        val ext = when {
            record.content_type.contains("png") -> "png"
            record.content_type.contains("webp") -> "webp"
            else -> "jpg"
        }
        val file = fileForId(uuid, ext)
        return if (file.exists()) file else null
    }

    /** Returns the content type for a stored image. */
    fun getContentType(uuid: String): String? {
        return LocalImage.findById(uuid)?.content_type
    }

    /** Deletes a local image (file + DB record). */
    fun delete(uuid: String) {
        val record = LocalImage.findById(uuid)
        if (record != null) {
            val ext = when {
                record.content_type.contains("png") -> "png"
                record.content_type.contains("webp") -> "webp"
                else -> "jpg"
            }
            val file = fileForId(uuid, ext)
            if (file.exists()) file.delete()
            record.delete()
            log.info("Local image deleted: id={}", uuid)
        }
    }

    private fun fileForId(uuid: String, ext: String): File {
        val ab = uuid.substring(0, 2)
        val cd = uuid.substring(2, 4)
        return File("$CACHE_DIR/$ab/$cd/$uuid.$ext")
    }
}
