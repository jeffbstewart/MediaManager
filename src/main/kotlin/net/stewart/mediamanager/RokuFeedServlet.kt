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
import net.stewart.mediamanager.service.RokuFeedService
import net.stewart.mediamanager.service.RokuHomeService
import net.stewart.mediamanager.service.RokuTitleService
import org.slf4j.LoggerFactory

/**
 * Serves Roku JSON endpoints at /roku/<anything>.
 *
 * - /roku/feed.json — full Direct Publisher format feed (legacy)
 * - /roku/home.json — home screen carousel feed (named rows with poster items)
 *
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

        when {
            path == "feed.json" -> handleFeed(req, resp)
            path == "home.json" -> handleHome(req, resp)
            path.matches(Regex("title/(\\d+)\\.json")) -> handleTitleDetail(req, resp, path)
            else -> {
                log.info("Roku request for unknown path: {}", path)
                resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                MetricsRegistry.countHttpResponse("roku", 404)
            }
        }
    }

    private fun handleFeed(req: HttpServletRequest, resp: HttpServletResponse) {
        val (apiKey, user) = authenticateDevice(req, resp, "feed") ?: return
        val baseUrl = getConfiguredBaseUrl(req)

        val json = RokuFeedService.generateFeed(baseUrl, apiKey, user)

        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "public, max-age=300")
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
        resp.setHeader("Cache-Control", "public, max-age=60")
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
        resp.setHeader("Cache-Control", "public, max-age=60")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku home feed served (status 200, {} carousels)", homeFeed.carousels.size)
    }
}
