package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpResponseWriter
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.Chapter
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.SkipSegment
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.transcode.BifGenerator
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile

/**
 * Armeria port of [net.stewart.mediamanager.VideoStreamServlet].
 *
 * Serves video streams at `/stream/{transcodeId}` with HTTP Range support,
 * plus auxiliary files (thumbnails, subtitles, chapters, BIF trick play).
 *
 * The main video endpoint uses a streaming response to avoid buffering entire
 * files in memory (some are >10GB). Sub-resource endpoints use @Blocking since
 * their payloads are small.
 */
class VideoStreamHttpService {

    private val log = LoggerFactory.getLogger(VideoStreamHttpService::class.java)

    private val directExtensions = setOf("mp4", "m4v")
    private val transcodeExtensions = setOf("mkv", "avi")
    private val thumbsJpgRegex = Regex("""thumbs_\d+\.jpg""")

    private val streamBytesCounter = MetricsRegistry.registry.counter("mm_stream_bytes_total")
    private val streamChunksCounter = MetricsRegistry.registry.counter("mm_stream_chunks_total")

    /**
     * Streams video with HTTP Range support. Uses a non-blocking handler that returns
     * a streaming response immediately, then writes file data on the blocking executor
     * with backpressure to avoid buffering large files in memory.
     */
    @Get("/stream/{transcodeId}")
    fun streamVideo(ctx: ServiceRequestContext, @Param("transcodeId") transcodeId: Long): HttpResponse {
        val writer = HttpResponse.streaming()
        ctx.blockingTaskExecutor().execute {
            doStreamVideo(ctx, transcodeId, writer)
        }
        return writer
    }

