package net.stewart.transcode

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Generates thumbnail sprite sheets and a WebVTT index file for a ForBrowser MP4.
 *
 * Output files are placed alongside the MP4:
 *   Movie.mp4 -> Movie.thumbs_1.jpg, Movie.thumbs_2.jpg, ..., Movie.thumbs.vtt
 *
 * Each sprite sheet is a 10x10 grid of 160x90 thumbnails (100 per sheet, covering
 * 1000 seconds of video at one thumbnail every 10 seconds).
 */
object ThumbnailSpriteGenerator {

    private val log = LoggerFactory.getLogger(ThumbnailSpriteGenerator::class.java)

    private const val THUMB_WIDTH = 160
    private const val THUMB_HEIGHT = 90
    private const val INTERVAL_SECS = 10
    private const val COLS = 10
    private const val ROWS = 10
    private const val THUMBS_PER_SHEET = COLS * ROWS // 100

    /**
     * Generates thumbnail sprites for [mp4File]. Returns true on success.
     * Output files are written to [outputDir] (defaults to mp4File's directory).
     * Skips if the VTT file already exists and is newer than the MP4.
     */
    fun generate(ffmpegPath: String, mp4File: File, outputDir: File = mp4File.parentFile): Boolean {
        val baseName = mp4File.nameWithoutExtension
        val parentDir = outputDir
        outputDir.mkdirs()
        val vttFile = File(parentDir, "$baseName.thumbs.vtt")

        if (vttFile.exists() && vttFile.lastModified() >= mp4File.lastModified()) {
            log.info("Thumbnails already exist for {}, skipping", mp4File.name)
            return true
        }

        // Probe duration
        val durationSecs = probeDurationSecs(ffmpegPath, mp4File)
        if (durationSecs == null || durationSecs <= 0) {
            log.warn("Could not determine duration for {}, skipping thumbnails", mp4File.name)
            return false
        }

        val totalThumbs = (durationSecs / INTERVAL_SECS).toInt() + 1
        val totalSheets = (totalThumbs + THUMBS_PER_SHEET - 1) / THUMBS_PER_SHEET

        log.info("Generating {} thumbnail(s) across {} sheet(s) for {} ({}s)",
            totalThumbs, totalSheets, mp4File.name, "%.0f".format(durationSecs))

        // FFmpeg: extract frames at interval and tile into sprite sheets
        val spritePattern = File(parentDir, "$baseName.thumbs_%d.jpg").absolutePath
        val command = listOf(
            ffmpegPath,
            "-i", mp4File.absolutePath,
            "-vf", "fps=1/$INTERVAL_SECS,scale=$THUMB_WIDTH:$THUMB_HEIGHT,tile=${COLS}x$ROWS",
            "-q:v", "5",
            "-y",
            spritePattern
        )

        log.info("Running: {}", command.joinToString(" "))
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = sanitizeFfmpegOutput(process.inputStream.bufferedReader().readText())
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            log.error("FFmpeg thumbnail extraction failed (exit {}): {}", exitCode, output.takeLast(1000))
            return false
        }

        // Verify at least one sprite sheet was created
        val firstSheet = File(parentDir, "$baseName.thumbs_1.jpg")
        if (!firstSheet.exists()) {
            log.error("No sprite sheet generated for {}", mp4File.name)
            return false
        }

        // Generate WebVTT file
        generateVtt(vttFile, baseName, totalThumbs)

        log.info("Thumbnails complete for {}: {} sheets, VTT at {}", mp4File.name, totalSheets, vttFile.name)
        return true
    }

    /**
     * Returns true if thumbnail sprites exist for the given ForBrowser MP4.
     */
    fun hasSprites(mp4File: File): Boolean {
        val vttFile = File(mp4File.parentFile, "${mp4File.nameWithoutExtension}.thumbs.vtt")
        return vttFile.exists()
    }

    private fun generateVtt(vttFile: File, baseName: String, totalThumbs: Int) {
        vttFile.bufferedWriter().use { writer ->
            writer.write("WEBVTT")
            writer.newLine()

            for (i in 0 until totalThumbs) {
                val sheetIndex = (i / THUMBS_PER_SHEET) + 1 // 1-based
                val posInSheet = i % THUMBS_PER_SHEET
                val col = posInSheet % COLS
                val row = posInSheet / COLS
                val x = col * THUMB_WIDTH
                val y = row * THUMB_HEIGHT

                val startSecs = i * INTERVAL_SECS
                val endSecs = (i + 1) * INTERVAL_SECS

                writer.newLine()
                writer.write("${formatTime(startSecs)} --> ${formatTime(endSecs)}")
                writer.newLine()
                writer.write("$baseName.thumbs_$sheetIndex.jpg#xywh=$x,$y,$THUMB_WIDTH,$THUMB_HEIGHT")
                writer.newLine()
            }
        }
    }

    private fun formatTime(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d.000".format(h, m, s)
    }

    /**
     * Copies all sprite files (VTT + JPG sheets) from [spriteDir] to [targetDir].
     * The [baseName] is the filename stem (e.g., "Avatar" from "Avatar.mp4").
     * Skips files that already exist in [targetDir]. Returns the number of files copied.
     */
    fun copySpritesToDirectory(baseName: String, spriteDir: File, targetDir: File): Int {
        val vttFile = File(spriteDir, "$baseName.thumbs.vtt")
        if (!vttFile.exists()) return 0

        targetDir.mkdirs()
        var copied = 0

        // Copy VTT
        val targetVtt = File(targetDir, vttFile.name)
        if (!targetVtt.exists() || targetVtt.lastModified() < vttFile.lastModified()) {
            vttFile.copyTo(targetVtt, overwrite = true)
            copied++
        }

        // Copy all numbered sprite sheet JPGs
        spriteDir.listFiles()
            ?.filter { it.name.startsWith("$baseName.thumbs_") && it.extension.lowercase() == "jpg" }
            ?.forEach { jpg ->
                val targetJpg = File(targetDir, jpg.name)
                if (!targetJpg.exists() || targetJpg.lastModified() < jpg.lastModified()) {
                    jpg.copyTo(targetJpg, overwrite = true)
                    copied++
                }
            }

        return copied
    }

    private fun probeDurationSecs(ffmpegPath: String, file: File): Double? {
        return try {
            val process = ProcessBuilder(ffmpegPath, "-i", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = sanitizeFfmpegOutput(process.inputStream.bufferedReader().readText())
            process.waitFor()

            val match = Regex("""Duration: (\d+):(\d+):(\d+)\.(\d+)""").find(output)
            if (match != null) {
                val h = match.groupValues[1].toInt()
                val m = match.groupValues[2].toInt()
                val s = match.groupValues[3].toInt()
                val frac = "0.${match.groupValues[4]}".toDouble()
                h * 3600.0 + m * 60.0 + s + frac
            } else null
        } catch (e: Exception) {
            log.warn("Failed to probe duration for {}: {}", file.name, e.message)
            null
        }
    }
}
