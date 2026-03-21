package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.PasswordService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Handles /api/v1/admin/users/... endpoints (admin-only):
 * - GET /users — list all users
 * - PUT /users/{id}/role — promote/demote
 * - PUT /users/{id}/rating-ceiling — set content rating ceiling
 * - POST /users/{id}/unlock — unlock account
 * - POST /users/{id}/reset-password — reset password
 * - POST /users/{id}/force-password-change — set must_change_password flag
 * - DELETE /users/{id} — delete user
 */
object UserManagementHandler {

    private val log = LoggerFactory.getLogger(UserManagementHandler::class.java)

    private val USER_ACTION = Regex("(\\d+)/(role|rating-ceiling|unlock|reset-password|force-password-change)")
    private val USER_ID = Regex("(\\d+)")

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper, adminUser: AppUser) {
        val method = req.method

        when {
            path.isEmpty() && method == "GET" -> handleList(resp, mapper)
            else -> {
                val actionMatch = USER_ACTION.matchEntire(path)
                val idMatch = USER_ID.matchEntire(path)

                when {
                    actionMatch != null -> {
                        val userId = actionMatch.groupValues[1].toLong()
                        val action = actionMatch.groupValues[2]
                        when {
                            action == "role" && method == "PUT" -> handleSetRole(req, resp, mapper, adminUser, userId)
                            action == "rating-ceiling" && method == "PUT" -> handleSetRatingCeiling(req, resp, mapper, userId)
                            action == "unlock" && method == "POST" -> handleUnlock(resp, mapper, userId)
                            action == "reset-password" && method == "POST" -> handleResetPassword(req, resp, mapper, adminUser, userId)
                            action == "force-password-change" && method == "POST" -> handleForcePasswordChange(resp, mapper, userId)
                            else -> {
                                ApiV1Servlet.sendError(resp, 405, "method_not_allowed")
                                MetricsRegistry.countHttpResponse("api_v1", 405)
                            }
                        }
                    }
                    idMatch != null && method == "DELETE" -> {
                        val userId = idMatch.groupValues[1].toLong()
                        handleDelete(resp, mapper, adminUser, userId)
                    }
                    else -> {
                        ApiV1Servlet.sendError(resp, 404, "not_found")
                        MetricsRegistry.countHttpResponse("api_v1", 404)
                    }
                }
            }
        }
    }

    private fun handleList(resp: HttpServletResponse, mapper: ObjectMapper) {
        val users = AppUser.findAll().sortedBy { it.username.lowercase() }

        val items = users.map { u ->
            val ceilingLabel = if (u.rating_ceiling != null) {
                ContentRating.entries.firstOrNull { it.ordinalLevel == u.rating_ceiling }?.displayLabel
            } else null

            mapOf(
                "id" to u.id,
                "username" to u.username,
                "display_name" to u.display_name,
                "access_level" to u.access_level,
                "is_admin" to u.isAdmin(),
                "locked" to u.locked,
                "must_change_password" to u.must_change_password,
                "rating_ceiling" to u.rating_ceiling,
                "rating_ceiling_label" to ceilingLabel,
                "created_at" to u.created_at?.toString()
            )
        }

        ApiV1Servlet.sendJson(resp, 200, mapOf("users" to items), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleSetRole(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, adminUser: AppUser, userId: Long) {
        val body = try { mapper.readTree(req.reader) } catch (_: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }
        val accessLevel = body.get("access_level")?.asInt()
        if (accessLevel == null || accessLevel !in 1..2) {
            ApiV1Servlet.sendError(resp, 400, "invalid_access_level")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val user = AppUser.findById(userId)
        if (user == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }

        // Block demoting the last admin
        if (accessLevel < 2 && user.isAdmin() && AuthService.countAdmins() <= 1) {
            ApiV1Servlet.sendError(resp, 409, "last_admin")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }

        user.access_level = accessLevel
        user.updated_at = LocalDateTime.now()
        user.save()
        AuthService.invalidateUserSessions(userId)

        log.info("AUDIT: Admin '{}' set user '{}' access_level={}", adminUser.username, user.username, accessLevel)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleSetRatingCeiling(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, userId: Long) {
        val body = try { mapper.readTree(req.reader) } catch (_: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }
        val ceilingNode = body.get("ceiling")
        val ceiling = if (ceilingNode == null || ceilingNode.isNull) null else ceilingNode.asInt()

        val user = AppUser.findById(userId)
        if (user == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }

        user.rating_ceiling = ceiling
        user.updated_at = LocalDateTime.now()
        user.save()

        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleUnlock(resp: HttpServletResponse, mapper: ObjectMapper, userId: Long) {
        val user = AppUser.findById(userId)
        if (user == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }
        user.locked = false
        user.updated_at = LocalDateTime.now()
        user.save()
        log.info("AUDIT: Account '{}' unlocked via API", user.username)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleResetPassword(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, adminUser: AppUser, userId: Long) {
        val body = try { mapper.readTree(req.reader) } catch (_: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }
        val newPassword = body.get("new_password")?.asText()
        val forceChange = body.get("force_change")?.asBoolean() ?: false

        if (newPassword.isNullOrBlank()) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val user = AppUser.findById(userId)
        if (user == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }

        val violations = PasswordService.validate(newPassword, user.username)
        if (violations.isNotEmpty()) {
            ApiV1Servlet.sendError(resp, 400, "validation_failed", mapOf("detail" to violations.first()))
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        user.password_hash = PasswordService.hash(newPassword)
        user.must_change_password = forceChange
        user.updated_at = LocalDateTime.now()
        user.save()
        AuthService.invalidateUserSessions(userId)

        log.info("AUDIT: Admin '{}' reset password for user '{}' (force_change={})", adminUser.username, user.username, forceChange)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleForcePasswordChange(resp: HttpServletResponse, mapper: ObjectMapper, userId: Long) {
        val user = AppUser.findById(userId)
        if (user == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }
        user.must_change_password = true
        user.updated_at = LocalDateTime.now()
        user.save()
        log.info("AUDIT: Forced password change for user '{}'", user.username)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleDelete(resp: HttpServletResponse, mapper: ObjectMapper, adminUser: AppUser, userId: Long) {
        if (userId == adminUser.id) {
            ApiV1Servlet.sendError(resp, 409, "cannot_delete_self")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }
        val user = AppUser.findById(userId)
        if (user == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }
        if (user.isAdmin() && AuthService.countAdmins() <= 1) {
            ApiV1Servlet.sendError(resp, 409, "last_admin")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }
        AuthService.invalidateUserSessions(userId)
        user.delete()
        log.info("AUDIT: Admin '{}' deleted user '{}'", adminUser.username, user.username)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }
}
