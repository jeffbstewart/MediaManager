package net.stewart.transcode

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Strips ASCII control characters (BEL, backspace, escape, etc.) from ffmpeg output
 * to prevent console beeps when logged on Windows. Preserves \n, \r, \t.
 */
fun sanitizeFfmpegOutput(text: String): String =
    text.replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"), "")

/** Browser-compatible video codecs that can be served with -c:v copy. */
val BROWSER_SAFE_CODECS = setOf("h264", "avc1", "avc")

/** Standard frame rates that Roku and most hardware decoders accept. */
val STANDARD_FPS = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0)

/**
 * Results from probing a video file — extracted in a single FFmpeg invocation.
 */
data class VideoProbeResult(
    val codec: String?,
    val durationSecs: Double?,
    val sarNum: Int?,
    val sarDen: Int?,
    val fps: Double?,
    val width: Int?,
    val height: Int?,
    val interlaced: Boolean = false
) {
    val browserSafe get() = codec != null && codec in BROWSER_SAFE_CODECS
    val isAnamorphic get() = sarNum != null && sarDen != null && sarNum != sarDen
    val isNonStandardFps get(): Boolean {
        if (fps == null) return false
        return STANDARD_FPS.none { kotlin.math.abs(it - fps) < 0.1 }
    }
    val needsVideoFilter get() = isAnamorphic || isNonStandardFps || interlaced
}

private val log = LoggerFactory.getLogger("net.stewart.transcode.VideoProbe")

/**
 * Probes a media file in a single FFmpeg invocation, extracting codec, duration,
 * SAR (sample aspect ratio), frame rate, and resolution.
 */
