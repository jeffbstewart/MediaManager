package net.stewart.mediamanager

import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import com.github.vokorm.findAll
import com.google.gson.Gson
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Chapter
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
 * Serves video streams for in-browser playback at /stream/{transcodeId}.
 *
 * MP4/M4V files are streamed directly from source. MKV/AVI files are served from the
 * pre-transcoded ForBrowser mirror (background TranscoderAgent). Returns 404 if the
 * ForBrowser transcode doesn't exist yet.
 */
@WebServlet(urlPatterns = ["/stream/*"])
class VideoStreamServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(VideoStreamServlet::class.java)

    private val directExtensions = setOf("mp4", "m4v")
    private val transcodeExtensions = setOf("mkv", "avi")

    private val streamBytesCounter = MetricsRegistry.registry.counter("mm_stream_bytes_total")
    private val streamChunksCounter = MetricsRegistry.registry.counter("mm_stream_chunks_total")


    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val parts = req.pathInfo?.removePrefix("/")?.split("/") ?: run {
            log.warn("Stream request with no pathInfo, returning 400")
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing path")
            MetricsRegistry.countHttpResponse("stream", 400)
            return
        }

        if (parts.isEmpty()) {
            log.warn("Stream request with empty path parts, returning 400")
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing transcode ID")
            MetricsRegistry.countHttpResponse("stream", 400)
            return
        }

        val transcodeId = parts[0].toLongOrNull() ?: run {
            log.warn("Non-numeric transcodeId='{}', returning 400", parts[0])
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid transcode ID")
            MetricsRegistry.countHttpResponse("stream", 400)
            return
        }

        // Route: /stream/{id}/thumbs.vtt or /stream/{id}/thumbs_{n}.jpg
        if (parts.size >= 2) {
            // Rating enforcement for sub-resources (thumbs, BIF, subtitles)
            if (!checkRatingForTranscode(transcodeId, req, resp)) return

            val subPath = parts[1]
            when {
                subPath == "thumbs.vtt" -> serveThumbFile(transcodeId, subPath, "text/vtt", resp)
                subPath.matches(Regex("""thumbs_\d+\.jpg""")) -> serveThumbFile(transcodeId, subPath, "image/jpeg", resp)
                subPath == "trickplay.bif" -> serveBifFile(transcodeId, resp)
                subPath == "subs.vtt" -> serveSubtitleFile(transcodeId, resp)
                subPath == "subs.srt" -> serveSubtitleFile(transcodeId, resp, asVtt = false)
                subPath == "chapters.json" -> serveChapters(transcodeId, resp)
                else -> {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                    MetricsRegistry.countHttpResponse("stream", 404)
                }
            }
            return
        }

        // Route: /stream/{id}
        serveVideo(transcodeId, req, resp)
    }

    /**
     * Checks whether the authenticated user is allowed to see content at this transcode's
     * rating. Returns true if allowed (or no user/title found), false after sending 403.
     */
    private fun checkRatingForTranscode(transcodeId: Long, req: HttpServletRequest, resp: HttpServletResponse): Boolean {
        val user = req.getAttribute(AuthFilter.USER_ATTRIBUTE) as? AppUser ?: return true
        val transcode = Transcode.findById(transcodeId) ?: return true
        val title = Title.findById(transcode.title_id) ?: return true
        if (!user.canSeeRating(title.content_rating)) {
            log.warn("Rating restricted on sub-resource: user='{}' ceiling={} title='{}' rating={}",
                user.username, user.rating_ceiling, title.name, title.content_rating)
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Content restricted by parental controls")
            MetricsRegistry.countHttpResponse("stream", 403)
            return false
        }
        return true
    }

    private fun serveVideo(transcodeId: Long, req: HttpServletRequest, resp: HttpServletResponse) {
        val transcode = Transcode.findById(transcodeId)
        if (transcode == null || transcode.file_path == null) {
            log.warn("Transcode not found or file_path null, id={}", transcodeId)
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("stream", 404)
            return
        }

        // Rating enforcement: check user ceiling against title rating
        val user = req.getAttribute(AuthFilter.USER_ATTRIBUTE) as? AppUser
        if (user != null) {
            val title = Title.findById(transcode.title_id)
            if (title != null && !user.canSeeRating(title.content_rating)) {
                log.warn("Rating restricted: user='{}' ceiling={} title='{}' rating={}",
                    user.username, user.rating_ceiling, title.name, title.content_rating)
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Content restricted by parental controls")
                MetricsRegistry.countHttpResponse("stream", 403)
                return
            }
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val sourceFile = File(transcode.file_path!!)
        if (!sourceFile.exists()) {
            log.warn("Source file does not exist on disk: {}", sourceFile.absolutePath)
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("stream", 404)
            return
        }

        // L3 fix: path traversal guard — served file must be within NAS root
        if (nasRoot != null && !sourceFile.canonicalPath.startsWith(File(nasRoot).canonicalPath)) {
            log.warn("Path traversal blocked: {} is outside NAS root", sourceFile.canonicalPath)
            resp.sendError(HttpServletResponse.SC_FORBIDDEN)
            MetricsRegistry.countHttpResponse("stream", 403)
            return
        }

        val ext = sourceFile.extension.lowercase()
        when {
            ext in directExtensions -> streamFile(sourceFile, req, resp)
            ext in transcodeExtensions -> {
                if (nasRoot == null) {
                    log.warn("NAS root path not configured for transcode id={}", transcodeId)
                    resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE)
                    MetricsRegistry.countHttpResponse("stream", 503)
                    return
                }
                val forBrowserFile = TranscoderAgent.getForBrowserPath(nasRoot, transcode.file_path!!)
                if (!forBrowserFile.exists()) {
                    log.info("ForBrowser file not yet available: {}", forBrowserFile.absolutePath)
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                    MetricsRegistry.countHttpResponse("stream", 404)
                    return
                }
                if (!forBrowserFile.canonicalPath.startsWith(File(nasRoot).canonicalPath)) {
                    log.warn("Path traversal blocked: {} is outside NAS root", forBrowserFile.canonicalPath)
                    resp.sendError(HttpServletResponse.SC_FORBIDDEN)
                    MetricsRegistry.countHttpResponse("stream", 403)
                    return
                }
                streamFile(forBrowserFile, req, resp)
            }
            else -> {
                log.warn("Unsupported file extension: {}", ext)
                resp.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE)
                MetricsRegistry.countHttpResponse("stream", 415)
            }
        }
    }

    /**
     * Serves a thumbnail sprite file (VTT or sprite sheet JPG) for a given transcode.
     * Looks alongside the source file first, falls back to ForBrowser.
     */
    private fun serveThumbFile(transcodeId: Long, fileName: String, contentType: String, resp: HttpServletResponse) {
        val transcode = Transcode.findById(transcodeId)
        if (transcode == null || transcode.file_path == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("stream", 404)
            return
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val filePath = transcode.file_path!!

        // Map generic request name to file suffix:
        // thumbs.vtt -> .thumbs.vtt, thumbs_1.jpg -> .thumbs_1.jpg
        val auxSuffix = "." + fileName

        // Look alongside source first, ForBrowser as fallback
        val thumbFile = TranscoderAgent.findAuxFile(nasRoot, filePath, auxSuffix)
        if (thumbFile == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("stream", 404)
            return
        }

        // Path traversal guard
        if (nasRoot != null && !thumbFile.canonicalPath.startsWith(File(nasRoot).canonicalPath)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN)
            MetricsRegistry.countHttpResponse("stream", 403)
            return
        }

        resp.contentType = contentType
        resp.setContentLengthLong(thumbFile.length())
        resp.setHeader("Cache-Control", "public, max-age=86400")
        thumbFile.inputStream().use { it.copyTo(resp.outputStream) }
        MetricsRegistry.countHttpResponse("stream", 200)
    }

    /**
     * Generates and serves a BIF (Base Index Frame) file on-the-fly from existing
     * thumbnail sprite sheets. No BIF stored on disk — built in memory per request.
     */
    private fun serveBifFile(transcodeId: Long, resp: HttpServletResponse) {
        val transcode = Transcode.findById(transcodeId)
        if (transcode == null || transcode.file_path == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("stream", 404)
            return
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val filePath = transcode.file_path!!

        // Find the VTT file (source dir first, ForBrowser fallback) to locate sprite sheets
        val vttFile = TranscoderAgent.findAuxFile(nasRoot, filePath, ".thumbs.vtt")
        if (vttFile == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("stream", 404)
            return
        }

        // Generate BIF on-the-fly from sprite sheets in the same directory as the VTT
        val baseName = File(filePath).nameWithoutExtension
        val bifBytes = BifGenerator.generateBytes(baseName, vttFile.parentFile)
        if (bifBytes == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("stream", 404)
            return
        }

        resp.contentType = "application/octet-stream"
        resp.setContentLengthLong(bifBytes.size.toLong())
        resp.setHeader("Cache-Control", "public, max-age=86400")
        resp.outputStream.write(bifBytes)
        MetricsRegistry.countHttpResponse("stream", 200)
    }

    /**
     * Serves an SRT subtitle file for a given transcode, optionally converting to WebVTT.
     * Looks alongside the source file first, falls back to ForBrowser.
     */
    private fun serveSubtitleFile(transcodeId: Long, resp: HttpServletResponse, asVtt: Boolean = true) {
        val transcode = Transcode.findById(transcodeId)
        if (transcode == null || transcode.file_path == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("stream", 404)
            return
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val filePath = transcode.file_path!!

        val srtFile = TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt")
        if (srtFile == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("stream", 404)
            return
        }

        // Path traversal guard
        if (nasRoot != null && !srtFile.canonicalPath.startsWith(File(nasRoot).canonicalPath)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN)
            MetricsRegistry.countHttpResponse("stream", 403)
            return
        }

        if (asVtt) {
            // Convert SRT to WebVTT on the fly
            val srtContent = srtFile.readText()
            val vttContent = "WEBVTT\n\n" + srtContent.replace(Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
            resp.contentType = "text/vtt"
            resp.characterEncoding = "UTF-8"
            resp.setHeader("Cache-Control", "public, max-age=86400")
            resp.setContentLength(vttContent.toByteArray(Charsets.UTF_8).size)
            resp.writer.write(vttContent)
        } else {
            resp.contentType = "application/x-subrip"
            resp.characterEncoding = "UTF-8"
            resp.setContentLengthLong(srtFile.length())
            resp.setHeader("Cache-Control", "public, max-age=86400")
            srtFile.inputStream().use { it.copyTo(resp.outputStream) }
        }
        MetricsRegistry.countHttpResponse("stream", 200)
    }

    /**
     * Serves chapter markers and skip segments as JSON for the player UI.
     */
    private fun serveChapters(transcodeId: Long, resp: HttpServletResponse) {
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

        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "public, max-age=300")
        resp.writer.write(Gson().toJson(data))
        MetricsRegistry.countHttpResponse("stream", 200)
    }

    /**
     * Streams a file with HTTP Range support. Always responds with 206 Partial Content.
     * The full requested range is served; TCP flow control handles backpressure.
     */
    private fun streamFile(file: File, req: HttpServletRequest, resp: HttpServletResponse) {
        val fileLength = file.length()
        val rangeHeader = req.getHeader("Range")

        resp.contentType = "video/mp4"
        resp.setHeader("Accept-Ranges", "bytes")

        // Always respond with 206 Partial Content — video players (Roku, browsers)
        // expect Range-based streaming. When no Range header is present, treat as
        // requesting the first chunk.
        val (start, requestedEnd) = if (rangeHeader == null) {
            0L to (fileLength - 1)
        } else {
            parseRange(rangeHeader, fileLength) ?: run {
                log.warn("Invalid range '{}' for file size {}", rangeHeader, fileLength)
                resp.setHeader("Content-Range", "bytes */$fileLength")
                resp.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE)
                MetricsRegistry.countHttpResponse("stream", 416)
                return
            }
        }

        val end = requestedEnd
        val contentLength = end - start + 1

        resp.status = HttpServletResponse.SC_PARTIAL_CONTENT
        resp.setHeader("Content-Range", "bytes $start-$end/$fileLength")
        resp.setContentLengthLong(contentLength)

        log.info("STREAM {} {}-{} ({}) of {}",
            file.name, humanSize(start), humanSize(end), humanSize(contentLength), humanSize(fileLength))

        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(8192)
                var remaining = contentLength
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read == -1) break
                    resp.outputStream.write(buf, 0, read)
                    remaining -= read
                }
            }
            streamBytesCounter.increment(contentLength.toDouble())
            streamChunksCounter.increment()
            MetricsRegistry.countHttpResponse("stream", 206)
        } catch (e: Exception) {
            log.warn("Range stream error: {}", e.message)
        }
    }

    /** Formats a byte count as a short human-readable string using base-2 SI units. */
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

    /**
     * Parses an HTTP Range header value. Returns (start, end) inclusive or null if invalid.
     */
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
}
