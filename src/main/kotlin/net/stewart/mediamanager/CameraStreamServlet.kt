package net.stewart.mediamanager

import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.service.Go2rtcAgent
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.UriCredentialRedactor
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI

/**
 * Proxies camera streams from go2rtc to authenticated clients.
 *
 * Routes:
 * - GET /cam/{id}/stream.m3u8  → HLS playlist (for Roku)
 * - GET /cam/{id}/segment/{file} → HLS .ts segments
 * - GET /cam/{id}/snapshot.jpg  → JPEG snapshot
 * - GET /cam/{id}/mjpeg        → MJPEG stream (for browser grid)
 *
 * Authentication is handled by [AuthFilter] which covers `/cam/`.
 */
@WebServlet(urlPatterns = ["/cam/*"])
class CameraStreamServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(CameraStreamServlet::class.java)

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val pathInfo = req.pathInfo?.removePrefix("/") ?: ""
        val parts = pathInfo.split("/", limit = 3)

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

        val subPath = if (parts.size > 1) parts.subList(1, parts.size).joinToString("/") else ""

        try {
            when {
                subPath == "stream.m3u8" -> proxyHlsPlaylist(camera, req, resp)
                subPath.startsWith("segment/") -> proxyHlsSegment(camera, subPath.removePrefix("segment/"), resp)
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

    private fun proxyHlsPlaylist(camera: Camera, req: HttpServletRequest, resp: HttpServletResponse) {
        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val go2rtcUrl = "http://127.0.0.1:$apiPort/api/stream.m3u8?src=${camera.go2rtc_name}"

        val conn = openGo2rtcConnection(go2rtcUrl) ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "go2rtc not available")
            MetricsRegistry.countHttpResponse("camera-stream", 502)
            return
        }

        MetricsRegistry.hlsStreamStarted()
        try {
            val body = conn.inputStream.bufferedReader().readText()

            // Rewrite HLS segment URLs to route through our proxy
            val rewritten = body.replace(Regex("""(/api/stream\.ts\?[^\s]*)""")) { match ->
                val originalUrl = match.value
                // Extract the segment params and route through our servlet
                val segFile = java.net.URLEncoder.encode(originalUrl, "UTF-8")
                "/cam/${camera.id}/segment/$segFile${keyParam(req)}"
            }

            resp.contentType = "application/vnd.apple.mpegurl"
            resp.characterEncoding = "UTF-8"
            resp.setHeader("Cache-Control", "no-cache")
            resp.writer.write(rewritten)
            MetricsRegistry.countCameraStreamBytes("hls", rewritten.length.toLong())
            MetricsRegistry.countHttpResponse("camera-stream", 200)
        } finally {
            MetricsRegistry.hlsStreamStopped()
            conn.disconnect()
        }
    }

    private fun proxyHlsSegment(camera: Camera, segmentFile: String, resp: HttpServletResponse) {
        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val decodedPath = java.net.URLDecoder.decode(segmentFile, "UTF-8")
        val go2rtcUrl = "http://127.0.0.1:$apiPort$decodedPath"

        val conn = openGo2rtcConnection(go2rtcUrl) ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "go2rtc not available")
            MetricsRegistry.countHttpResponse("camera-stream", 502)
            return
        }

        try {
            resp.contentType = conn.contentType ?: "video/mp2t"
            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull()
            contentLength?.let { resp.setContentLengthLong(it) }
            resp.setHeader("Cache-Control", "no-cache")
            conn.inputStream.copyTo(resp.outputStream)
            if (contentLength != null) MetricsRegistry.countCameraStreamBytes("hls", contentLength)
            MetricsRegistry.countHttpResponse("camera-stream", 200)
        } finally {
            conn.disconnect()
        }
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
                // Report bytes in chunks to avoid per-byte counter overhead
                if (totalBytes >= 1_048_576) { // every ~1 MB
                    MetricsRegistry.countCameraStreamBytes("mjpeg", totalBytes)
                    totalBytes = 0
                }
            }
            if (totalBytes > 0) MetricsRegistry.countCameraStreamBytes("mjpeg", totalBytes)
            MetricsRegistry.countHttpResponse("camera-stream", 200)
        } catch (_: java.io.IOException) {
            // Client disconnected — normal for MJPEG streams
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

    /** Returns "?key=xxx" suffix if the request has a key parameter, for HLS segment URL rewriting. */
    private fun keyParam(req: HttpServletRequest): String {
        val key = req.getParameter("key") ?: return ""
        return "?key=$key"
    }
}