fun probeVideo(ffmpegPath: String, sourceFile: File): VideoProbeResult {
    return try {
        val process = ProcessBuilder(ffmpegPath, "-i", sourceFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = sanitizeFfmpegOutput(process.inputStream.bufferedReader().readText())
        process.waitFor()

        // Codec: "Stream #0:0: Video: h264 (High), ..."
        val codecMatch = Regex("""Stream #\d+:\d+.*?: Video: (\w+)""").find(output)
        val codec = codecMatch?.groupValues?.get(1)?.lowercase()

        // Duration: "Duration: 00:44:02.04"
        val durMatch = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""").find(output)
        val durationSecs = if (durMatch != null) {
            val h = durMatch.groupValues[1].toInt()
            val m = durMatch.groupValues[2].toInt()
            val s = durMatch.groupValues[3].toInt()
            val frac = "0.${durMatch.groupValues[4]}".toDouble()
            (h * 3600 + m * 60 + s + frac)
        } else null

        // Resolution + SAR: "720x480 [SAR 8:9 DAR 4:3]" or "1920x1080"
        val videoLineMatch = Regex("""Video: \w+.*?, (\d+)x(\d+)(?:\s*\[SAR (\d+):(\d+))?""").find(output)
        val width = videoLineMatch?.groupValues?.get(1)?.toIntOrNull()
        val height = videoLineMatch?.groupValues?.get(2)?.toIntOrNull()
        val sarNum = videoLineMatch?.groupValues?.get(3)?.toIntOrNull()
        val sarDen = videoLineMatch?.groupValues?.get(4)?.toIntOrNull()

        // Frame rate: "29.97 fps" or "24.20 fps" (actual display rate)
        val fpsMatch = Regex(""", (\d+(?:\.\d+)?)\s+fps""").find(output)
        val fps = fpsMatch?.groupValues?.get(1)?.toDoubleOrNull()

        // Interlacing: "top first" or "bottom first" in the video stream line
        val videoLine = Regex("""Stream #\d+:\d+.*?: Video:.*""").find(output)?.value ?: ""
        val interlaced = "top first" in videoLine || "bottom first" in videoLine

        VideoProbeResult(codec, durationSecs, sarNum, sarDen, fps, width, height, interlaced)
    } catch (e: Exception) {
        log.warn("Failed to probe video: {}", e.message)
        VideoProbeResult(null, null, null, null, null, null, null)
    }
}

/** Convenience: probe and return just the video codec. */
fun probeVideoCodec(ffmpegPath: String, sourceFile: File): String? =
    probeVideo(ffmpegPath, sourceFile).codec

/** Convenience: probe and return just the duration in seconds. */
fun probeDuration(ffmpegPath: String, sourceFile: File): Double? =
    probeVideo(ffmpegPath, sourceFile).durationSecs

fun isBrowserSafeCodec(codec: String?): Boolean =
    codec != null && codec in BROWSER_SAFE_CODECS

// --- Chapter extraction via FFprobe ---

/**
 * A chapter marker extracted from a media file.
 */
data class ChapterInfo(
    val number: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val title: String?
)

/**
 * Derives the FFprobe path from the FFmpeg path.
 * Replaces "ffmpeg" with "ffprobe" in the filename.
 */
fun ffprobePath(ffmpegPath: String): String {
    val file = File(ffmpegPath)
    val name = file.name
    val probeName = when {
        name.equals("ffmpeg.exe", ignoreCase = true) -> name.replace("ffmpeg", "ffprobe")
        name == "ffmpeg" -> "ffprobe"
        else -> "ffprobe"
    }
    return if (file.parent != null) "${file.parent}${File.separator}$probeName" else probeName
}

/**
 * Probes a media file for chapter markers using FFprobe JSON output.
 * Returns a list of chapters, or empty list if none found or on error.
 */
fun probeChapters(ffmpegPath: String, sourceFile: File): List<ChapterInfo> {
    return try {
        val probe = ffprobePath(ffmpegPath)
        val process = ProcessBuilder(
            probe, "-show_chapters", "-print_format", "json", "-v", "quiet",
            sourceFile.absolutePath
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        if (process.exitValue() != 0) {
            log.warn("FFprobe chapters failed for {} (exit {})", sourceFile.name, process.exitValue())
            return emptyList()
        }

        parseChaptersJson(output)
    } catch (e: Exception) {
        log.warn("Failed to probe chapters for {}: {}", sourceFile.name, e.message)
        emptyList()
    }
}

/**
 * Parses FFprobe JSON chapter output into ChapterInfo list.
 */
internal fun parseChaptersJson(json: String): List<ChapterInfo> {
    // Minimal JSON parsing — avoid adding a dependency
    // FFprobe outputs: {"chapters": [{...}, ...]}
    val chapters = mutableListOf<ChapterInfo>()

    // Find the "chapters" array
    val chaptersIdx = json.indexOf("\"chapters\"")
    if (chaptersIdx < 0) return emptyList()

    val arrayStart = json.indexOf('[', chaptersIdx)
    if (arrayStart < 0) return emptyList()

    // Find matching closing bracket
    var depth = 0
    var arrayEnd = -1
    for (i in arrayStart until json.length) {
        when (json[i]) {
            '[' -> depth++
            ']' -> { depth--; if (depth == 0) { arrayEnd = i; break } }
        }
    }
    if (arrayEnd < 0) return emptyList()

    val arrayContent = json.substring(arrayStart + 1, arrayEnd)
    if (arrayContent.isBlank()) return emptyList()

    // Split into individual chapter objects by finding top-level { } pairs
    var objDepth = 0
    var objStart = -1
    val objects = mutableListOf<String>()
    for (i in arrayContent.indices) {
        when (arrayContent[i]) {
            '{' -> { if (objDepth == 0) objStart = i; objDepth++ }
            '}' -> {
                objDepth--
                if (objDepth == 0 && objStart >= 0) {
                    objects.add(arrayContent.substring(objStart, i + 1))
                    objStart = -1
                }
            }
        }
    }

    for ((idx, obj) in objects.withIndex()) {
        val startTime = extractJsonDouble(obj, "start_time")
        val endTime = extractJsonDouble(obj, "end_time")
        val id = extractJsonInt(obj, "id") ?: idx
        val title = extractJsonString(obj, "title")

        if (startTime != null && endTime != null) {
            chapters.add(ChapterInfo(
                number = id + 1,
                startSeconds = startTime,
                endSeconds = endTime,
                title = title
            ))
        }
    }

    return chapters
}

private fun extractJsonDouble(json: String, key: String): Double? {
    val pattern = Regex(""""$key"\s*:\s*"([^"]+)"""")
    return pattern.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
}

private fun extractJsonInt(json: String, key: String): Int? {
    val pattern = Regex(""""$key"\s*:\s*(\d+)""")
    return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
}

private fun extractJsonString(json: String, key: String): String? {
    // Look inside "tags" object for title
    val tagsIdx = json.indexOf("\"tags\"")
    val searchIn = if (key == "title" && tagsIdx >= 0) {
        val tagsStart = json.indexOf('{', tagsIdx)
        val tagsEnd = json.indexOf('}', tagsStart + 1)
        if (tagsStart >= 0 && tagsEnd >= 0) json.substring(tagsStart, tagsEnd + 1) else json
    } else json
    val pattern = Regex(""""$key"\s*:\s*"([^"]*?)"""")
    val value = pattern.find(searchIn)?.groupValues?.get(1)
    return if (value.isNullOrBlank()) null else value
}

/**
 * Infers media format from probe resolution.
 * Returns "UHD_BLURAY", "BLURAY", "DVD", or "OTHER" if resolution is present but
 * doesn't match known tiers. Returns null if resolution couldn't be determined.
 */
fun VideoProbeResult.toMediaFormat(): String? = when {
    width == null || height == null -> null
    width >= 3840 || height >= 2160 -> "UHD_BLURAY"
    width >= 1920 || height >= 1080 -> "BLURAY"
    width >= 640 || height >= 480 -> "DVD"
    else -> "OTHER"
}

// --- ForBrowser probe: captures ALL streams for playback diagnostics ---

/**
 * Information about a single stream within a media file.
 */
data class StreamInfo(
    val index: Int,
    val type: String,   // "video", "audio", "subtitle", "data", "attachment"
    val codec: String?,
    val width: Int? = null,
    val height: Int? = null,
    val sarNum: Int? = null,
    val sarDen: Int? = null,
    val fps: Double? = null,
    val channels: Int? = null,
    val channelLayout: String? = null,
    val sampleRate: Int? = null,
    val bitrateKbps: Int? = null,
    val rawLine: String
)

/**
 * Full probe result for a ForBrowser MP4, capturing every stream.
 */
data class ForBrowserProbeResult(
    val durationSecs: Double?,
    val streams: List<StreamInfo>,
    val rawOutput: String
) {
    val streamCount get() = streams.size
}

/**
 * Probes a ForBrowser output file, capturing ALL streams (video, audio, subtitle, data, etc.)
 * for playback diagnostics. Unlike [probeVideo] which extracts only the first video stream
 * for transcoding decisions, this captures everything to diagnose playback problems.
 */
fun probeForBrowser(ffmpegPath: String, file: File): ForBrowserProbeResult {
    return try {
        val process = ProcessBuilder(ffmpegPath, "-i", file.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = sanitizeFfmpegOutput(process.inputStream.bufferedReader().readText())
        process.waitFor()

        // Duration
        val durMatch = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""").find(output)
        val durationSecs = if (durMatch != null) {
            val h = durMatch.groupValues[1].toInt()
            val m = durMatch.groupValues[2].toInt()
            val s = durMatch.groupValues[3].toInt()
            val frac = "0.${durMatch.groupValues[4]}".toDouble()
            (h * 3600 + m * 60 + s + frac)
        } else null

        // Parse every "Stream #X:Y" line
        val streamRegex = Regex("""Stream #(\d+):(\d+).*?: (\w+): (.*)""")
        val streams = streamRegex.findAll(output).map { match ->
            val streamIndex = match.groupValues[2].toInt()
            val streamType = match.groupValues[3].lowercase()  // "video", "audio", "subtitle", "data", "attachment"
            val detail = match.groupValues[4]
            val rawLine = match.value

            when (streamType) {
                "video" -> {
                    val codec = Regex("""^(\w+)""").find(detail)?.groupValues?.get(1)?.lowercase()
                    val resMatch = Regex("""(\d+)x(\d+)""").find(detail)
                    val w = resMatch?.groupValues?.get(1)?.toIntOrNull()
                    val h = resMatch?.groupValues?.get(2)?.toIntOrNull()
                    val sarMatch = Regex("""\[SAR (\d+):(\d+)""").find(detail)
                    val sn = sarMatch?.groupValues?.get(1)?.toIntOrNull()
                    val sd = sarMatch?.groupValues?.get(2)?.toIntOrNull()
                    val fpsMatch = Regex(""", (\d+(?:\.\d+)?)\s+fps""").find(detail)
                    val fps = fpsMatch?.groupValues?.get(1)?.toDoubleOrNull()
                    StreamInfo(streamIndex, "video", codec, w, h, sn, sd, fps, rawLine = rawLine)
                }
                "audio" -> {
                    val codec = Regex("""^(\w+)""").find(detail)?.groupValues?.get(1)?.lowercase()
                    val chMatch = Regex("""(\d+)\s+channels?|mono|stereo|5\.1|7\.1""").find(detail)
                    val channels = when {
                        chMatch == null -> null
                        chMatch.value == "mono" -> 1
                        chMatch.value == "stereo" -> 2
                        chMatch.value == "5.1" -> 6
                        chMatch.value == "7.1" -> 8
                        else -> chMatch.groupValues[1].toIntOrNull()
                    }
                    val layoutMatch = Regex("""(mono|stereo|5\.1(?:\(side\))?|7\.1)""").find(detail)
                    val channelLayout = layoutMatch?.value
                    val srMatch = Regex("""(\d+)\s*Hz""").find(detail)
                    val sampleRate = srMatch?.groupValues?.get(1)?.toIntOrNull()
                    val brMatch = Regex("""(\d+)\s*kb/s""").find(detail)
                    val bitrateKbps = brMatch?.groupValues?.get(1)?.toIntOrNull()
                    StreamInfo(streamIndex, "audio", codec, channels = channels,
                        channelLayout = channelLayout, sampleRate = sampleRate,
                        bitrateKbps = bitrateKbps, rawLine = rawLine)
                }
                else -> {
                    val codec = Regex("""^(\w+)""").find(detail)?.groupValues?.get(1)?.lowercase()
                    StreamInfo(streamIndex, streamType, codec, rawLine = rawLine)
                }
            }
        }.toList()

        ForBrowserProbeResult(durationSecs, streams, output)
    } catch (e: Exception) {
        log.warn("Failed to probe ForBrowser file {}: {}", file.name, e.message)
        ForBrowserProbeResult(null, emptyList(), "ERROR: ${e.message}")
    }
}
