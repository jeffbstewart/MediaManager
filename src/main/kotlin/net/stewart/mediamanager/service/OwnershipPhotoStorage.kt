package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.OwnershipPhoto
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Generic file storage layer for ownership evidence photos.
 * Manages semantic directory structure and filenames based on [UniqueId] and optional title.
 * Application-specific concerns (media items, UPCs) belong in [OwnershipPhotoService].
 */
object OwnershipPhotoStorage {
    private val log = LoggerFactory.getLogger(OwnershipPhotoStorage::class.java)
    internal const val BASE_DIR = "data/ownership-photos"

    /**
     * Compute the disk path for a new photo.
     * @param uniqueId application-chosen identifier (provides storageKey + shard chars)
     * @param title optional human-readable name for slug generation
     * @param contentType MIME type (determines file extension)
     * @return relative path like "5/9/786936215595_3.jpg" or "m/a/786936215595_matrix_2.jpg"
     */
    fun computePath(uniqueId: UniqueId, title: String?, contentType: String): String {
        val slug = title?.let { slugify(it) }
        val shard1: Char
        val shard2: Char
        if (slug != null && slug.length >= 2) {
            shard1 = slug[0]
            shard2 = slug[1]
        } else if (slug != null && slug.length == 1) {
            shard1 = slug[0]
            shard2 = '_'
        } else {
            shard1 = uniqueId.shard1
            shard2 = uniqueId.shard2
        }

        val seq = nextSeq(uniqueId.storageKey)
        val ext = extensionFor(contentType)
        val filename = if (slug != null) {
            "${uniqueId.storageKey}_${slug}_$seq.$ext"
        } else {
            "${uniqueId.storageKey}_$seq.$ext"
        }
        return "$shard1/$shard2/$filename"
    }

