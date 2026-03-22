package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.TranscoderAgent
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Handles /api/v1/downloads/... endpoints:
 * - GET /downloads/available — titles with ForMobile files ready
 * - GET /downloads/manifest/{transcodeId} — file size + checksum
 * - POST /downloads/batch-manifest — multiple manifests at once
 * - GET /downloads/{transcodeId} — serve ForMobile MP4 with Range support
 */
object DownloadHandler {

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        when {
            path == "available" && req.method == "GET" -> handleAvailable(req, resp, mapper, user)
            path.startsWith("manifest/") && req.method == "GET" -> {
                val transcodeId = path.removePrefix("manifest/").toLongOrNull()
                if (transcodeId != null) handleManifest(req, resp, mapper, user, transcodeId)
                else {
                    ApiV1Servlet.sendError(resp, 400, "invalid_transcode_id")
                    MetricsRegistry.countHttpResponse("api_v1", 400)
                }
            }
            path == "batch-manifest" && req.method == "POST" -> handleBatchManifest(req, resp, mapper, user)
            path.matches(Regex("\\d+")) && req.method == "GET" -> {
                val transcodeId = path.toLong()
                handleDownload(req, resp, user, transcodeId)
            }
            else -> {
                ApiV1Servlet.sendError(resp, 404, "not_found")
                MetricsRegistry.countHttpResponse("api_v1", 404)
            }
        }
    }

    private fun handleAvailable(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, user: AppUser) {
        val transcodes = Transcode.findAll().filter { it.for_mobile_available }
        val titleIds = transcodes.map { it.title_id }.toSet()
        val titles = Title.findAll()
            .filter { it.id in titleIds && !it.hidden }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesById = titles.associateBy { it.id }

        val items = transcodes
            .filter { titlesById.containsKey(it.title_id) }
            .mapNotNull { tc ->
                val title = titlesById[tc.title_id] ?: return@mapNotNull null
                val posterUrl = if (title.poster_path != null) "/posters/w500/${title.id}" else null
                mapOf(
                    "transcode_id" to tc.id,
                    "title_id" to title.id,
                    "title_name" to title.name,
                    "media_type" to title.media_type,
                    "year" to title.release_year,
                    "poster_url" to posterUrl,
                    "content_rating" to title.content_rating,
                    "quality" to "1080p"
                )
            }

        ApiV1Servlet.sendJson(resp, 200, mapOf("downloads" to items), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleManifest(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, user: AppUser, transcodeId: Long) {
        val manifest = buildManifest(transcodeId, user)
        if (manifest == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }
        ApiV1Servlet.sendJson(resp, 200, manifest, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleBatchManifest(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, user: AppUser) {
        val body = try {
            mapper.readTree(req.reader)
        } catch (e: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val idsNode = body.get("transcode_ids")
        if (idsNode == null || !idsNode.isArray) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val manifests = idsNode.mapNotNull { node ->
            val id = node.asLong()
            buildManifest(id, user)
        }

        ApiV1Servlet.sendJson(resp, 200, mapOf("manifests" to manifests), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun buildManifest(transcodeId: Long, user: AppUser): Map<String, Any?>? {
        val tc = Transcode.findById(transcodeId) ?: return null
        if (!tc.for_mobile_available) return null

        val title = Title.findById(tc.title_id) ?: return null
        if (title.hidden || !user.canSeeRating(title.content_rating)) return null

        val nasRoot = TranscoderAgent.getNasRoot() ?: return null
        val mobileFile = TranscoderAgent.getForMobilePath(nasRoot, tc.file_path!!)
        if (!mobileFile.exists()) return null

        val hasSubtitles = TranscoderAgent.findAuxFile(nasRoot, tc.file_path!!, ".en.srt") != null
        val hasThumbnails = TranscoderAgent.findAuxFile(nasRoot, tc.file_path!!, ".thumbs.vtt") != null
        val hasChapters = Chapter.findAll().any { it.transcode_id == tc.id }

        return mapOf(
            "transcode_id" to tc.id,
            "title_id" to title.id,
            "title_name" to title.name,
            "file_size_bytes" to mobileFile.length(),
            "quality" to "1080p",
            "has_subtitles" to hasSubtitles,
            "has_thumbnails" to hasThumbnails,
            "has_chapters" to hasChapters
        )
    }

    private fun handleDownload(req: HttpServletRequest, resp: HttpServletResponse, user: AppUser, transcodeId: Long) {
        val tc = Transcode.findById(transcodeId)
        if (tc == null || !tc.for_mobile_available) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val title = Title.findById(tc.title_id)
        if (title == null || title.hidden || !user.canSeeRating(title.content_rating)) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        if (nasRoot == null) {
            ApiV1Servlet.sendError(resp, 503, "nas_unavailable")
            MetricsRegistry.countHttpResponse("api_v1", 503)
            return
        }

        val mobileFile = TranscoderAgent.getForMobilePath(nasRoot, tc.file_path!!)
        if (!mobileFile.exists()) {
            ApiV1Servlet.sendError(resp, 404, "file_not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        streamFile(mobileFile, req, resp)
    }

    /**
     * Streams a file with HTTP Range support for resumable downloads.
     */
    private fun streamFile(file: File, req: HttpServletRequest, resp: HttpServletResponse) {
        val fileLength = file.length()
        val rangeHeader = req.getHeader("Range")

        resp.contentType = "video/mp4"
        resp.setHeader("Accept-Ranges", "bytes")

        val (start, requestedEnd) = if (rangeHeader == null) {
            0L to (fileLength - 1)
        } else {
            parseRange(rangeHeader, fileLength) ?: run {
                resp.setHeader("Content-Range", "bytes */$fileLength")
                resp.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE)
                MetricsRegistry.countHttpResponse("api_v1", 416)
                return
            }
        }

        val end = requestedEnd.coerceAtMost(fileLength - 1)
        val contentLength = end - start + 1

        resp.status = HttpServletResponse.SC_PARTIAL_CONTENT
        resp.setHeader("Content-Range", "bytes $start-$end/$fileLength")
        resp.setContentLengthLong(contentLength)

        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(65536)
                var remaining = contentLength
                val out = resp.outputStream
                while (remaining > 0) {
                    val toRead = buffer.size.toLong().coerceAtMost(remaining).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
                out.flush()
            }
            MetricsRegistry.countHttpResponse("api_v1", 206)
        } catch (e: Exception) {
            // Client disconnected or IO error — don't send error response
        }
    }

    private fun parseRange(rangeHeader: String, fileLength: Long): Pair<Long, Long>? {
        if (!rangeHeader.startsWith("bytes=")) return null
        val range = rangeHeader.removePrefix("bytes=").trim()
        val dashIdx = range.indexOf('-')
        if (dashIdx < 0) return null

        val startStr = range.substring(0, dashIdx).trim()
        val endStr = range.substring(dashIdx + 1).trim()

        return if (startStr.isEmpty()) {
            // Suffix range: bytes=-500
            val suffix = endStr.toLongOrNull() ?: return null
            val start = (fileLength - suffix).coerceAtLeast(0)
            start to (fileLength - 1)
        } else {
            val start = startStr.toLongOrNull() ?: return null
            val end = if (endStr.isEmpty()) fileLength - 1 else endStr.toLongOrNull() ?: return null
            if (start > end || start >= fileLength) return null
            start to end
        }
    }
}
