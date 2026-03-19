package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.LoginResult
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.RefreshResult
import org.slf4j.LoggerFactory

/**
 * Handles /api/v1/auth/... endpoints:
 * - POST /auth/login — authenticate with username/password, receive JWT + refresh token
 * - POST /auth/refresh — exchange refresh token for new token pair (rotation)
 * - POST /auth/revoke — revoke a refresh token (logout)
 *
 * Error responses always use generic messages to prevent information leakage (Finding 6).
 */
object AuthHandler {

    private val log = LoggerFactory.getLogger(AuthHandler::class.java)

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        if (req.method != "POST") {
            ApiV1Servlet.sendError(resp, 405, "method_not_allowed")
            MetricsRegistry.countHttpResponse("api_v1", 405)
            return
        }

        when (path) {
            "login" -> handleLogin(req, resp, mapper)
            "refresh" -> handleRefresh(req, resp, mapper)
            "revoke" -> handleRevoke(req, resp, mapper)
            else -> {
                ApiV1Servlet.sendError(resp, 404, "not_found")
                MetricsRegistry.countHttpResponse("api_v1", 404)
            }
        }
    }

    private fun handleLogin(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val body = try {
            mapper.readTree(req.reader)
        } catch (e: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val username = body.get("username")?.asText()
        val password = body.get("password")?.asText()
        val deviceName = body.get("device_name")?.asText() ?: ""

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val ip = req.remoteAddr

        when (val result = AuthService.login(username, password, ip)) {
            is LoginResult.Success -> {
                val tokenPair = JwtService.createTokenPair(result.user, deviceName)
                val response = mapOf(
                    "access_token" to tokenPair.accessToken,
                    "refresh_token" to tokenPair.refreshToken,
                    "expires_in" to tokenPair.expiresIn,
                    "token_type" to "Bearer"
                )
                ApiV1Servlet.sendJson(resp, 200, response, mapper)
                MetricsRegistry.countHttpResponse("api_v1", 200)
            }
            is LoginResult.Failed -> {
                // Generic error — never reveal whether the username exists (Finding 6)
                ApiV1Servlet.sendError(resp, 401, "invalid_credentials")
                MetricsRegistry.countHttpResponse("api_v1", 401)
            }
            is LoginResult.RateLimited -> {
                resp.setIntHeader("Retry-After", result.retryAfterSeconds.toInt())
                ApiV1Servlet.sendError(resp, 429, "rate_limited",
                    mapOf("retry_after" to result.retryAfterSeconds))
                MetricsRegistry.countHttpResponse("api_v1", 429)
            }
        }
    }

    private fun handleRefresh(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val body = try {
            mapper.readTree(req.reader)
        } catch (e: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val refreshToken = body.get("refresh_token")?.asText()
        if (refreshToken.isNullOrBlank()) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        when (val result = JwtService.refresh(refreshToken)) {
            is RefreshResult.Success -> {
                val response = mapOf(
                    "access_token" to result.tokenPair.accessToken,
                    "refresh_token" to result.tokenPair.refreshToken,
                    "expires_in" to result.tokenPair.expiresIn,
                    "token_type" to "Bearer"
                )
                ApiV1Servlet.sendJson(resp, 200, response, mapper)
                MetricsRegistry.countHttpResponse("api_v1", 200)
            }
            is RefreshResult.InvalidToken -> {
                ApiV1Servlet.sendError(resp, 401, "invalid_token")
                MetricsRegistry.countHttpResponse("api_v1", 401)
            }
            is RefreshResult.FamilyRevoked -> {
                ApiV1Servlet.sendError(resp, 401, "token_revoked")
                MetricsRegistry.countHttpResponse("api_v1", 401)
            }
        }
    }

    private fun handleRevoke(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val body = try {
            mapper.readTree(req.reader)
        } catch (e: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val refreshToken = body.get("refresh_token")?.asText()
        if (refreshToken.isNullOrBlank()) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        JwtService.revoke(refreshToken)
        // Always return success to prevent token existence probing
        ApiV1Servlet.sendJson(resp, 200, mapOf("revoked" to true), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
