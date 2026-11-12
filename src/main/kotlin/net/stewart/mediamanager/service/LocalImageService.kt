package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.LocalImage
import net.stewart.mediamanager.entity.LocalImageSourceType
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * Stores and retrieves locally-sourced images (user uploads and FFmpeg
 * frame extracts). Phase-3 primary writes go to the unified
 * [FirstPartyImageStore]; reads fall back to the legacy UUID-sharded
 * directory under data/local-images/ when the new path is empty.
 */
object LocalImageService {
    private val log = LoggerFactory.getLogger(LocalImageService::class.java)
    private const val LEGACY_DIR = "data/local-images"

    /** Stores image bytes, creates a LocalImage record, and returns the UUID. */
    fun store(bytes: ByteArray, sourceType: LocalImageSourceType, contentType: String): String {
        val uuid = UUID.randomUUID().toString()
        val ext = extensionFor(contentType)
        val createdAt = LocalDateTime.now()

        FirstPartyImageStore.commitLocalImage(
            uuid = uuid,
            bytes = bytes,
            contentType = contentType,
            subjectType = sourceType.name,
            subjectId = null,
            uploadedByUserId = null,
            uploadedAt = createdAt,
            extension = ext
        )

        LocalImage(
            id = uuid,
            source_type = sourceType.name,
            content_type = contentType,
            created_at = createdAt
        ).create()

        log.info("Local image stored: id={} type={} size={} bytes", uuid, sourceType, bytes.size)
        return uuid
    }

    /** Returns the image file for a given UUID, or null if not found. */
    fun getFile(uuid: String): File? {
        val record = LocalImage.findById(uuid) ?: return null
        val ext = extensionFor(record.content_type)
        val legacyPath = legacyFileFor(uuid, ext).toPath()
        return FirstPartyImageStore.getImage(
            category = FirstPartyImageStore.Category.LOCAL_IMAGES,
            identifier = uuid,
            legacyPath = legacyPath,
            extension = ext
        )?.toFile()
    }

    /** Returns the content type for a stored image. */
    fun getContentType(uuid: String): String? {
        return LocalImage.findById(uuid)?.content_type
    }

    /** Deletes a local image (both new + legacy file + sidecar + DB row). */
    fun delete(uuid: String) {
        val record = LocalImage.findById(uuid) ?: return

        val ext = extensionFor(record.content_type)
        FirstPartyImageStore.deleteNewLayout(
            category = FirstPartyImageStore.Category.LOCAL_IMAGES,
            identifier = uuid,
            extension = ext
        )

        val legacy = legacyFileFor(uuid, ext)
        if (legacy.exists()) legacy.delete()
        MetadataWriter.deleteSidecar(legacy.toPath())

        record.delete()
        log.info("Local image deleted: id={}", uuid)
    }

    private fun legacyFileFor(uuid: String, ext: String): File {
        val ab = uuid.substring(0, 2)
        val cd = uuid.substring(2, 4)
        return File("$LEGACY_DIR/$ab/$cd/$uuid.$ext")
    }

    private fun extensionFor(contentType: String): String = when {
        contentType.contains("png") -> "png"
        contentType.contains("webp") -> "webp"
        else -> "jpg"
    }
}
