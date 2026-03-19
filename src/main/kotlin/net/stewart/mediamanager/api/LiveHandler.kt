package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.LiveTvStreamServlet
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.service.MetricsRegistry

/**
 * Handles GET /api/v1/live/... endpoints:
 * - /live/cameras — list enabled cameras with stream URLs (no RTSP credentials)
 * - /live/tv/channels — list enabled channels filtered by user's quality preference
 *
 * Camera and live TV streams are served by existing servlets (CameraStreamServlet,
 * LiveTvStreamServlet) which are protected by AuthFilter with JWT support.
 * This handler only provides metadata and stream URLs — actual streams use
 * /cam/{id}/... and /live-tv-stream/{id}/... paths with Bearer auth.
 */
object LiveHandler {

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        if (req.method != "GET") {
            ApiV1Servlet.sendError(resp, 405, "method_not_allowed")
            MetricsRegistry.countHttpResponse("api_v1", 405)
            return
        }

        when (path) {
            "cameras" -> handleCameras(resp, mapper)
            "tv/channels" -> handleTvChannels(req, resp, mapper, user)
            else -> {
                ApiV1Servlet.sendError(resp, 404, "not_found")
                MetricsRegistry.countHttpResponse("api_v1", 404)
            }
        }
    }

    private fun handleCameras(resp: HttpServletResponse, mapper: ObjectMapper) {
        val cameras = Camera.findAll()
            .filter { it.enabled }
            .sortedBy { it.display_order }

        val items = cameras.map { cam ->
            mapOf(
                "id" to cam.id,
                "name" to cam.name,
                // Stream URLs — iOS client adds Bearer auth header
                "hls_url" to "/cam/${cam.id}/stream.m3u8",
                "snapshot_url" to "/cam/${cam.id}/snapshot.jpg"
                // RTSP URL intentionally omitted — contains credentials (UriCredentialRedactor)
            )
        }

        ApiV1Servlet.sendJson(resp, 200, mapOf("cameras" to items), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleTvChannels(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, user: AppUser) {
        // Content rating gate — same check as LiveTvStreamServlet
        if (!LiveTvStreamServlet.canAccessLiveTv(user)) {
            ApiV1Servlet.sendJson(resp, 200, mapOf("channels" to emptyList<Any>()), mapper)
            MetricsRegistry.countHttpResponse("api_v1", 200)
            return
        }

        val userMinQuality = user.live_tv_min_quality
        val enabledTunerIds = LiveTvTuner.findAll()
            .filter { it.enabled }
            .mapNotNull { it.id }
            .toSet()

        val channels = LiveTvChannel.findAll()
            .filter { it.enabled && it.tuner_id in enabledTunerIds }
            .filter { it.reception_quality >= userMinQuality }
            .sortedWith(compareBy({ it.display_order }, { it.guide_number.toDoubleOrNull() ?: 9999.0 }))

        val items = channels.map { ch ->
            mapOf(
                "id" to ch.id,
                "guide_number" to ch.guide_number,
                "guide_name" to ch.guide_name,
                "network_affiliation" to ch.network_affiliation,
                "reception_quality" to ch.reception_quality,
                // Stream URL — iOS client adds Bearer auth header
                "hls_url" to "/live-tv-stream/${ch.id}/stream.m3u8"
                // HDHomeRun stream_url intentionally omitted — internal network address
            )
        }

        ApiV1Servlet.sendJson(resp, 200, mapOf("channels" to items), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
