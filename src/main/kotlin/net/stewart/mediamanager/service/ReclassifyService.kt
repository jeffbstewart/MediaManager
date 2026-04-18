package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.Transcode
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Moves transcode files between NAS format directories (BLURAY, DVD, UHD)
 * and updates all related database records.
 *
 * TV Series files are not supported — their recursive show/season structure
 * requires a different model entirely.
 */
object ReclassifyService {

    private val log = LoggerFactory.getLogger(ReclassifyService::class.java)

    /**
     * Maps a [MediaFormat] to its NAS directory name.
     * Only flat movie directories are supported.
     */
    fun formatToDirectory(format: MediaFormat): String = when (format) {
        MediaFormat.BLURAY -> "BLURAY"
        MediaFormat.DVD -> "DVD"
        MediaFormat.UHD_BLURAY -> "UHD"
        MediaFormat.HD_DVD -> throw IllegalArgumentException("HD_DVD reclassification not supported")
        MediaFormat.UNKNOWN -> throw IllegalArgumentException("UNKNOWN format cannot be reclassified")
        MediaFormat.OTHER -> throw IllegalArgumentException("OTHER format cannot be reclassified")
        in MediaFormat.BOOK_FORMATS -> throw IllegalArgumentException("Book formats cannot be reclassified")
        else -> throw IllegalArgumentException("Unsupported format: $format")
    }

    /**
     * Reclassifies a transcode by moving its file to the target format's directory
     * and updating all related DB records.
     *
     * @param transcodeId the transcode record to move
     * @param targetFormat the destination format (BLURAY, DVD, UHD_BLURAY)
     * @param nasRoot the NAS root path; if null, read from app_config
     * @return a [ReclassifyResult] describing what was done
     * @throws IllegalArgumentException on validation failures
     * @throws IllegalStateException if the source file is missing or target exists
     */
    fun reclassify(transcodeId: Long, targetFormat: MediaFormat, nasRoot: String? = null): ReclassifyResult {
        val resolvedNasRoot = nasRoot ?: TranscoderAgent.getNasRoot()
            ?: throw IllegalStateException("NAS root path not configured")

        val transcode = Transcode.findById(transcodeId)
            ?: throw IllegalArgumentException("Transcode $transcodeId not found")

        val sourcePath = transcode.file_path
            ?: throw IllegalArgumentException("Transcode $transcodeId has no file_path")

        val currentFormat = transcode.media_format
            ?: throw IllegalArgumentException("Transcode $transcodeId has no media_format")

        if (currentFormat == targetFormat.name) {
            throw IllegalArgumentException("Transcode $transcodeId is already $currentFormat")
        }

        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            throw IllegalStateException("Source file does not exist: $sourcePath")
        }

        val targetDirName = formatToDirectory(targetFormat)
        val targetDir = File(resolvedNasRoot, targetDirName)
        if (!targetDir.isDirectory) {
            throw IllegalStateException("Target directory does not exist: ${targetDir.absolutePath}")
        }

        val targetFile = File(targetDir, sourceFile.name)
        if (!targetFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
            throw IllegalArgumentException("Target path escapes target directory")
        }
        if (targetFile.exists()) {
            throw IllegalStateException("Target file already exists: ${targetFile.absolutePath}")
        }

        // 1. Move the physical file
        log.info("Moving {} -> {}", sourceFile.absolutePath, targetFile.absolutePath)
        Files.move(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE)

        val oldPath = sourcePath
        val newPath = targetFile.absolutePath

        // 2. Update transcode record
        transcode.file_path = newPath
        transcode.media_format = targetFormat.name
        transcode.file_size_bytes = targetFile.length()
        transcode.file_modified_at = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(targetFile.lastModified()),
            ZoneId.systemDefault()
        )
        transcode.save()

        // 3. Delete old ForBrowser output
        val oldForBrowserFile = TranscoderAgent.getForBrowserPath(resolvedNasRoot, oldPath)
        var forBrowserDeleted = false
        if (oldForBrowserFile.exists()) {
            log.info("Deleting old ForBrowser file: {}", oldForBrowserFile.absolutePath)
            oldForBrowserFile.delete()
            forBrowserDeleted = true
        }

        // 4. Delete ForBrowser probe data
        ForBrowserProbeService.deleteForTranscode(transcodeId)

        // 5. Clear failed/expired leases for this transcode
        val leasesCleared = TranscodeLeaseService.clearFailures(transcodeId)

        val oldRelative = File(resolvedNasRoot).toPath().relativize(sourceFile.toPath()).toString()
            .replace('\\', '/')
        val newRelative = File(resolvedNasRoot).toPath().relativize(targetFile.toPath()).toString()
            .replace('\\', '/')

        log.info("Reclassified transcode {}: {} ({}) -> {} ({})",
            transcodeId, oldRelative, currentFormat, newRelative, targetFormat.name)

        return ReclassifyResult(
            transcodeId = transcodeId,
            oldPath = oldRelative,
            newPath = newRelative,
            oldFormat = currentFormat,
            newFormat = targetFormat.name,
            forBrowserDeleted = forBrowserDeleted,
            leasesCleared = leasesCleared
        )
    }
}

data class ReclassifyResult(
    val transcodeId: Long,
    val oldPath: String,
    val newPath: String,
    val oldFormat: String,
    val newFormat: String,
    val forBrowserDeleted: Boolean,
    val leasesCleared: Int
)
