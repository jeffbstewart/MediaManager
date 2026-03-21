package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.CommandLineFlags
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.MetricsRegistry
import org.slf4j.LoggerFactory

/**
 * REST API servlet for the iOS app at /api/v1/...
 *
 * Security:
 * - HTTPS enforced in production (localhost exempt, developer_mode exempt)
 * - JWT auth via Authorization: Bearer header only (never from URL params, cookies, or form data)
 * - Generic error messages to prevent information leakage
 */
@WebServlet(urlPatterns = ["/api/v1/*"])
class ApiV1Servlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(ApiV1Servlet::class.java)
    private val MAX_BODY_SIZE = 65_536 // 64 KB — generous for auth payloads

    val mapper: ObjectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
    }

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"

        // /discover is unauthenticated and HTTPS-exempt — it returns only
        // API versions and the canonical HTTPS URL for client bootstrap via SSDP.
        val path0 = req.pathInfo?.removePrefix("/") ?: ""
        if (path0 == "discover" && req.method == "GET") {
            DiscoverHandler.handle(req, resp, mapper)
            return
        }

        if (!isSecureOrExempt(req)) {
            sendError(resp, 403, "https_required")
            MetricsRegistry.countHttpResponse("api_v1", 403)
            return
        }

        // Reject oversized or unbounded request bodies (DoS protection).
        // Content-Length of -1 means missing header (chunked encoding) — reject on
        // methods with bodies to prevent unbounded reads.
        val cl = req.contentLength
        if (cl > MAX_BODY_SIZE || (cl < 0 && req.method in setOf("POST", "PUT", "PATCH"))) {
            sendError(resp, 413, "payload_too_large")
            MetricsRegistry.countHttpResponse("api_v1", 413)
            return
        }

        val path = req.pathInfo?.removePrefix("/") ?: ""
        val method = req.method

        try {
            when {
                path.startsWith("auth/") -> AuthHandler.handle(req, resp, path.removePrefix("auth/"), mapper)
                path == "info" && method == "GET" -> InfoHandler.handle(req, resp, mapper)
                path.startsWith("catalog/") && method == "GET" -> CatalogHandler.handle(req, resp, path.removePrefix("catalog/"), mapper)
                path.startsWith("playback/") -> PlaybackHandler.handle(req, resp, path.removePrefix("playback/"), mapper)
                path == "wishlist" || path.startsWith("wishlist/") -> WishListHandler.handle(req, resp, path.removePrefix("wishlist").removePrefix("/"), mapper)
                path.startsWith("downloads/") || path == "downloads" -> DownloadHandler.handle(req, resp, path.removePrefix("downloads").removePrefix("/"), mapper)
                path.startsWith("admin/") -> AdminHandler.handle(req, resp, path.removePrefix("admin/"), mapper)
                path.startsWith("live/") -> LiveHandler.handle(req, resp, path.removePrefix("live/"), mapper)
                path == "tmdb/search" && method == "GET" -> TmdbSearchHandler.handle(req, resp, mapper)
                else -> {
                    sendError(resp, 404, "not_found")
                    MetricsRegistry.countHttpResponse("api_v1", 404)
                }
            }
        } catch (e: Exception) {
            log.error("API error on {} {}: {}", method, path, e.message, e)
            sendError(resp, 500, "internal_error")
            MetricsRegistry.countHttpResponse("api_v1", 500)
        }
    }

    private fun isSecureOrExempt(req: HttpServletRequest): Boolean {
        if (CommandLineFlags.developerMode) return true
        if (req.isSecure) return true
        val proto = req.getHeader("X-Forwarded-Proto")
        if (proto?.equals("https", ignoreCase = true) == true) return true
        val addr = req.remoteAddr
        return addr == "127.0.0.1" || addr == "0:0:0:0:0:0:0:1" || addr == "::1"
    }

    companion object {
        fun sendJson(resp: HttpServletResponse, status: Int, data: Any, mapper: ObjectMapper) {
            resp.status = status
            mapper.writeValue(resp.writer, data)
        }

        fun sendError(resp: HttpServletResponse, status: Int, error: String, extra: Map<String, Any> = emptyMap()) {
            resp.status = status
            val body = mutableMapOf<String, Any>("error" to error)
            body.putAll(extra)
            // Use a minimal ObjectMapper for proper JSON escaping
            ObjectMapper().writeValue(resp.writer, body)
        }

        /**
         * Extracts the Bearer token from the Authorization header.
         * Returns null if the header is missing or not a Bearer token.
         * JWT is NEVER accepted from URL parameters, cookies, or form data (Finding 14).
         */
        fun extractBearer(req: HttpServletRequest): String? {
            val auth = req.getHeader("Authorization") ?: return null
            if (!auth.startsWith("Bearer ", ignoreCase = true)) return null
            return auth.substring(7).trim()
        }

        /**
         * Validates the Bearer token and returns the authenticated user.
         * Sends a 401 error response and returns null if auth fails.
         */
        fun requireAuth(req: HttpServletRequest, resp: HttpServletResponse): AppUser? {
            val token = extractBearer(req) ?: run {
                sendError(resp, 401, "auth_required")
                MetricsRegistry.countHttpResponse("api_v1", 401)
                return null
            }
            return JwtService.validateAccessToken(token) ?: run {
                sendError(resp, 401, "invalid_token")
                MetricsRegistry.countHttpResponse("api_v1", 401)
                return null
            }
        }
    }
}
