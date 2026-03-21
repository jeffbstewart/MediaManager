package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.DeviceToken
import net.stewart.mediamanager.entity.RefreshToken
import net.stewart.mediamanager.entity.SessionToken
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.PairingService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Handles /api/v1/sessions/... endpoints:
 * - GET /sessions — list all active sessions (browser + app + device) for the current user
 * - DELETE /sessions?scope=others — revoke all sessions except current
 * - DELETE /sessions/{id}?type={browser|app|device} — revoke a specific session
 */
object SessionHandler {

    private val log = LoggerFactory.getLogger(SessionHandler::class.java)

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val method = req.method

        when {
            path.isEmpty() && method == "GET" -> handleList(req, resp, mapper, user)
            path.isEmpty() && method == "DELETE" -> {
                val scope = req.getParameter("scope")
                if (scope == "others") {
                    handleRevokeAllOthers(req, resp, mapper, user)
                } else {
                    ApiV1Servlet.sendError(resp, 400, "invalid_request", mapOf("detail" to "scope=others required"))
                    MetricsRegistry.countHttpResponse("api_v1", 400)
                }
            }
            path.matches(Regex("\\d+")) && method == "DELETE" -> {
                val sessionId = path.toLong()
                handleRevokeOne(req, resp, mapper, user, sessionId)
            }
            else -> {
                ApiV1Servlet.sendError(resp, 404, "not_found")
                MetricsRegistry.countHttpResponse("api_v1", 404)
            }
        }
    }

    private fun handleList(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, user: AppUser) {
        val userId = user.id!!
        val now = LocalDateTime.now()

        // Identify the current refresh token from the JWT
        val currentRefreshFamilyId = extractCurrentFamilyId(req)

        // Browser sessions (session_token table)
        val browserSessions = SessionToken.findAll()
            .filter { it.user_id == userId && it.expires_at.isAfter(now) }
            .map { st ->
                mapOf(
                    "id" to st.id,
                    "type" to "browser",
                    "device_name" to summarizeUserAgent(st.user_agent),
                    "created_at" to st.created_at?.toString(),
                    "last_used_at" to st.last_used_at?.toString(),
                    "expires_at" to st.expires_at.toString(),
                    "is_current" to false // browser sessions are never "current" for an API caller
                )
            }

        // App sessions (refresh_token table — non-revoked, non-expired)
        val appSessions = RefreshToken.findAll()
            .filter { it.user_id == userId && !it.revoked && it.expires_at.isAfter(now) && it.replaced_by_hash == null }
            .map { rt ->
                mapOf(
                    "id" to rt.id,
                    "type" to "app",
                    "device_name" to rt.device_name.ifEmpty { "Unknown Device" },
                    "created_at" to rt.created_at?.toString(),
                    "last_used_at" to rt.created_at?.toString(), // refresh tokens don't track last_used
                    "expires_at" to rt.expires_at.toString(),
                    "is_current" to (rt.family_id == currentRefreshFamilyId)
                )
            }

        // Paired devices (device_token table)
        val deviceSessions = DeviceToken.findAll()
            .filter { it.user_id == userId }
            .map { dt ->
                mapOf(
                    "id" to dt.id,
                    "type" to "device",
                    "device_name" to dt.device_name.ifEmpty { "Unknown Device" },
                    "created_at" to dt.created_at?.toString(),
                    "last_used_at" to dt.last_used_at?.toString(),
                    "expires_at" to null,
                    "is_current" to false
                )
            }

        val sessions = (browserSessions + appSessions + deviceSessions)
            .sortedByDescending { it["last_used_at"] as? String ?: it["created_at"] as? String ?: "" }

        ApiV1Servlet.sendJson(resp, 200, mapOf("sessions" to sessions), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleRevokeOne(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, user: AppUser, sessionId: Long) {
        val type = req.getParameter("type")
        if (type.isNullOrBlank()) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request", mapOf("detail" to "type parameter required (browser, app, device)"))
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        when (type) {
            "browser" -> {
                val token = SessionToken.findById(sessionId)
                if (token == null || token.user_id != user.id) {
                    ApiV1Servlet.sendError(resp, 404, "not_found")
                    MetricsRegistry.countHttpResponse("api_v1", 404)
                    return
                }
                token.delete()
                log.info("AUDIT: API revoked browser session id={} for user_id={}", sessionId, user.id)
            }
            "app" -> {
                val currentFamilyId = extractCurrentFamilyId(req)
                val token = RefreshToken.findById(sessionId)
                if (token == null || token.user_id != user.id) {
                    ApiV1Servlet.sendError(resp, 404, "not_found")
                    MetricsRegistry.countHttpResponse("api_v1", 404)
                    return
                }
                if (token.family_id == currentFamilyId) {
                    ApiV1Servlet.sendError(resp, 403, "cannot_revoke_current")
                    MetricsRegistry.countHttpResponse("api_v1", 403)
                    return
                }
                token.revoked = true
                token.save()
                log.info("AUDIT: API revoked app session id={} for user_id={}", sessionId, user.id)
            }
            "device" -> {
                val token = DeviceToken.findById(sessionId)
                if (token == null || token.user_id != user.id) {
                    ApiV1Servlet.sendError(resp, 404, "not_found")
                    MetricsRegistry.countHttpResponse("api_v1", 404)
                    return
                }
                PairingService.revokeToken(sessionId)
                log.info("AUDIT: API revoked device token id={} for user_id={}", sessionId, user.id)
            }
            else -> {
                ApiV1Servlet.sendError(resp, 400, "invalid_type")
                MetricsRegistry.countHttpResponse("api_v1", 400)
                return
            }
        }

        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleRevokeAllOthers(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, user: AppUser) {
        val userId = user.id!!
        val currentFamilyId = extractCurrentFamilyId(req)

        // Revoke all browser sessions
        SessionToken.findAll().filter { it.user_id == userId }.forEach { it.delete() }

        // Revoke all refresh tokens except current family
        RefreshToken.findAll()
            .filter { it.user_id == userId && !it.revoked && it.family_id != currentFamilyId }
            .forEach { it.revoked = true; it.save() }

        // Revoke all device tokens
        PairingService.revokeAllForUser(userId)

        log.info("AUDIT: API revoked all other sessions for user_id={}", userId)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    /**
     * Extracts the family_id of the current refresh token by looking at the JWT's subject
     * and finding the most recent non-revoked refresh token for this user's device.
     * This is imperfect but sufficient — the caller's access token was issued from a specific
     * refresh token family, and we use the JWT's issued-at to find the matching family.
     */
    private fun extractCurrentFamilyId(req: HttpServletRequest): String? {
        val token = ApiV1Servlet.extractBearer(req) ?: return null
        val decoded = try {
            com.auth0.jwt.JWT.decode(token)
        } catch (_: Exception) {
            return null
        }
        val userId = decoded.subject?.toLongOrNull() ?: return null
        val issuedAt = decoded.issuedAtAsInstant ?: return null

        // Find the refresh token family that was active when this access token was issued.
        // The access token's iat matches (within seconds) the refresh token's created_at.
        return RefreshToken.findAll()
            .filter { it.user_id == userId && !it.revoked }
            .minByOrNull {
                val created = it.created_at?.atZone(java.time.ZoneId.systemDefault())?.toInstant() ?: java.time.Instant.MIN
                kotlin.math.abs(java.time.Duration.between(issuedAt, created).seconds)
            }
            ?.family_id
    }

    private fun summarizeUserAgent(ua: String): String {
        if (ua.isBlank()) return "Unknown Browser"
        return when {
            "Chrome" in ua && "Edg" in ua -> "Edge"
            "Chrome" in ua -> "Chrome"
            "Firefox" in ua -> "Firefox"
            "Safari" in ua -> "Safari"
            else -> ua.take(50)
        }
    }
}
