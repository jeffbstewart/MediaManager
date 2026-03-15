package net.stewart.mediamanager

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.github.vokorm.findAll
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.PairingService
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.service.RokuFeedService
import net.stewart.mediamanager.service.RokuHomeService
import net.stewart.mediamanager.service.RokuSearchService
import net.stewart.mediamanager.service.RokuTitleService
import org.slf4j.LoggerFactory

/**
 * Serves Roku JSON endpoints at /roku/<anything>.
 *
 * GET endpoints:
 * - /roku/feed.json — full Direct Publisher format feed (legacy)
 * - /roku/home.json — home screen carousel feed (named rows with poster items)
 * - /roku/search.json?q=query — search across titles, actors, collections, tags, genres
 * - /roku/title/{id}.json — per-title detail for episode picker
 * - /roku/collection/{tmdbCollectionId}.json — collection landing page
 * - /roku/tag/{id}.json — tag landing page
 * - /roku/genre/{id}.json — genre landing page
 * - /roku/actor/{tmdbPersonId}.json — actor landing page
 *
 * POST endpoints:
 * - /roku/wishlist/add — add a media wish
 *
 * All endpoints require a key query parameter containing a valid device token.
 */

@WebServlet(urlPatterns = ["/roku/*"])
class RokuFeedServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(RokuFeedServlet::class.java)

    private val mapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    private fun getConfiguredBaseUrl(req: HttpServletRequest): String {
        val configured = AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val
        if (!configured.isNullOrBlank()) return configured.trimEnd('/')
        return "${req.scheme}://${req.serverName}:${req.serverPort}"
    }

    /**
     * Authenticates a request via device token (key param) or browser session (cookie).
     * Returns the API key (or empty string for cookie auth) and the authenticated user,
     * or null after sending an error response.
     */
    private fun authenticateDevice(req: HttpServletRequest, resp: HttpServletResponse, endpoint: String): Pair<String, net.stewart.mediamanager.entity.AppUser>? {
        // Try device token auth first
        val apiKey = req.getParameter("key")
        if (apiKey != null) {
            val deviceUser = PairingService.validateDeviceToken(apiKey)
            if (deviceUser != null) {
                return apiKey to deviceUser
            }
        }

        // Fall back to cookie session auth
        val cookieUser = AuthService.validateCookieFromRequest(req)
        if (cookieUser != null) {
            log.info("Roku {} served via cookie auth for user {}", endpoint, cookieUser.username)
            return "" to cookieUser
        }

        log.info("Roku {} auth failed (status 401)", endpoint)
        resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing credentials")
        MetricsRegistry.countHttpResponse("roku", 401)
        return null
    }

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val path = req.pathInfo?.removePrefix("/") ?: ""
        try {
            when {
                path == "feed.json" -> handleFeed(req, resp)
                path == "home.json" -> handleHome(req, resp)
                path == "search.json" -> handleSearch(req, resp)
                path.matches(Regex("title/(\\d+)\\.json")) -> handleTitleDetail(req, resp, path)
                path.matches(Regex("collection/(\\d+)\\.json")) -> handleCollection(req, resp, path)
                path.matches(Regex("tag/(\\d+)\\.json")) -> handleTag(req, resp, path)
                path.matches(Regex("genre/(\\d+)\\.json")) -> handleGenre(req, resp, path)
                path.matches(Regex("actor/(\\d+)\\.json")) -> handleActor(req, resp, path)
                path == "cameras.json" -> handleCameras(req, resp)
                path == "livetv/channels.json" -> handleLiveTvChannels(req, resp)
                else -> {
                    log.info("Roku request for unknown path: {}", path)
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                    MetricsRegistry.countHttpResponse("roku", 404)
                }
            }
        } catch (e: Exception) {
            log.error("Roku API error on GET /{}: {}", path, e.message, e)
            if (!resp.isCommitted) {
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error")
            }
            MetricsRegistry.countHttpResponse("roku", 500)
        }
    }

    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        val path = req.pathInfo?.removePrefix("/") ?: ""
        try {
            when {
                path == "wishlist/add" -> handleWishlistAdd(req, resp)
                else -> {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                    MetricsRegistry.countHttpResponse("roku", 404)
                }
            }
        } catch (e: Exception) {
            log.error("Roku API error on POST /{}: {}", path, e.message, e)
            if (!resp.isCommitted) {
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error")
            }
            MetricsRegistry.countHttpResponse("roku", 500)
        }
    }

    private fun handleFeed(req: HttpServletRequest, resp: HttpServletResponse) {
        val (apiKey, user) = authenticateDevice(req, resp, "feed") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        val json = RokuFeedService.generateFeed(baseUrl, apiKey, user)

        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "private, max-age=300")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku feed served (status 200)")
    }

    private fun handleTitleDetail(req: HttpServletRequest, resp: HttpServletResponse, path: String) {
        val (apiKey, user) = authenticateDevice(req, resp, "title-detail") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        val titleId = Regex("title/(\\d+)\\.json").find(path)?.groupValues?.get(1)?.toLongOrNull()
        if (titleId == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid title ID")
            MetricsRegistry.countHttpResponse("roku", 400)
            return
        }

        val detail = RokuTitleService.getTitleDetail(titleId, baseUrl, apiKey, user)
        if (detail == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Title not found or not playable")
            MetricsRegistry.countHttpResponse("roku", 404)
            return
        }

        val json = mapper.writeValueAsString(detail)
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "private, max-age=60")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku title detail served for titleId={} (status 200)", titleId)
    }

    private fun handleHome(req: HttpServletRequest, resp: HttpServletResponse) {
        val (apiKey, user) = authenticateDevice(req, resp, "home") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        val homeFeed = RokuHomeService.generateHomeFeed(baseUrl, apiKey, user)
        val json = mapper.writeValueAsString(homeFeed)

        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "private, max-age=60")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku home feed served (status 200, {} carousels)", homeFeed.carousels.size)
    }

    private fun handleSearch(req: HttpServletRequest, resp: HttpServletResponse) {
        val (apiKey, user) = authenticateDevice(req, resp, "search") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        val query = req.getParameter("q")
        if (query.isNullOrBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing query parameter 'q'")
            MetricsRegistry.countHttpResponse("roku", 400)
            return
        }

        val result = RokuSearchService.search(query, baseUrl, apiKey, user)
        val json = mapper.writeValueAsString(result)

        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "no-cache")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
    }

    private fun handleCollection(req: HttpServletRequest, resp: HttpServletResponse, path: String) {
        val (apiKey, user) = authenticateDevice(req, resp, "collection") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        val collId = Regex("collection/(\\d+)\\.json").find(path)?.groupValues?.get(1)?.toIntOrNull()
        if (collId == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid collection ID")
            MetricsRegistry.countHttpResponse("roku", 400)
            return
        }

        val detail = RokuSearchService.getCollectionDetail(collId, baseUrl, apiKey, user)
        if (detail == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Collection not found")
            MetricsRegistry.countHttpResponse("roku", 404)
            return
        }

        val json = mapper.writeValueAsString(detail)
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "private, max-age=60")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku collection detail served for collectionId={} (status 200)", collId)
    }

    private fun handleTag(req: HttpServletRequest, resp: HttpServletResponse, path: String) {
        val (apiKey, user) = authenticateDevice(req, resp, "tag") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        val tagId = Regex("tag/(\\d+)\\.json").find(path)?.groupValues?.get(1)?.toLongOrNull()
        if (tagId == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid tag ID")
            MetricsRegistry.countHttpResponse("roku", 400)
            return
        }

        val detail = RokuSearchService.getTagDetail(tagId, baseUrl, apiKey, user)
        if (detail == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Tag not found")
            MetricsRegistry.countHttpResponse("roku", 404)
            return
        }

        val json = mapper.writeValueAsString(detail)
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "private, max-age=60")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku tag detail served for tagId={} (status 200)", tagId)
    }

    private fun handleGenre(req: HttpServletRequest, resp: HttpServletResponse, path: String) {
        val (apiKey, user) = authenticateDevice(req, resp, "genre") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        val genreId = Regex("genre/(\\d+)\\.json").find(path)?.groupValues?.get(1)?.toLongOrNull()
        if (genreId == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid genre ID")
            MetricsRegistry.countHttpResponse("roku", 400)
            return
        }

        val detail = RokuSearchService.getGenreDetail(genreId, baseUrl, apiKey, user)
        if (detail == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Genre not found")
            MetricsRegistry.countHttpResponse("roku", 404)
            return
        }

        val json = mapper.writeValueAsString(detail)
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "private, max-age=60")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku genre detail served for genreId={} (status 200)", genreId)
    }

    private fun handleActor(req: HttpServletRequest, resp: HttpServletResponse, path: String) {
        val (apiKey, user) = authenticateDevice(req, resp, "actor") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        val personId = Regex("actor/(\\d+)\\.json").find(path)?.groupValues?.get(1)?.toIntOrNull()
        if (personId == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid actor ID")
            MetricsRegistry.countHttpResponse("roku", 400)
            return
        }

        val detail = RokuSearchService.getActorDetail(personId, baseUrl, apiKey, user)
        if (detail == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Actor not found")
            MetricsRegistry.countHttpResponse("roku", 404)
            return
        }

        val json = mapper.writeValueAsString(detail)
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "private, max-age=60")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku actor detail served for personId={} (status 200)", personId)
    }

    private fun handleCameras(req: HttpServletRequest, resp: HttpServletResponse) {
        val (apiKey, _) = authenticateDevice(req, resp, "cameras") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

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

        val json = mapper.writeValueAsString(mapOf("cameras" to cameras))
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "private, max-age=60")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku cameras served ({} cameras, status 200)", cameras.size)
    }

    private fun handleLiveTvChannels(req: HttpServletRequest, resp: HttpServletResponse) {
        val (apiKey, user) = authenticateDevice(req, resp, "livetv-channels") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        // Content rating gate
        if (!LiveTvStreamServlet.canAccessLiveTv(user)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Live TV access restricted")
            MetricsRegistry.countHttpResponse("roku", 403)
            return
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
                    "receptionQuality" to ch.reception_quality,
                    "streamUrl" to "$baseUrl/live-tv-stream/${ch.id}/stream.m3u8$keyParam"
                )
            }

        val json = mapper.writeValueAsString(mapOf("channels" to channels))
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "private, max-age=60")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku live TV channels served ({} channels, status 200)", channels.size)
    }

    private fun handleWishlistAdd(req: HttpServletRequest, resp: HttpServletResponse) {
        val (_, user) = authenticateDevice(req, resp, "wishlist-add") ?: return

        val body = try {
            val text = req.inputStream.readNBytes(4096).toString(Charsets.UTF_8)
            mapper.readTree(text)
        } catch (e: Exception) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body")
            MetricsRegistry.countHttpResponse("roku", 400)
            return
        }

        val tmdbId = body.get("tmdb_id")?.asInt()
        val mediaType = body.get("media_type")?.asText()
        if (tmdbId == null || mediaType.isNullOrBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing tmdb_id or media_type")
            MetricsRegistry.countHttpResponse("roku", 400)
            return
        }

        val title = body.get("title")?.asText()
        val posterPath = body.get("poster_path")?.asText()
        val releaseYear = body.get("release_year")?.asInt()

        val result = RokuSearchService.addWish(tmdbId, mediaType, title, posterPath, releaseYear, user)

        val json = mapper.writeValueAsString(result)
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "no-cache")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
    }
}
