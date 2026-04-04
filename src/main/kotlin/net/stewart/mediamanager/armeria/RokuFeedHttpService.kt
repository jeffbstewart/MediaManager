package net.stewart.mediamanager.armeria

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.github.vokorm.findAll
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.service.AuthService
// LiveTvStreamServlet removed — using LiveTvStreamHttpService.canAccessLiveTv()
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.PairingService
import net.stewart.mediamanager.service.RokuFeedService
import net.stewart.mediamanager.service.RokuHomeService
import net.stewart.mediamanager.service.RokuSearchService
import net.stewart.mediamanager.service.RokuTitleService
import org.slf4j.LoggerFactory

/**
 * Armeria port of [net.stewart.mediamanager.RokuFeedServlet].
 *
 * Uses its own auth logic (device token first, cookie fallback) rather than
 * [ArmeriaAuthDecorator], because Roku endpoints were never behind AuthFilter.
 */
class RokuFeedHttpService {

    private val log = LoggerFactory.getLogger(RokuFeedHttpService::class.java)
    private val mapper = ObjectMapper().apply { enable(SerializationFeature.INDENT_OUTPUT) }

    private fun getConfiguredBaseUrl(ctx: ServiceRequestContext): String {
        val configured = AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val
        if (!configured.isNullOrBlank()) return configured.trimEnd('/')
        val host = ctx.request().headers().get("host") ?: "localhost"
        return "http://$host"
    }

    private fun authenticateDevice(ctx: ServiceRequestContext, endpoint: String): Pair<String, AppUser>? {
        // Device token auth
        val apiKey = ctx.queryParams().get("key")
        if (apiKey != null) {
            val deviceUser = PairingService.validateDeviceToken(apiKey)
            if (deviceUser != null) return apiKey to deviceUser
        }

        // Cookie session fallback
        val cookies = ctx.request().headers().cookies()
        val sessionCookie = cookies.firstOrNull { it.name() == "mm_auth" }
        if (sessionCookie != null) {
            val cookieUser = AuthService.validateCookieToken(sessionCookie.value())
            if (cookieUser != null) {
                log.info("Roku {} served via cookie auth for user {}", endpoint, cookieUser.username)
                return "" to cookieUser
            }
        }

        log.info("Roku {} auth failed (status 401)", endpoint)
        MetricsRegistry.countHttpResponse("roku", 401)
        return null
    }

