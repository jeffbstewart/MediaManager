package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.MediaItem
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Migrates ownership photos from UUID-based paths to semantic paths.
 * Moves files on disk and populates the disk_path column.
 */
class MigrateOwnershipPhotosUpdater : SchemaUpdater {
    override val name = "migrate_ownership_photos"
    override val version = 1

    private val log = LoggerFactory.getLogger(MigrateOwnershipPhotosUpdater::class.java)

    override fun run() {
        val photos = OwnershipPhoto.findAll().filter { it.disk_path == null }
        if (photos.isEmpty()) {
            log.info("No ownership photos need migration")
            return
        }

        log.info("Migrating {} ownership photos to semantic paths", photos.size)

        // Pre-load title lookups for efficiency
        val itemTitles = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val titles = Title.findAll().associateBy { it.id }
        val items = MediaItem.findAll().associateBy { it.id }

        var migrated = 0
        var failed = 0

        for (photo in photos) {
            try {
                val uuid = photo.id ?: continue
                val legacyPath = OwnershipPhotoStorage.legacyPath(uuid, photo.content_type)
                val oldFile = File("${OwnershipPhotoStorage.BASE_DIR}/$legacyPath")

                if (!oldFile.exists()) {
                    // File missing — just record the legacy path so getFile knows where to look
                    photo.disk_path = legacyPath
                    photo.save()
                    log.warn("Ownership photo file missing, recorded legacy path: {}", legacyPath)
                    continue
                }

                val upc = photo.upc
                if (upc != null && upc.length >= 10) {
                    val uniqueId = UpcUniqueId(upc)
                    val titleName = resolveTitleName(photo.media_item_id, itemTitles, titles, items)
                    val newPath = OwnershipPhotoStorage.computePath(uniqueId, titleName, photo.content_type)
                    val newFile = File("${OwnershipPhotoStorage.BASE_DIR}/$newPath")
                    newFile.parentFile.mkdirs()

                    if (oldFile.renameTo(newFile)) {
                        photo.disk_path = newPath
                        photo.save()
                        migrated++
                        log.debug("Migrated {} -> {}", legacyPath, newPath)
                    } else {
                        // renameTo failed — keep legacy path
                        photo.disk_path = legacyPath
                        photo.save()
                        failed++
                        log.warn("Failed to move {} -> {}, keeping legacy path", legacyPath, newPath)
                    }
                } else {
                    // No valid UPC — keep at legacy path
                    photo.disk_path = legacyPath
                    photo.save()
                    migrated++
                }
            } catch (e: Exception) {
                failed++
                log.error("Error migrating ownership photo {}: {}", photo.id, e.message)
            }
        }

        // Clean up empty old UUID-based directories
        cleanEmptyDirs()

        log.info("Ownership photo migration complete: {} migrated, {} failed", migrated, failed)
    }

    private fun resolveTitleName(
        mediaItemId: Long?,
        itemTitles: Map<Long, List<MediaItemTitle>>,
        titles: Map<Long?, Title>,
        items: Map<Long?, MediaItem>
    ): String? {
        if (mediaItemId == null) return null
        val link = itemTitles[mediaItemId]?.firstOrNull()
        if (link != null) {
            val title = titles[link.title_id]
            if (title?.name != null) return title.name
        }
        return items[mediaItemId]?.product_name
    }

    private fun cleanEmptyDirs() {
        val base = File(OwnershipPhotoStorage.BASE_DIR)
        if (!base.exists()) return
        base.listFiles()?.forEach { dir1 ->
            if (dir1.isDirectory) {
                dir1.listFiles()?.forEach { dir2 ->
                    if (dir2.isDirectory && (dir2.listFiles()?.isEmpty() == true)) {
                        dir2.delete()
                    }
                }
                if (dir1.listFiles()?.isEmpty() == true) {
                    dir1.delete()
                }
            }
        }
    }
}
