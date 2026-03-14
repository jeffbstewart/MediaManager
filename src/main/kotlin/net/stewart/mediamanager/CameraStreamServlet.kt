package net.stewart.mediamanager

import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.service.Go2rtcAgent
import net.stewart.mediamanager.service.HlsRelayManager
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.UriCredentialRedactor
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI

/**
 * Proxies camera streams from go2rtc to authenticated clients.
 *
 * Routes:
 * - GET /cam/{id}/stream.m3u8       -> HLS playlist from relay ring buffer
 * - GET /cam/{id}/hls/segment/{n}   -> HLS segment from relay ring buffer
 * - GET /cam/{id}/snapshot.jpg      -> JPEG snapshot (proxied from go2rtc)
 * - GET /cam/{id}/mjpeg             -> MJPEG stream (proxied from go2rtc)
 *
 * HLS streaming uses a server-side relay (HlsCameraRelay) that maintains a persistent
 * polling loop to go2rtc, caching segments in a ring buffer. This decouples clients
 * from go2rtc's ephemeral HLS sessions which expire quickly.
 *
 * Authentication is handled by [AuthFilter] which covers `/cam/`.
 */
@WebServlet(urlPatterns = ["/cam/*"])
class CameraStreamServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(CameraStreamServlet::class.java)

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val pathInfo = req.pathInfo?.removePrefix("/") ?: ""
        val parts = pathInfo.split("/", limit = 2)

        if (parts.isEmpty() || parts[0].isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing camera ID")
            MetricsRegistry.countHttpResponse("camera-stream", 400)
            return
        }

        val cameraId = parts[0].toLongOrNull()
        if (cameraId == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid camera ID")
            MetricsRegistry.countHttpResponse("camera-stream", 400)
            return
        }

        val camera = Camera.findById(cameraId)
        if (camera == null || !camera.enabled) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Camera not found")
            MetricsRegistry.countHttpResponse("camera-stream", 404)
            return
        }

        val subPath = if (parts.size > 1) parts[1] else ""

        try {
            when {
                subPath == "stream.m3u8" -> serveRelayPlaylist(camera, req, resp)
                subPath.startsWith("hls/segment/") -> serveRelaySegment(camera, subPath, resp)
                subPath == "snapshot.jpg" -> proxySnapshot(camera, resp)
                subPath == "mjpeg" -> proxyMjpeg(camera, resp)
                else -> {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                    MetricsRegistry.countHttpResponse("camera-stream", 404)
                }
            }
        } catch (e: Exception) {
            log.error("Camera stream proxy error for camera '{}': {}", camera.name,
                UriCredentialRedactor.redactAll(e.message ?: ""))
            if (!resp.isCommitted) {
                resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Stream proxy error")
            }
            MetricsRegistry.countHttpResponse("camera-stream", 502)
        }
    }

    /**
     * Serve an HLS playlist generated from the relay's ring buffer.
     * Creates a relay on-demand if one doesn't exist for this camera.
     */
    private fun serveRelayPlaylist(camera: Camera, req: HttpServletRequest, resp: HttpServletResponse) {
        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val relay = HlsRelayManager.getOrCreateRelay(camera.id!!, camera.go2rtc_name, apiPort)

        if (relay == null) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Too many active camera streams")
            MetricsRegistry.countHttpResponse("camera-stream", 503)
            return
        }

        val baseUrl = getBaseUrl(req)
        val key = keyParam(req)

        // The relay may not have segments yet if it just started. Wait briefly.
        var playlist: String? = null
        for (attempt in 1..10) {
            playlist = relay.generatePlaylist(baseUrl, key)
            if (playlist != null) break
            Thread.sleep(500)
        }

        if (playlist == null) {
            log.warn("HLS relay for camera '{}' has no segments after waiting", camera.name)
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Stream not ready")
            MetricsRegistry.countHttpResponse("camera-stream", 503)
            return
        }

        MetricsRegistry.hlsStreamStarted()
        try {
            resp.contentType = "application/vnd.apple.mpegurl"
            resp.characterEncoding = "UTF-8"
            resp.setHeader("Cache-Control", "no-cache")
            resp.writer.write(playlist)
            MetricsRegistry.countCameraStreamBytes("hls", playlist.length.toLong())
            MetricsRegistry.countHttpResponse("camera-stream", 200)
        } finally {
            MetricsRegistry.hlsStreamStopped()
        }
    }

    /**
     * Serve a segment from the relay's ring buffer by its sequence number.
     * URL format: /cam/{id}/hls/segment/{n}
     */
    private fun serveRelaySegment(camera: Camera, subPath: String, resp: HttpServletResponse) {
        // Parse sequence number from "hls/segment/{n}"
        val seqStr = subPath.removePrefix("hls/segment/")
        val seqNum = seqStr.toLongOrNull()
        if (seqNum == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid segment number")
            MetricsRegistry.countHttpResponse("camera-stream", 400)
            return
        }

        val relay = HlsRelayManager.getRelay(camera.id!!)
        if (relay == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No active relay for this camera")
            MetricsRegistry.countHttpResponse("camera-stream", 404)
            return
        }

        val data = relay.getSegment(seqNum)
        if (data == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Segment evicted or not yet available")
            MetricsRegistry.countHttpResponse("camera-stream", 404)
            return
        }

        resp.contentType = "video/mp2t"
        resp.setContentLengthLong(data.size.toLong())
        resp.setHeader("Cache-Control", "no-cache")
        resp.outputStream.write(data)
        MetricsRegistry.countCameraStreamBytes("hls", data.size.toLong())
        MetricsRegistry.countHttpResponse("camera-stream", 200)
    }

    private fun proxySnapshot(camera: Camera, resp: HttpServletResponse) {
        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val go2rtcUrl = "http://127.0.0.1:$apiPort/api/frame.jpeg?src=${camera.go2rtc_name}"

        val conn = openGo2rtcConnection(go2rtcUrl) ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "go2rtc not available")
            MetricsRegistry.countHttpResponse("camera-stream", 502)
            return
        }

        try {
            resp.contentType = "image/jpeg"
            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull()
            contentLength?.let { resp.setContentLengthLong(it) }
            resp.setHeader("Cache-Control", "no-cache")
            conn.inputStream.copyTo(resp.outputStream)
            if (contentLength != null) MetricsRegistry.countCameraStreamBytes("snapshot", contentLength)
            MetricsRegistry.countHttpResponse("camera-stream", 200)
        } finally {
            conn.disconnect()
        }
    }

    private fun proxyMjpeg(camera: Camera, resp: HttpServletResponse) {
        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val go2rtcUrl = "http://127.0.0.1:$apiPort/api/stream.mjpeg?src=${camera.go2rtc_name}"

        val conn = openGo2rtcConnection(go2rtcUrl) ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "go2rtc not available")
            MetricsRegistry.countHttpResponse("camera-stream", 502)
            return
        }

        MetricsRegistry.mjpegStreamStarted()
        try {
            // Pass through the multipart content type from go2rtc
            resp.contentType = conn.contentType ?: "multipart/x-mixed-replace; boundary=frame"
            resp.setHeader("Cache-Control", "no-cache")
            resp.setHeader("Connection", "keep-alive")

            // Stream continuously until client disconnects
            val input = conn.inputStream
            val output = resp.outputStream
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytes = 0L
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
                totalBytes += bytesRead
                if (totalBytes >= 1_048_576) {
                    MetricsRegistry.countCameraStreamBytes("mjpeg", totalBytes)
                    totalBytes = 0
                }
            }
            if (totalBytes > 0) MetricsRegistry.countCameraStreamBytes("mjpeg", totalBytes)
            MetricsRegistry.countHttpResponse("camera-stream", 200)
        } catch (_: java.io.IOException) {
            // Client disconnected -- normal for MJPEG streams
        } finally {
            MetricsRegistry.mjpegStreamStopped()
            conn.disconnect()
        }
    }

    private fun openGo2rtcConnection(url: String): HttpURLConnection? {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) {
                log.warn("go2rtc returned {} for {}", conn.responseCode, UriCredentialRedactor.redactAll(url))
                conn.disconnect()
                return null
            }
            conn
        } catch (e: Exception) {
            log.warn("Cannot connect to go2rtc: {}", UriCredentialRedactor.redactAll(e.message ?: ""))
            null
        }
    }

    /** Returns "?key=xxx" suffix if the request has a key parameter, for HLS URL rewriting. */
    private fun keyParam(req: HttpServletRequest): String {
        val key = req.getParameter("key") ?: return ""
        return "?key=$key"
    }

    /** Build the external base URL from the request or configured roku_base_url. */
    private fun getBaseUrl(req: HttpServletRequest): String {
        val configured = net.stewart.mediamanager.entity.AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val
        if (!configured.isNullOrBlank()) return configured.trimEnd('/')
        // Use X-Forwarded headers if behind a reverse proxy
        val proto = req.getHeader("X-Forwarded-Proto") ?: req.scheme
        val host = req.getHeader("X-Forwarded-Host") ?: "${req.serverName}:${req.serverPort}"
        return "$proto://$host"
    }
}
