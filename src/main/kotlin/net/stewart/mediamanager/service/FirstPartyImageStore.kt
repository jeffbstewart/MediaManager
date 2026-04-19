package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Unified content-addressable store for first-party images — ownership
 * photos and local uploads / frame extracts. See
 * docs/IMAGE_CACHE_MIGRATION.md.
 *
 * Layout:
 *   data/first-party-images/{category}/{ab}/{cd}/{identifier}.{ext}
 *   data/first-party-images/{category}/{ab}/{cd}/{identifier}.meta.json
 *
 * `{ab}/{cd}` are the first four hex chars of `sha256(identifier)` so
 * fan-out stays 256×256 regardless of whether the identifier is a
 * UUID, a sequence-numbered filename, or something else. The filename
 * itself stays human-readable.
 *
 * **Critical invariant: this store never deletes or overwrites
 * first-party images** (ownership photos are physically expensive to
 * recreate; local uploads originated from a user action). Reads fall
 * back to the legacy path when the new path is empty, never the other
 * way around — old files are treated as read-only ground truth.
 * Phase 3 of the migration writes the copy, not a move.
 */
object FirstPartyImageStore {

    private val log = LoggerFactory.getLogger(FirstPartyImageStore::class.java)

    val root: Path = Path.of("data", "first-party-images")

    object Category {
        const val OWNERSHIP_PHOTOS = "ownership-photos"
        const val LOCAL_IMAGES = "local-images"
    }

    /**
     * Compute the canonical new-layout path for a given (category,
     * identifier, extension). Does not check whether the file exists.
     */
    fun pathFor(category: String, identifier: String, extension: String = "jpg"): Path {
        val hash = sha256Hex(identifier)
        val shard1 = hash.substring(0, 2)
        val shard2 = hash.substring(2, 4)
        return root.resolve(category).resolve(shard1).resolve(shard2)
            .resolve("$identifier.$extension")
    }

    /**
     * Read-side dual-lookup: try the new path first, fall back to the
     * legacy path. If both miss, returns null. Never copies on read.
     *
     * [legacyPath] is the caller-supplied path under the old layout
     * (`data/ownership-photos/...` or `data/local-images/...`). The
     * store itself doesn't hold that knowledge — every legacy scheme
     * is slightly different, so the calling service hands us a
     * resolved Path.
     */
    fun getImage(
        category: String,
        identifier: String,
        legacyPath: Path?,
        extension: String = "jpg"
    ): Path? {
        val newPath = pathFor(category, identifier, extension)
        if (Files.exists(newPath)) return newPath
        if (legacyPath != null && Files.exists(legacyPath)) return legacyPath
        return null
    }

    /**
     * Atomically writes image bytes to the new layout and drops a
     * sidecar alongside. Returns the destination path. Callers handle
     * any DB / entity update on their own after the bytes land.
     *
     * This function deliberately has no "move from old location"
     * semantics — phase 3's policy is never to touch the old directory.
     */
    fun commitOwnershipPhoto(
        photoId: String,
        bytes: ByteArray,
        contentType: String,
        storageKey: String?,
        mediaItemId: Long?,
        slugHint: String?,
        sequence: Int?,
        originalFilename: String? = null,
        capturedAt: java.time.LocalDateTime? = java.time.LocalDateTime.now(),
        extension: String = "jpg"
    ): Path {
        val destPath = pathFor(Category.OWNERSHIP_PHOTOS, photoId, extension)
        writeAtomic(destPath, bytes)
        MetadataWriter.writeSidecar(
            destPath,
            ImageMetadata.ownershipPhoto(
                photoId = photoId,
                storageKey = storageKey,
                mediaItemId = mediaItemId,
                slugHint = slugHint,
                sequence = sequence,
                capturedAt = capturedAt,
                contentType = contentType,
                originalFilename = originalFilename
            )
        )
        return destPath
    }

    fun commitLocalImage(
        uuid: String,
        bytes: ByteArray,
        contentType: String,
        subjectType: String?,
        subjectId: Long?,
        uploadedByUserId: Long?,
        uploadedAt: java.time.LocalDateTime? = java.time.LocalDateTime.now(),
        extension: String = "jpg"
    ): Path {
        val destPath = pathFor(Category.LOCAL_IMAGES, uuid, extension)
        writeAtomic(destPath, bytes)
        MetadataWriter.writeSidecar(
            destPath,
            ImageMetadata.localImage(
                uuid = uuid,
                subjectType = subjectType,
                subjectId = subjectId,
                uploadedByUserId = uploadedByUserId,
                uploadedAt = uploadedAt,
                contentType = contentType
            )
        )
        return destPath
    }

    /**
     * Delete the copy in the new layout plus its sidecar. The legacy
     * path is left alone — callers that need to scrub both must delete
     * the old one separately. We preserve old bytes by default: that's
     * the whole point of phase 3's dual-read policy.
     */
    fun deleteNewLayout(category: String, identifier: String, extension: String = "jpg") {
        val path = pathFor(category, identifier, extension)
        try {
            Files.deleteIfExists(path)
        } catch (e: Exception) {
            log.warn("FirstPartyImageStore delete failed for {}: {}", path, e.message)
        }
        MetadataWriter.deleteSidecar(path)
    }

    private fun writeAtomic(destPath: Path, bytes: ByteArray) {
        Files.createDirectories(destPath.parent)
        val tmp = Files.createTempFile(destPath.parent, ".write-", ".tmp")
        try {
            Files.write(tmp, bytes)
            Files.move(
                tmp, destPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val hi = (b.toInt() shr 4) and 0xF
            val lo = b.toInt() and 0xF
            sb.append("0123456789abcdef"[hi])
            sb.append("0123456789abcdef"[lo])
        }
        return sb.toString()
    }
}