    private fun doStreamVideo(ctx: ServiceRequestContext, transcodeId: Long, writer: HttpResponseWriter) {
        MetricsRegistry.trackVideoStream().use {
        try {
            val transcode = Transcode.findById(transcodeId)
            if (transcode == null || transcode.file_path == null) {
                log.warn("Transcode not found or file_path null, id={}", transcodeId)
                writer.write(ResponseHeaders.of(HttpStatus.NOT_FOUND))
                MetricsRegistry.countHttpResponse("stream", 404)
                writer.close()
                return
            }

            // Rating enforcement
            val user = ArmeriaAuthDecorator.getUser(ctx)
            if (user != null) {
                val title = Title.findById(transcode.title_id)
                if (title != null && !user.canSeeRating(title.content_rating)) {
                    log.warn("Rating restricted: user='{}' ceiling={} title='{}' rating={}",
                        user.username, user.ratingCeilingValue?.label, title.name, title.content_rating)
                    writer.write(ResponseHeaders.of(HttpStatus.FORBIDDEN))
                    MetricsRegistry.countHttpResponse("stream", 403)
                    writer.close()
                    return
                }
            }

            val nasRoot = TranscoderAgent.getNasRoot()
            val sourceFile = File(transcode.file_path!!)
            if (!sourceFile.exists()) {
                log.warn("Source file does not exist on disk: {}", sourceFile.absolutePath)
                writer.write(ResponseHeaders.of(HttpStatus.NOT_FOUND))
                MetricsRegistry.countHttpResponse("stream", 404)
                writer.close()
                return
            }

            // Path traversal guard
            if (nasRoot != null && !sourceFile.canonicalPath.startsWith(File(nasRoot).canonicalPath)) {
                log.warn("Path traversal blocked: {} is outside NAS root", sourceFile.canonicalPath)
                writer.write(ResponseHeaders.of(HttpStatus.FORBIDDEN))
                MetricsRegistry.countHttpResponse("stream", 403)
                writer.close()
                return
            }

            val ext = sourceFile.extension.lowercase()
            val fileToStream = when {
                ext in directExtensions -> sourceFile
                ext in transcodeExtensions -> {
                    if (nasRoot == null) {
                        log.warn("NAS root path not configured for transcode id={}", transcodeId)
                        writer.write(ResponseHeaders.of(HttpStatus.SERVICE_UNAVAILABLE))
                        MetricsRegistry.countHttpResponse("stream", 503)
                        writer.close()
                        return
                    }
                    val forBrowserFile = TranscoderAgent.getForBrowserPath(nasRoot, transcode.file_path!!)
                    if (!forBrowserFile.exists()) {
                        log.info("ForBrowser file not yet available: {}", forBrowserFile.absolutePath)
                        writer.write(ResponseHeaders.of(HttpStatus.NOT_FOUND))
                        MetricsRegistry.countHttpResponse("stream", 404)
                        writer.close()
                        return
                    }
                    if (!forBrowserFile.canonicalPath.startsWith(File(nasRoot).canonicalPath)) {
                        log.warn("Path traversal blocked: {} is outside NAS root", forBrowserFile.canonicalPath)
                        writer.write(ResponseHeaders.of(HttpStatus.FORBIDDEN))
                        MetricsRegistry.countHttpResponse("stream", 403)
                        writer.close()
                        return
                    }
                    forBrowserFile
                }
                else -> {
                    log.warn("Unsupported file extension: {}", ext)
                    writer.write(ResponseHeaders.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE))
                    MetricsRegistry.countHttpResponse("stream", 415)
                    writer.close()
                    return
                }
            }

            streamFileToWriter(fileToStream, ctx, writer)
        } catch (e: Exception) {
            log.warn("Video stream error: {}", e.message)
            try { writer.close() } catch (_: Exception) {}
        }
        }
    }

    /**
     * Streams a file with HTTP Range support. Writes headers and data chunks to the
     * HttpResponseWriter with backpressure (whenConsumed) to avoid buffering.
     */
    private fun streamFileToWriter(file: File, ctx: ServiceRequestContext, writer: HttpResponseWriter) {
        val fileLength = file.length()
        val rangeHeader = ctx.request().headers().get("range")

        val range = if (rangeHeader == null) {
            0L to (fileLength - 1)
        } else {
            val parsed = parseRange(rangeHeader, fileLength)
            if (parsed == null) {
                log.warn("Invalid range '{}' for file size {}", rangeHeader, fileLength)
                writer.write(ResponseHeaders.builder(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .add("Content-Range", "bytes */$fileLength")
                    .build())
                MetricsRegistry.countHttpResponse("stream", 416)
                writer.close()
                return
            }
            parsed
        }

        val (start, requestedEnd) = range
        // Cap response at 10MB to prevent Armeria request timeout on large ranges.
        // Browsers seamlessly issue follow-up Range requests for the next chunk.
        val maxChunkSize = 10L * 1024 * 1024
        val end = minOf(requestedEnd, start + maxChunkSize - 1)
        val contentLength = end - start + 1

        val headers = ResponseHeaders.builder(HttpStatus.PARTIAL_CONTENT)
            .contentType(MediaType.parse("video/mp4"))
            .add("Accept-Ranges", "bytes")
            .add("Content-Range", "bytes $start-$end/$fileLength")
            .contentLength(contentLength)
            .build()

        writer.write(headers)

        log.info("STREAM {} {}-{} ({}) of {}",
            file.name, humanSize(start), humanSize(end), humanSize(contentLength), humanSize(fileLength))

        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                var remaining = contentLength
                while (remaining > 0) {
                    // Allocate each buffer fresh so HttpData.wrap() can take ownership
                    // without copying. 256KB chunks reduce allocation count vs 8KB.
                    val toRead = minOf(256 * 1024L, remaining).toInt()
                    val buf = ByteArray(toRead)
                    val read = raf.read(buf, 0, toRead)
                    if (read == -1) break
                    writer.whenConsumed().join()
                    val data = if (read == buf.size) HttpData.wrap(buf) else HttpData.wrap(buf, 0, read)
                    if (!writer.tryWrite(data)) {
                        break // Client disconnected
                    }
                    remaining -= read
                }
            }
            streamBytesCounter.increment(contentLength.toDouble())
            streamChunksCounter.increment()
            MetricsRegistry.countHttpResponse("stream", 206)
        } catch (e: Exception) {
            log.warn("Range stream error: {}", e.message)
        } finally {
            writer.close()
        }
    }

    /**
     * Handles sub-resources: thumbnails, subtitles, chapters, BIF trick play.
     * Uses @Blocking since payloads are small (KB to low MB).
     */
    @Blocking
    @Get("/stream/{transcodeId}/{subPath}")
    fun subResource(ctx: ServiceRequestContext, @Param("transcodeId") transcodeId: Long,
                    @Param("subPath") subPath: String): HttpResponse {
        if (!checkRatingForTranscode(transcodeId, ctx)) return HttpResponse.of(HttpStatus.FORBIDDEN)

        return when {
            subPath == "thumbs.vtt" -> serveThumbFile(transcodeId, subPath, "text/vtt")
            subPath.matches(thumbsJpgRegex) -> serveThumbFile(transcodeId, subPath, "image/jpeg")
            subPath == "trickplay.bif" -> serveBifFile(transcodeId)
            subPath == "subs.vtt" -> serveSubtitleFile(transcodeId, asVtt = true)
            subPath == "subs.srt" -> serveSubtitleFile(transcodeId, asVtt = false)
            subPath == "chapters.json" -> serveChapters(transcodeId)
            subPath == "next-episode" -> serveNextEpisode(transcodeId)
            else -> {
                MetricsRegistry.countHttpResponse("stream", 404)
                HttpResponse.of(HttpStatus.NOT_FOUND)
            }
        }
    }

    private fun checkRatingForTranscode(transcodeId: Long, ctx: ServiceRequestContext): Boolean {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return true
        val transcode = Transcode.findById(transcodeId) ?: return true
        val title = Title.findById(transcode.title_id) ?: return true
        if (!user.canSeeRating(title.content_rating)) {
            log.warn("Rating restricted on sub-resource: user='{}' ceiling={} title='{}' rating={}",
                user.username, user.ratingCeilingValue?.label, title.name, title.content_rating)
            MetricsRegistry.countHttpResponse("stream", 403)
            return false
        }
        return true
    }

    private fun serveThumbFile(transcodeId: Long, fileName: String, contentType: String): HttpResponse {
        val transcode = Transcode.findById(transcodeId)
        if (transcode == null || transcode.file_path == null) {
            MetricsRegistry.countHttpResponse("stream", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val auxSuffix = ".$fileName"
        val thumbFile = TranscoderAgent.findAuxFile(nasRoot, transcode.file_path!!, auxSuffix)
        if (thumbFile == null) {
            MetricsRegistry.countHttpResponse("stream", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        // Path traversal guard
        if (nasRoot != null && !thumbFile.canonicalPath.startsWith(File(nasRoot).canonicalPath)) {
            MetricsRegistry.countHttpResponse("stream", 403)
            return HttpResponse.of(HttpStatus.FORBIDDEN)
        }

        val bytes = if (contentType == "text/vtt") {
            // Rewrite sprite filenames in VTT to use generic names (thumbs_N.jpg)
            // so they match the URL routing. On disk, files are named like
            // "Canadian Bacon.thumbs_4.jpg" but the URL namespace uses "thumbs_4.jpg".
            thumbFile.readLines().joinToString("\n") { line ->
                val idx = line.indexOf("thumbs_")
                if (idx > 0) line.substring(idx) else line
            }.toByteArray(Charsets.UTF_8)
        } else {
            thumbFile.readBytes()
        }

        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.parse(contentType))
            .contentLength(bytes.size.toLong())
            .add("Cache-Control", "public, max-age=86400")
            .build()
        MetricsRegistry.countHttpResponse("stream", 200)
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun serveBifFile(transcodeId: Long): HttpResponse {
        val transcode = Transcode.findById(transcodeId)
        if (transcode == null || transcode.file_path == null) {
            MetricsRegistry.countHttpResponse("stream", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val vttFile = TranscoderAgent.findAuxFile(nasRoot, transcode.file_path!!, ".thumbs.vtt")
        if (vttFile == null) {
            MetricsRegistry.countHttpResponse("stream", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val baseName = File(transcode.file_path!!).nameWithoutExtension
        val bifBytes = BifGenerator.generateBytes(baseName, vttFile.parentFile)
        if (bifBytes == null) {
            MetricsRegistry.countHttpResponse("stream", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.OCTET_STREAM)
            .contentLength(bifBytes.size.toLong())
            .add("Cache-Control", "public, max-age=86400")
            .build()
        MetricsRegistry.countHttpResponse("stream", 200)
        return HttpResponse.of(headers, HttpData.wrap(bifBytes))
    }

    private fun serveSubtitleFile(transcodeId: Long, asVtt: Boolean = true): HttpResponse {
        val transcode = Transcode.findById(transcodeId)
        if (transcode == null || transcode.file_path == null) {
            MetricsRegistry.countHttpResponse("stream", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val srtFile = TranscoderAgent.findAuxFile(nasRoot, transcode.file_path!!, ".en.srt")
        if (srtFile == null) {
            MetricsRegistry.countHttpResponse("stream", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        // Path traversal guard
        if (nasRoot != null && !srtFile.canonicalPath.startsWith(File(nasRoot).canonicalPath)) {
            MetricsRegistry.countHttpResponse("stream", 403)
            return HttpResponse.of(HttpStatus.FORBIDDEN)
        }

        if (asVtt) {
            val srtContent = srtFile.readText()
            val vttContent = "WEBVTT\n\n" + srtContent.replace(Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
            val bytes = vttContent.toByteArray(Charsets.UTF_8)
            val headers = ResponseHeaders.builder(HttpStatus.OK)
                .contentType(MediaType.parse("text/vtt; charset=utf-8"))
                .contentLength(bytes.size.toLong())
                .add("Cache-Control", "public, max-age=86400")
                .build()
            MetricsRegistry.countHttpResponse("stream", 200)
            return HttpResponse.of(headers, HttpData.wrap(bytes))
        } else {
            val bytes = srtFile.readBytes()
            val headers = ResponseHeaders.builder(HttpStatus.OK)
                .contentType(MediaType.parse("application/x-subrip; charset=utf-8"))
                .contentLength(bytes.size.toLong())
                .add("Cache-Control", "public, max-age=86400")
                .build()
            MetricsRegistry.countHttpResponse("stream", 200)
            return HttpResponse.of(headers, HttpData.wrap(bytes))
        }
    }

    private fun serveChapters(transcodeId: Long): HttpResponse {
        val chapters = Chapter.findAll()
            .filter { it.transcode_id == transcodeId }
            .sortedBy { it.chapter_number }

        val skipSegments = SkipSegment.findAll()
            .filter { it.transcode_id == transcodeId }

        val data = mapOf(
            "chapters" to chapters.map { ch ->
                mapOf(
                    "number" to ch.chapter_number,
                    "start" to ch.start_seconds,
                    "end" to ch.end_seconds,
                    "title" to ch.title
                )
            },
            "skipSegments" to skipSegments.map { seg ->
                mapOf(
                    "type" to seg.segment_type,
                    "start" to seg.start_seconds,
                    "end" to seg.end_seconds,
                    "method" to seg.detection_method
                )
            }
        )

        val json = Gson().toJson(data)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .add("Cache-Control", "public, max-age=300")
            .build()
        MetricsRegistry.countHttpResponse("stream", 200)
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun serveNextEpisode(transcodeId: Long): HttpResponse {
        val next = findNextPlayableEpisode(transcodeId)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val json = Gson().toJson(mapOf("transcodeId" to next.first, "label" to next.second))
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        MetricsRegistry.countHttpResponse("stream", 200)
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes / 1024.0
        for (unit in units) {
            if (value < 1024 || unit == units.last()) return "%.1f%s".format(value, unit)
            value /= 1024.0
        }
        return "${bytes}B"
    }

    private fun parseRange(rangeHeader: String, fileLength: Long): Pair<Long, Long>? {
        if (!rangeHeader.startsWith("bytes=")) return null
        val range = rangeHeader.removePrefix("bytes=").trim()
        val dashIndex = range.indexOf('-')
        if (dashIndex < 0) return null

        return try {
            val startStr = range.substring(0, dashIndex).trim()
            val endStr = range.substring(dashIndex + 1).trim()

            if (startStr.isEmpty()) {
                val suffix = endStr.toLong()
                val start = maxOf(fileLength - suffix, 0)
                start to (fileLength - 1)
            } else {
                val start = startStr.toLong()
                val end = if (endStr.isEmpty()) fileLength - 1 else minOf(endStr.toLong(), fileLength - 1)
                if (start > end || start >= fileLength) return null
                start to end
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Find the next playable episode after the given transcode. Returns (transcodeId, label) or null. */
    private fun findNextPlayableEpisode(currentTranscodeId: Long): Pair<Long, String>? {
        val currentTranscode = Transcode.findById(currentTranscodeId) ?: return null
        val currentEpisodeId = currentTranscode.episode_id ?: return null
        val currentEpisode = Episode.findById(currentEpisodeId) ?: return null

        val titleId = currentTranscode.title_id
        val allEpisodes = Episode.findAll()
            .filter { it.title_id == titleId }
            .sortedWith(compareBy({ it.season_number }, { it.episode_number }))

        val currentIndex = allEpisodes.indexOfFirst { it.id == currentEpisodeId }
        if (currentIndex < 0) return null

        val titleTranscodes = Transcode.findAll().filter { it.title_id == titleId }
        val nasRoot = TranscoderAgent.getNasRoot()

        for (i in (currentIndex + 1) until allEpisodes.size) {
            val nextEp = allEpisodes[i]
            val tc = titleTranscodes.firstOrNull { it.episode_id == nextEp.id } ?: continue
            val filePath = tc.file_path ?: continue
            val canPlay = if (TranscoderAgent.needsTranscoding(filePath)) {
                nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, filePath)
            } else true
            if (canPlay) {
                val seasonEp = "S%02dE%02d".format(nextEp.season_number, nextEp.episode_number)
                val label = nextEp.name?.let { "$seasonEp \u2014 $it" } ?: seasonEp
                return tc.id!! to label
            }
        }
        return null
    }
}
