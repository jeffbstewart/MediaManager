package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.Transcode
import net.stewart.transcode.probeVideo
import net.stewart.transcode.toMediaFormat
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Background service that probes source files with FFprobe to determine their
 * media format (DVD, Blu-ray, UHD, etc.) based on video resolution.
 *
 * Files discovered by NAS scan start with format UNKNOWN. This service probes
 * them and fills in the actual format.
 */
object FormatProbeService {

    private val log = LoggerFactory.getLogger(FormatProbeService::class.java)

    /**
     * Probes all transcodes and discovered files with UNKNOWN format.
     * Returns the number of records updated.
     */
    fun probeUnknownFormats(): Int {
        val ffmpegPath = getFFmpegPath() ?: return 0
        var updated = 0

        // Probe transcodes
        val unknownTranscodes = Transcode.findAll()
            .filter { it.media_format == MediaFormat.UNKNOWN.name && it.file_path != null }

        for (tc in unknownTranscodes) {
            val format = probeFileFormat(ffmpegPath, tc.file_path!!)
            if (format != null && format != tc.media_format) {
                tc.media_format = format
                tc.save()
                updated++
                log.info("Probed format for transcode {}: {} -> {}", tc.id, MediaFormat.UNKNOWN.name, format)
            }
        }

        // Probe discovered files
        val unknownDiscovered = DiscoveredFile.findAll()
            .filter { it.media_format == MediaFormat.UNKNOWN.name }

        for (df in unknownDiscovered) {
            val format = probeFileFormat(ffmpegPath, df.file_path)
            if (format != null && format != df.media_format) {
                df.media_format = format
                df.save()
                updated++
                log.info("Probed format for discovered file {}: {} -> {}", df.id, MediaFormat.UNKNOWN.name, format)
            }
        }

        if (updated > 0) {
            log.info("Format probe complete: {} records updated", updated)
        }

        return updated
    }

    /**
     * Probes a single file and returns the media format string, or null on failure.
     */
    fun probeFileFormat(ffmpegPath: String, filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null

        val result = probeVideo(ffmpegPath, file)
        return result.toMediaFormat() ?: MediaFormat.OTHER.name
    }

    private fun getFFmpegPath(): String? {
        val configured = AppConfig.findAll()
            .firstOrNull { it.config_key == "ffmpeg_path" }?.config_val
        if (configured != null) return configured
        val defaultPath = File("/usr/bin/ffmpeg")
        return if (defaultPath.exists()) defaultPath.absolutePath else null
    }
}
