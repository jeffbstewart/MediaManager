package net.stewart.mediamanager

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.PlaybackProgressService
import org.slf4j.LoggerFactory

/**
 * REST endpoint for playback progress tracking.
 *
 * POST /playback-progress/{transcodeId}  — record position (body: {"position":123.4,"duration":7200.0})
 * GET  /playback-progress/{transcodeId}  — get saved position (returns JSON or 404)
 * DELETE /playback-progress/{transcodeId} — clear progress
 *
 * Auth: cookie auth → use session user. API key (?key=) → Roku user. No auth → 401.
 */
@WebServlet(urlPatterns = ["/playback-progress/*"])
class PlaybackProgressServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(PlaybackProgressServlet::class.java)
    private val mapper = ObjectMapper()

    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        val transcodeId = parseTranscodeId(req, resp) ?: return
        val userId = resolveUserId(req, resp) ?: return

        val body = try {
            val bytes = req.inputStream.readNBytes(1024)
            mapper.readTree(bytes)
        } catch (e: Exception) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON")
            return
        }

        val position = body.get("position")?.asDouble() ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing 'position' field")
            return
        }
        val duration = body.get("duration")?.asDouble()

        PlaybackProgressService.recordProgressForUser(userId, transcodeId, position, duration)
        resp.status = HttpServletResponse.SC_NO_CONTENT
    }

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val transcodeId = parseTranscodeId(req, resp) ?: return
        val userId = resolveUserId(req, resp) ?: return

        val progress = PlaybackProgressService.getProgressForUser(userId, transcodeId)
        if (progress == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }

        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        val result = linkedMapOf<String, Any?>(
            "position" to progress.position_seconds,
            "duration" to progress.duration_seconds
        )
        mapper.writeValue(resp.writer, result)
    }

    override fun doDelete(req: HttpServletRequest, resp: HttpServletResponse) {
        val transcodeId = parseTranscodeId(req, resp) ?: return
        val userId = resolveUserId(req, resp) ?: return

        val progress = PlaybackProgressService.getProgressForUser(userId, transcodeId)
        if (progress != null) {
            progress.delete()
        }
        resp.status = HttpServletResponse.SC_NO_CONTENT
    }

    private fun parseTranscodeId(req: HttpServletRequest, resp: HttpServletResponse): Long? {
        val pathInfo = req.pathInfo?.removePrefix("/") ?: ""
        val transcodeId = pathInfo.toLongOrNull()
        if (transcodeId == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid transcode ID")
        }
        return transcodeId
    }

    /**
     * Resolves the acting user from the AuthFilter-set USER_ATTRIBUTE.
     * AuthFilter handles both cookie auth and API key fallback.
     * Roku synthetic users (no DB ID) map to the Roku system user for progress tracking.
     */
    private fun resolveUserId(req: HttpServletRequest, resp: HttpServletResponse): Long? {
        val user = req.getAttribute(AuthFilter.USER_ATTRIBUTE) as? AppUser
        if (user == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required")
            return null
        }
        // Roku synthetic user (created by AuthFilter for API key auth) has no DB ID
        if (user.id == null) {
            return PlaybackProgressService.getOrCreateRokuUser().id
        }
        return user.id
    }
}
