package net.stewart.mediamanager

import com.github.vokorm.findAll
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.service.LiveTvStreamManager
import net.stewart.mediamanager.service.MetricsRegistry
import org.slf4j.LoggerFactory

@WebServlet(urlPatterns = ["/live-tv/*"])
class LiveTvStreamServlet : HttpServlet() {
    private val log = LoggerFactory.getLogger(LiveTvStreamServlet::class.java)

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        try {
            val pathInfo = req.pathInfo?.removePrefix("/") ?: ""
            val parts = pathInfo.split("/", limit = 3)

            if (parts.isEmpty() || parts[0].isBlank()) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing channel ID")
                MetricsRegistry.countHttpResponse("live-tv", 400)
                return
            }

            val channelId = parts[0].toLongOrNull()
            if (channelId == null) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid channel ID")
                MetricsRegistry.countHttpResponse("live-tv", 400)
                return
            }

            // Auth check
            val user = req.getAttribute(AuthFilter.USER_ATTRIBUTE) as? AppUser
            if (user == null) {
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                MetricsRegistry.countHttpResponse("live-tv", 401)
                return
            }

            // Content rating gate
            if (!canAccessLiveTv(user)) {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Live TV access restricted")
                MetricsRegistry.countHttpResponse("live-tv", 403)
                return
            }

            // Look up channel
            val channel = LiveTvChannel.findById(channelId)
            if (channel == null || !channel.enabled) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Channel not found")
                MetricsRegistry.countHttpResponse("live-tv", 404)
                return
            }

            // Check tuner is enabled
            val tuner = LiveTvTuner.findById(channel.tuner_id)
            if (tuner == null || !tuner.enabled) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Tuner not available")
                MetricsRegistry.countHttpResponse("live-tv", 404)
                return
            }

            // Route based on path
            val action = if (parts.size >= 2) parts[1] else ""
            when (action) {
                "stream.m3u8" -> handlePlaylist(req, resp, channel, user)
                "segment" -> {
                    val segmentName = if (parts.size >= 3) parts[2] else ""
                    handleSegment(resp, channel, segmentName)
                }
                else -> {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                    MetricsRegistry.countHttpResponse("live-tv", 404)
                }
            }
        } catch (e: Exception) {
            log.error("Live TV stream error: {}", e.message)
            if (!resp.isCommitted) {
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            }
            MetricsRegistry.countHttpResponse("live-tv", 500)
        }
    }

    private fun handlePlaylist(req: HttpServletRequest, resp: HttpServletResponse, channel: LiveTvChannel, user: AppUser) {
        // Stop any existing stream for this user (channel switch)
        LiveTvStreamManager.stopUserStream(user.id!!)

        val (stream, error) = LiveTvStreamManager.getOrCreateStream(channel, user.id!!)
        if (stream == null) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, error ?: "Cannot start stream")
            MetricsRegistry.countHttpResponse("live-tv", 503)
            return
        }

        // Poll up to 10 seconds for playlist file to appear (FFmpeg startup delay)
        val playlistFile = stream.getPlaylistFile()
        var waited = 0
        while (!playlistFile.exists() && waited < 10000 && stream.isHealthy()) {
            Thread.sleep(500)
            waited += 500
        }

        if (!playlistFile.exists()) {
            if (!stream.isHealthy()) {
                resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "FFmpeg process exited")
            } else {
                resp.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, "Stream startup timeout")
            }
            MetricsRegistry.countHttpResponse("live-tv", if (!stream.isHealthy()) 502 else 504)
            return
        }

        // Read playlist and rewrite segment URLs to absolute paths
        val apiKey = req.getParameter("key")
        val keyParam = if (apiKey != null) "?key=$apiKey" else ""
        val content = playlistFile.readText()
        val rewritten = content.lines().joinToString("\n") { line ->
            if (line.endsWith(".ts")) {
                val segName = line.trim()
                "/live-tv/${channel.id}/segment/$segName$keyParam"
            } else {
                line
            }
        }

        stream.touch()
        resp.contentType = "application/vnd.apple.mpegurl"
        resp.setHeader("Cache-Control", "no-cache")
        val bytes = rewritten.toByteArray()
        resp.outputStream.write(bytes)
        MetricsRegistry.countLiveTvStreamBytes("playlist", bytes.size.toLong())
        MetricsRegistry.countHttpResponse("live-tv", 200)
    }

    private fun handleSegment(resp: HttpServletResponse, channel: LiveTvChannel, segmentName: String) {
        // Validate segment filename to prevent path traversal
        if (!LiveTvStreamManager.isValidSegmentName(segmentName)) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid segment name")
            MetricsRegistry.countHttpResponse("live-tv", 400)
            return
        }

        val stream = LiveTvStreamManager.getStream(channel.id!!)
        if (stream == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No active stream")
            MetricsRegistry.countHttpResponse("live-tv", 404)
            return
        }

        val segmentFile = stream.getSegmentFile(segmentName)
        if (!segmentFile.exists()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Segment not found")
            MetricsRegistry.countHttpResponse("live-tv", 404)
            return
        }

        stream.touch()
        resp.contentType = "video/mp2t"
        resp.setHeader("Cache-Control", "no-cache")
        val bytes = segmentFile.readBytes()
        resp.outputStream.write(bytes)
        MetricsRegistry.countLiveTvStreamBytes("segment", bytes.size.toLong())
        MetricsRegistry.countHttpResponse("live-tv", 200)
    }

    companion object {
        /**
         * Checks whether a user can access live TV based on content rating configuration.
         */
        fun canAccessLiveTv(user: AppUser): Boolean {
            if (user.isAdmin()) return true
            val ceiling = user.rating_ceiling ?: return true // unrestricted
            val minLevel = AppConfig.findAll()
                .firstOrNull { it.config_key == "live_tv_min_rating" }
                ?.config_val?.toIntOrNull() ?: 4
            return ceiling >= minLevel
        }
    }
}
