package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.PlaybackProgressService

/**
 * Handles /api/v1/playback/progress/{transcodeId} endpoints:
 * - GET — retrieve saved position
 * - POST — record position (body: {"position":123.4,"duration":7200.0})
 * - DELETE — clear progress
 */
object PlaybackHandler {

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        // Parse transcode ID from path: "progress/{id}"
        val transcodeId = path.removePrefix("progress/").toLongOrNull()
        if (transcodeId == null || !path.startsWith("progress/")) {
            ApiV1Servlet.sendError(resp, 400, "invalid_transcode_id")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        when (req.method) {
            "GET" -> {
                val progress = PlaybackProgressService.getProgressForUser(user.id!!, transcodeId)
                if (progress == null) {
                    ApiV1Servlet.sendError(resp, 404, "not_found")
                    MetricsRegistry.countHttpResponse("api_v1", 404)
                    return
                }
                ApiV1Servlet.sendJson(resp, 200, ApiPlaybackProgress(
                    transcodeId = progress.transcode_id,
                    positionSeconds = progress.position_seconds,
                    durationSeconds = progress.duration_seconds,
                    updatedAt = progress.updated_at?.toString()
                ), mapper)
                MetricsRegistry.countHttpResponse("api_v1", 200)
            }
            "POST" -> {
                val body = try {
                    mapper.readTree(req.reader)
                } catch (e: Exception) {
                    ApiV1Servlet.sendError(resp, 400, "invalid_request")
                    MetricsRegistry.countHttpResponse("api_v1", 400)
                    return
                }
                val position = body.get("position")?.asDouble()
                if (position == null) {
                    ApiV1Servlet.sendError(resp, 400, "missing_position")
                    MetricsRegistry.countHttpResponse("api_v1", 400)
                    return
                }
                val duration = body.get("duration")?.asDouble()
                PlaybackProgressService.recordProgressForUser(user.id!!, transcodeId, position, duration)
                resp.status = 204
                MetricsRegistry.countHttpResponse("api_v1", 204)
            }
            "DELETE" -> {
                val progress = PlaybackProgressService.getProgressForUser(user.id!!, transcodeId)
                if (progress != null) {
                    progress.delete()
                }
                resp.status = 204
                MetricsRegistry.countHttpResponse("api_v1", 204)
            }
            else -> {
                ApiV1Servlet.sendError(resp, 405, "method_not_allowed")
                MetricsRegistry.countHttpResponse("api_v1", 405)
            }
        }
    }
}