    private fun jsonResponse(body: String, cacheSeconds: Int = 60): HttpResponse {
        MetricsRegistry.countHttpResponse("roku", 200)
        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, body)
            .header("Cache-Control", "private, max-age=$cacheSeconds")
            .build()
    }

    @Blocking
    @Get("/roku/feed.json")
    fun feed(ctx: ServiceRequestContext): HttpResponse {
        val (apiKey, user) = authenticateDevice(ctx, "feed")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val json = RokuFeedService.generateFeed(getConfiguredBaseUrl(ctx), apiKey, user)
        log.info("Roku feed served (status 200)")
        return jsonResponse(json, 300)
    }

    @Blocking
    @Get("/roku/home.json")
    fun home(ctx: ServiceRequestContext): HttpResponse {
        val (apiKey, user) = authenticateDevice(ctx, "home")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val homeFeed = RokuHomeService.generateHomeFeed(getConfiguredBaseUrl(ctx), apiKey, user)
        val json = mapper.writeValueAsString(homeFeed)
        log.info("Roku home feed served (status 200, {} carousels)", homeFeed.carousels.size)
        return jsonResponse(json)
    }

    @Blocking
    @Get("/roku/search.json")
    fun search(ctx: ServiceRequestContext, @Param("q") @Default("") q: String): HttpResponse {
        val (apiKey, user) = authenticateDevice(ctx, "search")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (q.isBlank()) {
            MetricsRegistry.countHttpResponse("roku", 400)
            return HttpResponse.of(HttpStatus.BAD_REQUEST)
        }
        val result = RokuSearchService.search(q, getConfiguredBaseUrl(ctx), apiKey, user)
        return jsonResponse(mapper.writeValueAsString(result), 0)
    }

    @Blocking
    @Get("regex:^/roku/title/(?<titleId>\\d+)\\.json$")
    fun titleDetail(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val (apiKey, user) = authenticateDevice(ctx, "title-detail")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val detail = RokuTitleService.getTitleDetail(titleId, getConfiguredBaseUrl(ctx), apiKey, user)
        if (detail == null) {
            MetricsRegistry.countHttpResponse("roku", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }
        log.info("Roku title detail served for titleId={} (status 200)", titleId)
        return jsonResponse(mapper.writeValueAsString(detail))
    }

    @Blocking
    @Get("regex:^/roku/collection/(?<collectionId>\\d+)\\.json$")
    fun collection(ctx: ServiceRequestContext, @Param("collectionId") collectionId: Int): HttpResponse {
        val (apiKey, user) = authenticateDevice(ctx, "collection")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val detail = RokuSearchService.getCollectionDetail(collectionId, getConfiguredBaseUrl(ctx), apiKey, user)
        if (detail == null) {
            MetricsRegistry.countHttpResponse("roku", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }
        log.info("Roku collection detail served for collectionId={} (status 200)", collectionId)
        return jsonResponse(mapper.writeValueAsString(detail))
    }

    @Blocking
    @Get("regex:^/roku/tag/(?<tagId>\\d+)\\.json$")
    fun tag(ctx: ServiceRequestContext, @Param("tagId") tagId: Long): HttpResponse {
        val (apiKey, user) = authenticateDevice(ctx, "tag")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val detail = RokuSearchService.getTagDetail(tagId, getConfiguredBaseUrl(ctx), apiKey, user)
        if (detail == null) {
            MetricsRegistry.countHttpResponse("roku", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }
        log.info("Roku tag detail served for tagId={} (status 200)", tagId)
        return jsonResponse(mapper.writeValueAsString(detail))
    }

    @Blocking
    @Get("regex:^/roku/genre/(?<genreId>\\d+)\\.json$")
    fun genre(ctx: ServiceRequestContext, @Param("genreId") genreId: Long): HttpResponse {
        val (apiKey, user) = authenticateDevice(ctx, "genre")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val detail = RokuSearchService.getGenreDetail(genreId, getConfiguredBaseUrl(ctx), apiKey, user)
        if (detail == null) {
            MetricsRegistry.countHttpResponse("roku", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }
        log.info("Roku genre detail served for genreId={} (status 200)", genreId)
        return jsonResponse(mapper.writeValueAsString(detail))
    }

    @Blocking
    @Get("regex:^/roku/actor/(?<personId>\\d+)\\.json$")
    fun actor(ctx: ServiceRequestContext, @Param("personId") personId: Int): HttpResponse {
        val (apiKey, user) = authenticateDevice(ctx, "actor")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val detail = RokuSearchService.getActorDetail(personId, getConfiguredBaseUrl(ctx), apiKey, user)
        if (detail == null) {
            MetricsRegistry.countHttpResponse("roku", 404)
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }
        log.info("Roku actor detail served for personId={} (status 200)", personId)
        return jsonResponse(mapper.writeValueAsString(detail))
    }

    @Blocking
    @Get("/roku/cameras.json")
    fun cameras(ctx: ServiceRequestContext): HttpResponse {
        val (apiKey, _) = authenticateDevice(ctx, "cameras")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val baseUrl = getConfiguredBaseUrl(ctx)

        val cameras = Camera.findAll()
            .filter { it.enabled }
            .sortedBy { it.display_order }
            .map { cam ->
                val keyParam = if (apiKey.isNotEmpty()) "?key=$apiKey" else ""
                mapOf(
                    "id" to cam.id,
                    "name" to cam.name,
                    "streamUrl" to "$baseUrl/cam/${cam.id}/stream.m3u8$keyParam",
                    "snapshotUrl" to "$baseUrl/cam/${cam.id}/snapshot.jpg$keyParam"
                )
            }

        log.info("Roku cameras served ({} cameras, status 200)", cameras.size)
        return jsonResponse(mapper.writeValueAsString(mapOf("cameras" to cameras)))
    }

    @Blocking
    @Get("/roku/livetv/channels.json")
    fun liveTvChannels(ctx: ServiceRequestContext): HttpResponse {
        val (apiKey, user) = authenticateDevice(ctx, "livetv-channels")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val baseUrl = getConfiguredBaseUrl(ctx)

        if (!LiveTvStreamHttpService.canAccessLiveTv(user)) {
            MetricsRegistry.countHttpResponse("roku", 403)
            return HttpResponse.of(HttpStatus.FORBIDDEN)
        }

        val minQuality = user.live_tv_min_quality
        val channels = LiveTvChannel.findAll()
            .filter { it.enabled }
            .filter { ch ->
                val tuner = LiveTvTuner.findById(ch.tuner_id)
                tuner != null && tuner.enabled
            }
            .filter { it.reception_quality >= minQuality }
            .sortedWith(compareBy({ it.display_order }, { it.guide_number.toDoubleOrNull() ?: 9999.0 }))
            .map { ch ->
                val keyParam = if (apiKey.isNotEmpty()) "?key=$apiKey" else ""
                mapOf(
                    "id" to ch.id,
                    "guideNumber" to ch.guide_number,
                    "guideName" to ch.guide_name,
                    "networkAffiliation" to (ch.network_affiliation ?: ""),
                    "receptionQuality" to ch.reception_quality,
                    "streamUrl" to "$baseUrl/live-tv-stream/${ch.id}/stream.m3u8$keyParam"
                )
            }

        log.info("Roku live TV channels served ({} channels, status 200)", channels.size)
        return jsonResponse(mapper.writeValueAsString(mapOf("channels" to channels)))
    }

    @Blocking
    @Post("/roku/wishlist/add")
    fun wishlistAdd(ctx: ServiceRequestContext): HttpResponse {
        val (_, user) = authenticateDevice(ctx, "wishlist-add")
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val body = try {
            val text = ctx.request().aggregate().join().contentUtf8()
            mapper.readTree(text)
        } catch (e: Exception) {
            MetricsRegistry.countHttpResponse("roku", 400)
            return HttpResponse.of(HttpStatus.BAD_REQUEST)
        }

        val tmdbId = body.get("tmdb_id")?.asInt()
        val mediaType = body.get("media_type")?.asText()
        if (tmdbId == null || mediaType.isNullOrBlank()) {
            MetricsRegistry.countHttpResponse("roku", 400)
            return HttpResponse.of(HttpStatus.BAD_REQUEST)
        }

        val title = body.get("title")?.asText()
        val posterPath = body.get("poster_path")?.asText()
        val releaseYear = body.get("release_year")?.asInt()

        val result = RokuSearchService.addWish(tmdbId, mediaType, title, posterPath, releaseYear, user)
        return jsonResponse(mapper.writeValueAsString(result), 0)
    }
}
