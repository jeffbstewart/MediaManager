package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.Transcode
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Backfills file_modified_at for existing transcode and discovered_file records
 * by reading the filesystem last-modified time. Skips unreachable files.
 */
class PopulateFileModifiedUpdater : SchemaUpdater {
    override val name = "populate_file_modified"
    override val version = 1

    private val log = LoggerFactory.getLogger(PopulateFileModifiedUpdater::class.java)

    override fun run() {
        var tcUpdated = 0
        var dfUpdated = 0
        var skipped = 0

        for (tc in Transcode.findAll()) {
            if (tc.file_modified_at != null) continue
            val path = tc.file_path ?: continue
            val file = File(path)
            if (!file.exists()) {
                skipped++
                continue
            }
            tc.file_modified_at = readModifiedAt(file)
            tc.save()
            tcUpdated++
        }

        for (df in DiscoveredFile.findAll()) {
            if (df.file_modified_at != null) continue
            val file = File(df.file_path)
            if (!file.exists()) {
                skipped++
                continue
            }
            df.file_modified_at = readModifiedAt(file)
            df.save()
            dfUpdated++
        }

        log.info("Backfilled file_modified_at: {} transcodes, {} discovered files, {} skipped (unreachable)",
            tcUpdated, dfUpdated, skipped)
    }

    private fun readModifiedAt(file: File): LocalDateTime? {
        return try {
            val instant = Files.getLastModifiedTime(file.toPath()).toInstant()
            LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        } catch (_: Exception) {
            null
        }
    }
}
