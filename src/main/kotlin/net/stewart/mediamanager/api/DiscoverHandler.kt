package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.MetricsRegistry

/**
 * Handles GET /api/v1/discover — unauthenticated, HTTPS-exempt endpoint for client bootstrap.
 *
 * Returns only the minimum information needed for a client to establish a secure connection:
 * - Supported API versions (so the client knows if it's compatible)
 * - The canonical HTTPS base URL (so the client knows where to authenticate)
 *
 * No server version, title count, capabilities, or user data — those stay behind auth on /info.
 */
object DiscoverHandler {

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val secureUrl = AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val?.trimEnd('/')

        val response = mutableMapOf<String, Any>(
            "api_versions" to listOf("v1"),
            "auth_methods" to listOf("jwt"),
            "server_fingerprint" to JwtService.getSigningKeyFingerprint()
        )
        if (!secureUrl.isNullOrBlank()) {
            response["secure_url"] = secureUrl
        }

        ApiV1Servlet.sendJson(resp, 200, response, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