    /** Write bytes to the computed disk path. Creates parent directories as needed. */
    fun writeFile(diskPath: String, bytes: ByteArray) {
        val file = File("$BASE_DIR/$diskPath")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    /** Get the File for a disk path, or null if it doesn't exist. */
    fun getFile(diskPath: String): File? {
        val file = File("$BASE_DIR/$diskPath")
        return if (file.exists()) file else null
    }

    /** Delete the file at a disk path, along with any sidecar metadata next to it. */
    fun deleteFile(diskPath: String) {
        val file = File("$BASE_DIR/$diskPath")
        if (file.exists()) file.delete()
        // Sidecar follows the image — never orphan one.
        MetadataWriter.deleteSidecar(file.toPath())
    }

    /**
     * Move all photos for a storageKey to new slug-based paths.
     * Called when a title becomes available after initial storage.
     * @return number of files moved
     */
    fun moveToSlug(uniqueId: UniqueId, title: String): Int {
        val slug = slugify(title) ?: return 0
        val photos = OwnershipPhoto.findAll()
            .filter { it.disk_path != null && extractStorageKey(it.disk_path!!) == uniqueId.storageKey }

        var moved = 0
        for (photo in photos) {
            val oldPath = photo.disk_path!!
            val oldFile = File("$BASE_DIR/$oldPath")
            if (!oldFile.exists()) continue

            // Already in a slug-based dir? Skip if slug matches.
            if (isSlugBased(oldPath, slug)) continue

            val seq = extractSeq(oldPath)
            val ext = oldPath.substringAfterLast('.')

            val shard1 = if (slug.length >= 1) slug[0] else uniqueId.shard1
            val shard2 = if (slug.length >= 2) slug[1] else '_'
            val newFilename = "${uniqueId.storageKey}_${slug}_$seq.$ext"
            val newPath = "$shard1/$shard2/$newFilename"

            val newFile = File("$BASE_DIR/$newPath")
            newFile.parentFile.mkdirs()
            if (oldFile.renameTo(newFile)) {
                // Carry the sidecar along with the image so metadata stays
                // paired. If the sidecar rename fails we just re-emit on the
                // next backfill pass — the photo itself is the critical bit.
                val oldSidecar = MetadataWriter.sidecarFor(oldFile.toPath()).toFile()
                if (oldSidecar.exists()) {
                    val newSidecar = MetadataWriter.sidecarFor(newFile.toPath()).toFile()
                    if (!oldSidecar.renameTo(newSidecar)) {
                        log.warn("Sidecar rename failed {} -> {}; backfill will recreate",
                            oldSidecar, newSidecar)
                    }
                }
                photo.disk_path = newPath
                photo.save()
                moved++
                log.info("Moved ownership photo {} -> {}", oldPath, newPath)
            } else {
                log.warn("Failed to move ownership photo {} -> {}", oldPath, newPath)
            }
        }

        // Clean up empty old directories
        if (moved > 0) cleanEmptyDirs()
        return moved
    }

    /**
     * Compute the legacy UUID-based file path (for backwards compatibility during migration).
     */
    fun legacyPath(uuid: String, contentType: String): String {
        val ab = uuid.substring(0, 2)
        val cd = uuid.substring(2, 4)
        val ext = extensionFor(contentType)
        return "$ab/$cd/$uuid.$ext"
    }

    /** Strip non-alphanumeric, lowercase, truncate to 15 chars. Returns null if result is empty. */
    fun slugify(title: String): String? {
        val slug = title.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (slug.isEmpty()) return null
        return slug.take(15)
    }

    /**
     * Resolve a relative diskPath to an absolute Path for sidecar / filesystem use.
     * Routes through [Filesystems.current] so Jimfs-backed tests see the
     * legacy tree under the in-memory root rather than the host's `data/`
     * directory. Production wiring is unchanged (default FS).
     */
    fun resolveAbsolute(diskPath: String): java.nio.file.Path =
        Filesystems.current.getPath(BASE_DIR, diskPath)

    private fun nextSeq(storageKey: String): Int {
        val existing = OwnershipPhoto.findAll()
            .filter { it.disk_path != null && extractStorageKey(it.disk_path!!) == storageKey }
        if (existing.isEmpty()) return 1

        val maxSeq = existing.mapNotNull { extractSeq(it.disk_path!!) }.maxOrNull() ?: 0
        return maxSeq + 1
    }

    /** Extract the storageKey from a disk path filename like "5/9/786936215595_matrix_2.jpg" */
    internal fun extractStorageKey(diskPath: String): String? {
        val filename = diskPath.substringAfterLast('/')
        val base = filename.substringBeforeLast('.')
        return base.split('_').firstOrNull()
    }

    /** Extract the seq number from a disk path filename */
    fun extractSeq(diskPath: String): Int {
        val filename = diskPath.substringAfterLast('/')
        val base = filename.substringBeforeLast('.')
        val parts = base.split('_')
        return parts.lastOrNull()?.toIntOrNull() ?: 1
    }

    /** Check if a path is already slug-based with the given slug */
    private fun isSlugBased(diskPath: String, slug: String): Boolean {
        val filename = diskPath.substringAfterLast('/')
        val base = filename.substringBeforeLast('.')
        val parts = base.split('_')
        // slug-based: storageKey_slug_seq — at least 3 parts with middle matching slug
        return parts.size >= 3 && parts[1] == slug
    }

    private fun extensionFor(contentType: String): String = when {
        contentType.contains("png") -> "png"
        contentType.contains("webp") -> "webp"
        contentType.contains("heic") -> "heic"
        contentType.contains("heif") -> "heif"
        else -> "jpg"
    }

    /** Remove empty shard directories left after moves. */
    private fun cleanEmptyDirs() {
        val base = File(BASE_DIR)
        if (!base.exists()) return
        base.listFiles()?.forEach { shard1Dir ->
            if (shard1Dir.isDirectory) {
                shard1Dir.listFiles()?.forEach { shard2Dir ->
                    if (shard2Dir.isDirectory && (shard2Dir.listFiles()?.isEmpty() == true)) {
                        shard2Dir.delete()
                    }
                }
                if (shard1Dir.listFiles()?.isEmpty() == true) {
                    shard1Dir.delete()
                }
            }
        }
    }
}
