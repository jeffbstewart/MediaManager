package net.stewart.mediamanager.armeria

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
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.service.Go2rtcAgent
import net.stewart.mediamanager.service.HlsRelayManager
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.UriCredentialRedactor
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI

/**
 * Armeria port of [net.stewart.mediamanager.CameraStreamServlet].
 *
 * Proxies camera streams from go2rtc to authenticated clients.
 * HLS streaming uses server-side relay ring buffers. MJPEG and snapshots
 * are proxied directly from go2rtc.
 */
class CameraStreamHttpService {

    private val log = LoggerFactory.getLogger(CameraStreamHttpService::class.java)

    @Blocking
    @Get("/cam/{id}/start")
    fun startRelay(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val camera = findEnabledCamera(id) ?: return cameraNotFound()

        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val relay = HlsRelayManager.getOrCreateRelay(camera.id!!, camera.go2rtc_name, apiPort)
        if (relay == null) {
            MetricsRegistry.countHttpResponse("camera-stream", 503)
            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE)
        }

        // Block until relay has enough segments (up to 30 seconds)
        val minSegments = 10
        for (attempt in 1..120) {
            relay.touch()
            val count = relay.segmentCount()
            if (count >= minSegments) {
                log.info("Camera '{}' relay ready: {} segments", camera.name, count)
                MetricsRegistry.countHttpResponse("camera-stream", 200)
                return jsonResponse("""{"ready":true,"segments":$count}""")
            }
            Thread.sleep(250)
        }

