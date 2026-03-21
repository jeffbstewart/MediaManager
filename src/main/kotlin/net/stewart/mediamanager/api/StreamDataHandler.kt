package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.MetricsRegistry

/**
 * Handles GET /api/v1/stream/{transcodeId}/chapters
 *
 * Returns chapter markers and skip segments (intro, end credits) for a transcode.
 * Enforces parental controls — user must be able to see the title's rating.
 */
object StreamDataHandler {

    fun handleChapters(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        // Parse transcode ID from "stream/{id}/chapters"
        val transcodeId = path.removePrefix("stream/").removeSuffix("/chapters").toLongOrNull()
        if (transcodeId == null) {
            ApiV1Servlet.sendError(resp, 400, "invalid_transcode_id")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        // Parental controls
        val transcode = Transcode.findById(transcodeId)
        if (transcode == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }
        val title = Title.findById(transcode.title_id)
        if (title == null || !user.canSeeRating(title.content_rating)) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

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
            "skip_segments" to skipSegments.map { seg ->
                mapOf(
                    "type" to seg.segment_type,
                    "start" to seg.start_seconds,
                    "end" to seg.end_seconds,
                    "method" to seg.detection_method
                )
            }
        )

        ApiV1Servlet.sendJson(resp, 200, data, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
