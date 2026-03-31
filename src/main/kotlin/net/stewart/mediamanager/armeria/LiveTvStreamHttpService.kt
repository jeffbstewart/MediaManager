package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.service.LiveTvStreamManager
import net.stewart.mediamanager.service.MetricsRegistry
import org.slf4j.LoggerFactory

/**
 * Armeria port of [net.stewart.mediamanager.LiveTvStreamServlet].
 *
 * Serves live TV HLS streams backed by FFmpeg transcoding from HDHomeRun.
 * All endpoints use @Blocking since they involve DB lookups and may block
 * up to 30s waiting for FFmpeg to produce the first playlist.
 */
@Blocking
class LiveTvStreamHttpService {

    private val log = LoggerFactory.getLogger(LiveTvStreamHttpService::class.java)

    @Get("/live-tv-stream/{channelId}/stream.m3u8")
    fun masterPlaylist(ctx: ServiceRequestContext, @Param("channelId") channelId: Long): HttpResponse {
        val (channel, errorResponse) = validateChannel(channelId, ctx)
        if (channel == null) return errorResponse!!

        val baseUrl = getBaseUrl(ctx)
        val keyParam = keyParam(ctx)
        val variantUrl = "$baseUrl/live-tv-stream/${channel.id}/hls/live.m3u8$keyParam"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000\n$variantUrl\n"

        MetricsRegistry.countHttpResponse("live-tv", 200)
        return hlsResponse(master)
    }

    @Get("/live-tv-stream/{channelId}/hls/live.m3u8")
    fun playlist(ctx: ServiceRequestContext, @Param("channelId") channelId: Long): HttpResponse {
        val (channel, errorResponse) = validateChannel(channelId, ctx)
        if (channel == null) return errorResponse!!

        val user = ArmeriaAuthDecorator.getUser(ctx)!!

        // getOrCreateStream handles channel switching internally.
        // Do NOT stop the stream here — HLS clients re-poll every few seconds.
        val (stream, error) = LiveTvStreamManager.getOrCreateStream(channel, user.id!!)
        if (stream == null) {
            MetricsRegistry.countHttpResponse("live-tv", 503)
            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE)
        }

        // Poll up to 30 seconds for playlist file to appear.
        // FFmpeg must: connect → receive MPEG-TS → decode → re-encode → write first segment.
        val playlistFile = stream.getPlaylistFile()
        var waited = 0
        while (!playlistFile.exists() && waited < 30000 && stream.isHealthy()) {
            Thread.sleep(500)
            waited += 500
        }

        if (!playlistFile.exists()) {
            val status = if (!stream.isHealthy()) HttpStatus.BAD_GATEWAY else HttpStatus.GATEWAY_TIMEOUT
            MetricsRegistry.countHttpResponse("live-tv", status.code())
            return HttpResponse.of(status)
        }

        // Rewrite segment URLs. Roku AVPlayer needs absolute URLs so cookies are
        // sent on segment requests. Browser clients (hls.js) need relative URLs to
        // avoid CORS issues when the playlist host differs from the page origin.
        val keyParam = keyParam(ctx)
        val useAbsoluteUrls = keyParam.isNotEmpty() // Device token = Roku; no token = browser
        val baseUrl = if (useAbsoluteUrls) getBaseUrl(ctx) else ""
        val content = playlistFile.readText()
        val rewritten = content.lines().joinToString("\n") { line ->
            if (line.endsWith(".ts")) {
                val segName = line.trim()
                if (useAbsoluteUrls) {
                    "$baseUrl/live-tv-stream/${channel.id}/segment/$segName$keyParam"
                } else {
                    "../segment/$segName"
                }
            } else {
                line
            }
        }

        stream.touch()
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
        MetricsRegistry.countLiveTvStreamBytes("playlist", bytes.size.toLong())
        MetricsRegistry.countHttpResponse("live-tv", 200)
        return hlsResponse(rewritten)
    }

    @Get("/live-tv-stream/{channelId}/segment/{segmentName}")
    fun segment(ctx: ServiceRequestContext, @Param("channelId") channelId: Long,
                @Param("segmentName") segmentName: String): HttpResponse {
        // Validate segment filename to prevent path traversal
        if (!LiveTvStreamManager.isValidSegmentName(segmentName)) {
            MetricsRegistry.countHttpResponse("live-tv", 400)
            return HttpResponse.of(HttpStatus.BAD_REQUEST)
        }

        val stream = LiveTvStreamManager.getStream(channelId)
        if (stream == null) {
            MetricsRegistry.countHttpResponse("live-tv", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val segmentFile = stream.getSegmentFile(segmentName)
        if (!segmentFile.exists()) {
            MetricsRegistry.countHttpResponse("live-tv", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        stream.touch()
        val bytes = segmentFile.readBytes()
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.parse("video/mp2t"))
            .contentLength(bytes.size.toLong())
            .add("Cache-Control", "no-cache")
            .build()
        MetricsRegistry.countLiveTvStreamBytes("segment", bytes.size.toLong())
        MetricsRegistry.countHttpResponse("live-tv", 200)
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    /**
     * Validates the channel: auth check, content rating gate, channel/tuner enabled.
     * Returns the channel on success, or null + error response.
     */
    private fun validateChannel(channelId: Long, ctx: ServiceRequestContext): Pair<LiveTvChannel?, HttpResponse?> {
        val user = ArmeriaAuthDecorator.getUser(ctx)
        if (user == null) {
            MetricsRegistry.countHttpResponse("live-tv", 401)
            return null to HttpResponse.of(HttpStatus.UNAUTHORIZED)
        }

        if (!canAccessLiveTv(user)) {
            MetricsRegistry.countHttpResponse("live-tv", 403)
            return null to HttpResponse.of(HttpStatus.FORBIDDEN)
        }

        val channel = LiveTvChannel.findById(channelId)
        if (channel == null || !channel.enabled) {
            MetricsRegistry.countHttpResponse("live-tv", 404)
            return null to HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val tuner = LiveTvTuner.findById(channel.tuner_id)
        if (tuner == null || !tuner.enabled) {
            MetricsRegistry.countHttpResponse("live-tv", 404)
            return null to HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        return channel to null
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

    companion object {
        /** Checks if a user has sufficient content rating to access live TV. */
        fun canAccessLiveTv(user: AppUser): Boolean {
            if (user.isAdmin()) return true
            val ceiling = user.ratingCeilingValue ?: return true
            val minLevel = AppConfig.findAll()
                .firstOrNull { it.config_key == "live_tv_min_rating" }
                ?.config_val?.toIntOrNull() ?: 4
            return ceiling.ordinalLevel >= minLevel
        }
    }
}