        log.warn("Camera '{}' relay not ready after 30s", camera.name)
        MetricsRegistry.countHttpResponse("camera-stream", 504)
        return jsonResponse("""{"ready":false,"error":"Stream not ready after 30 seconds"}""",
            HttpStatus.GATEWAY_TIMEOUT)
    }

    @Blocking
    @Get("/cam/{id}/stream.m3u8")
    fun masterPlaylist(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val camera = findEnabledCamera(id) ?: return cameraNotFound()

        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val relay = HlsRelayManager.getOrCreateRelay(camera.id!!, camera.go2rtc_name, apiPort)
        if (relay == null) {
            MetricsRegistry.countHttpResponse("camera-stream", 503)
            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE)
        }

        val baseUrl = getBaseUrl(ctx)
        val key = keyParam(ctx)
        val variantUrl = "$baseUrl/cam/${camera.id}/hls/live.m3u8${if (key.isNotEmpty()) key else ""}"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\n$variantUrl\n"

        MetricsRegistry.countHttpResponse("camera-stream", 200)
        return hlsResponse(master)
    }

    @Blocking
    @Get("/cam/{id}/hls/live.m3u8")
    fun variantPlaylist(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val camera = findEnabledCamera(id) ?: return cameraNotFound()

        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val relay = HlsRelayManager.getOrCreateRelay(camera.id!!, camera.go2rtc_name, apiPort)
        if (relay == null) {
            MetricsRegistry.countHttpResponse("camera-stream", 503)
            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE)
        }

        val baseUrl = getBaseUrl(ctx)
        val key = keyParam(ctx)

        // Short wait — the /start endpoint handles the long warmup
        var playlist: String? = null
        for (attempt in 1..8) {
            playlist = relay.generatePlaylist(baseUrl, key)
            if (playlist != null) break
            Thread.sleep(250)
        }

        if (playlist == null) {
            log.warn("HLS relay for camera '{}' has insufficient segments", camera.name)
            MetricsRegistry.countHttpResponse("camera-stream", 503)
            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE)
        }

        MetricsRegistry.hlsStreamStarted()
        try {
            log.info("Served HLS playlist for camera '{}': {} bytes", camera.name, playlist.length)
            MetricsRegistry.countCameraStreamBytes("hls", playlist.length.toLong())
            MetricsRegistry.countHttpResponse("camera-stream", 200)
            return hlsResponse(playlist)
        } finally {
            MetricsRegistry.hlsStreamStopped()
        }
    }

    @Blocking
    @Get("/cam/{id}/hls/segment/{n}")
    fun segment(ctx: ServiceRequestContext, @Param("id") id: Long, @Param("n") n: Long): HttpResponse {
        val camera = findEnabledCamera(id) ?: return cameraNotFound()

        val relay = HlsRelayManager.getRelay(camera.id!!)
        if (relay == null) {
            MetricsRegistry.countHttpResponse("camera-stream", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val data = relay.getSegment(n)
        if (data == null) {
            MetricsRegistry.countHttpResponse("camera-stream", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.parse("video/mp2t"))
            .contentLength(data.size.toLong())
            .add("Cache-Control", "no-cache")
            .build()
        MetricsRegistry.countCameraStreamBytes("hls", data.size.toLong())
        MetricsRegistry.countHttpResponse("camera-stream", 200)
        return HttpResponse.of(headers, HttpData.wrap(data))
    }

    @Blocking
    @Get("/cam/{id}/snapshot.jpg")
    fun snapshot(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val camera = findEnabledCamera(id) ?: return cameraNotFound()

        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val go2rtcUrl = "http://127.0.0.1:$apiPort/api/frame.jpeg?src=${camera.go2rtc_name}"

        val conn = openGo2rtcConnection(go2rtcUrl) ?: run {
            MetricsRegistry.countHttpResponse("camera-stream", 502)
            return HttpResponse.of(HttpStatus.BAD_GATEWAY)
        }

        try {
            val bytes = conn.inputStream.readBytes()
            val headers = ResponseHeaders.builder(HttpStatus.OK)
                .contentType(MediaType.JPEG)
                .contentLength(bytes.size.toLong())
                .add("Cache-Control", "no-cache")
                .build()
            MetricsRegistry.countCameraStreamBytes("snapshot", bytes.size.toLong())
            MetricsRegistry.countHttpResponse("camera-stream", 200)
            return HttpResponse.of(headers, HttpData.wrap(bytes))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * MJPEG stream proxy. Uses a non-blocking handler with a streaming response
     * since the stream runs until the client disconnects.
     */
    @Get("/cam/{id}/mjpeg")
    fun mjpeg(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val writer = HttpResponse.streaming()
        ctx.blockingTaskExecutor().execute {
            doMjpeg(id, writer)
        }
        return writer
    }

    private fun doMjpeg(id: Long, writer: HttpResponseWriter) {
        val camera = findEnabledCamera(id)
        if (camera == null) {
            writer.write(ResponseHeaders.of(HttpStatus.NOT_FOUND))
            MetricsRegistry.countHttpResponse("camera-stream", 404)
            writer.close()
            return
        }

        val apiPort = Go2rtcAgent.instance?.apiPort ?: 1984
        val go2rtcUrl = "http://127.0.0.1:$apiPort/api/stream.mjpeg?src=${camera.go2rtc_name}"

        val conn = openGo2rtcConnection(go2rtcUrl)
        if (conn == null) {
            writer.write(ResponseHeaders.of(HttpStatus.BAD_GATEWAY))
            MetricsRegistry.countHttpResponse("camera-stream", 502)
            writer.close()
            return
        }

        MetricsRegistry.mjpegStreamStarted()
        try {
            val contentType = conn.contentType ?: "multipart/x-mixed-replace; boundary=frame"
            val headers = ResponseHeaders.builder(HttpStatus.OK)
                .contentType(MediaType.parse(contentType))
                .add("Cache-Control", "no-cache")
                .add("Connection", "keep-alive")
                .build()
            writer.write(headers)

            val input = conn.inputStream
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytes = 0L
            while (input.read(buffer).also { bytesRead = it } != -1) {
                writer.whenConsumed().join()
                if (!writer.tryWrite(HttpData.copyOf(buffer, 0, bytesRead))) {
                    break // Client disconnected
                }
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
            writer.close()
        }
    }

    private fun findEnabledCamera(id: Long): Camera? {
        val camera = Camera.findById(id)
        return if (camera != null && camera.enabled) camera else null
    }

    private fun cameraNotFound(): HttpResponse {
        MetricsRegistry.countHttpResponse("camera-stream", 404)
        return HttpResponse.of(HttpStatus.NOT_FOUND)
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

    private fun keyParam(ctx: ServiceRequestContext): String {
        val key = ctx.queryParams().get("key") ?: return ""
        return "?key=$key"
    }

    private fun getBaseUrl(ctx: ServiceRequestContext): String {
        val configured = AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val
        if (!configured.isNullOrBlank()) return configured.trimEnd('/')
        val headers = ctx.request().headers()
        val proto = headers.get("x-forwarded-proto") ?: ctx.sessionProtocol().uriText()
        val host = headers.get("x-forwarded-host") ?: headers.get("host") ?: "localhost"
        return "$proto://$host"
    }

    private fun hlsResponse(body: String): HttpResponse {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.parse("application/vnd.apple.mpegurl; charset=utf-8"))
            .contentLength(bytes.size.toLong())
            .add("Cache-Control", "no-cache")
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun jsonResponse(body: String, status: HttpStatus = HttpStatus.OK): HttpResponse {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(status)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
