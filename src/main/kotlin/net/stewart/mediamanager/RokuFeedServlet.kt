package net.stewart.mediamanager

import com.github.vokorm.findAll
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.PairingService
import net.stewart.mediamanager.service.RokuFeedService
import org.slf4j.LoggerFactory

/**
 * Serves the Roku Direct Publisher JSON feed at `/roku/feed.json`.
 *
 * Requires a `key` query parameter containing a valid device token (from QR code pairing).
 * Returns 401 if the key is missing or invalid.
 */
@WebServlet(urlPatterns = ["/roku/*"])
class RokuFeedServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(RokuFeedServlet::class.java)

    private fun getConfiguredBaseUrl(req: HttpServletRequest): String {
        val configured = AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val
        if (!configured.isNullOrBlank()) return configured.trimEnd('/')
        // Safe fallback: serverName/serverPort are set by the container, not the Host header
        return "${req.scheme}://${req.serverName}:${req.serverPort}"
    }

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val path = req.pathInfo?.removePrefix("/") ?: ""
        if (path != "feed.json") {
            log.info("Roku feed request for unknown path: {}", path)
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("roku", 404)
            return
        }

        val apiKey = req.getParameter("key")
        if (apiKey == null) {
            log.info("Roku feed auth failed — no key (status 401)")
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing API key")
            MetricsRegistry.countHttpResponse("roku", 401)
            return
        }

        // Authenticate via device token (QR code pairing)
        val deviceUser = PairingService.validateDeviceToken(apiKey)
        if (deviceUser == null) {
            log.info("Roku feed auth failed (status 401)")
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing device token. Re-pair your device.")
            MetricsRegistry.countHttpResponse("roku", 401)
            return
        }

        val baseUrl = getConfiguredBaseUrl(req)

        val json = RokuFeedService.generateFeed(baseUrl, apiKey, deviceUser)

        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "public, max-age=300")
        resp.writer.write(json)
        MetricsRegistry.countHttpResponse("roku", 200)
        log.info("Roku feed served (status 200)")
    }
}
