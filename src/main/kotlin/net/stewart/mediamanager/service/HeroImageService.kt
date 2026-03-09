package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.LocalImageSourceType
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

/** A candidate hero frame with its file and the timestamp it was extracted from. */
data class CandidateFrame(val file: File, val seekTimeSeconds: Long)

/**
 * Extracts candidate hero frames from a video file using FFmpeg.
 * Returns a list of temporary JPEG files that the user can choose from.
 */
object HeroImageService {
    private val log = LoggerFactory.getLogger(HeroImageService::class.java)

    /** Number of candidate frames to extract. */
    private const val FRAME_COUNT = 12

    /**
     * Extracts [FRAME_COUNT] evenly-spaced frames from [videoFile].
     * Returns a list of [CandidateFrame] with temp files and their seek timestamps.
     * Caller is responsible for deleting temp files after use.
     */
    fun extractCandidateFrames(videoFile: File, jitterSeed: Int = 0): List<CandidateFrame> {
        val ffmpegPath = TranscoderAgent.getFfmpegPath()
        if (!File(ffmpegPath).exists()) {
            log.warn("FFmpeg not found at {}", ffmpegPath)
            return emptyList()
        }

        val duration = probeDuration(ffmpegPath, videoFile)
        if (duration <= 0) {
            log.warn("Could not determine duration for {}", videoFile.name)
            return emptyList()
        }

        val tempDir = Files.createTempDirectory("hero-frames-").toFile()
        val outputPattern = File(tempDir, "frame_%02d.jpg").absolutePath

        // Extract frames at evenly-spaced intervals, skipping the first and last 5%
        // jitterSeed shifts the sample points by a fraction of the interval for variety
        val startOffset = (duration * 0.05).toLong()
        val endOffset = (duration * 0.95).toLong()
        val span = endOffset - startOffset
        val interval = if (FRAME_COUNT > 1) span.toDouble() / (FRAME_COUNT - 1) else span.toDouble()
        val jitter = if (jitterSeed > 0) (interval * 0.5 * ((jitterSeed % 7 + 1).toDouble() / 8.0)).toLong() else 0L

        val frames = mutableListOf<CandidateFrame>()
        for (i in 0 until FRAME_COUNT) {
            val seekTime = (startOffset + (i * interval).toLong() + jitter).coerceAtMost(endOffset)
            val outputFile = File(tempDir, "frame_%02d.jpg".format(i + 1))

            val command = listOf(
                ffmpegPath,
                "-ss", seekTime.toString(),
                "-i", videoFile.absolutePath,
                "-vframes", "1",
                "-vf", "scale=640:-1",
                "-q:v", "2",
                "-y",
                outputFile.absolutePath
            )

            try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.readBytes() // consume output
                val exitCode = process.waitFor()
                if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                    frames.add(CandidateFrame(outputFile, seekTime))
                }
            } catch (e: Exception) {
                log.warn("Frame extraction failed at {}s: {}", seekTime, e.message)
            }
        }

        log.info("Extracted {} candidate frames from {}", frames.size, videoFile.name)
        return frames
    }

    /**
     * Stores the selected frame as the poster for a personal video title.
     * Sets `poster_cache_id` to the local image UUID.
     */
    fun setHeroImage(title: Title, imageBytes: ByteArray) {
        // Delete old hero image if one exists
        val oldId = title.poster_cache_id
        if (oldId != null) {
            LocalImageService.delete(oldId)
        }

        val uuid = LocalImageService.store(imageBytes, LocalImageSourceType.FRAME_EXTRACT, "image/jpeg")
        title.poster_cache_id = uuid
        title.save()
        log.info("Hero image set for title {}: local_image={}", title.id, uuid)
    }

    /** Probes video duration in seconds using FFmpeg. */
    private fun probeDuration(ffmpegPath: String, videoFile: File): Long {
        try {
            val process = ProcessBuilder(
                ffmpegPath, "-i", videoFile.absolutePath
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val match = Regex("Duration: (\\d+):(\\d+):(\\d+)\\.(\\d+)").find(output)
            if (match != null) {
                val h = match.groupValues[1].toLong()
                val m = match.groupValues[2].toLong()
                val s = match.groupValues[3].toLong()
                return h * 3600 + m * 60 + s
            }
        } catch (e: Exception) {
            log.warn("Duration probe failed for {}: {}", videoFile.name, e.message)
        }
        return 0
    }

    /** Cleans up temporary frame files. */
    fun cleanupTempFrames(frames: List<CandidateFrame>) {
        if (frames.isEmpty()) return
        val tempDir = frames.first().file.parentFile
        for (f in frames) {
            try { f.file.delete() } catch (_: Exception) {}
        }
        try { tempDir?.delete() } catch (_: Exception) {}
    }
}
