package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PasswordService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Blocking
class UserManagementHttpService {

    private val gson = Gson()
    private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    @Get("/api/v2/admin/users")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val users = AppUser.findAll().sortedBy { it.username.lowercase() }.map { u ->
            mapOf(
                "id" to u.id,
                "username" to u.username,
                "access_level" to u.access_level,
                "locked" to u.locked,
                "must_change_password" to u.must_change_password,
                "rating_ceiling" to u.rating_ceiling,
                "created_at" to u.created_at?.format(dtf)
            )
        }
        return jsonResponse(gson.toJson(mapOf("users" to users)))
    }

    @Post("/api/v2/admin/users")
    fun createUser(ctx: ServiceRequestContext): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val username = (map["username"] as? String)?.trim() ?: return badRequest("username required")
        val password = map["password"] as? String ?: return badRequest("password required")
        val forceChange = (map["force_change"] as? Boolean) ?: true

        if (username.isBlank()) return badRequest("username required")
        if (AppUser.findAll().any { it.username.equals(username, ignoreCase = true) }) {
            return jsonResponse(gson.toJson(mapOf("ok" to false, "error" to "Username already taken")))
        }

        val violations = PasswordService.validate(password, username)
        if (violations.isNotEmpty()) {
            return jsonResponse(gson.toJson(mapOf("ok" to false, "error" to violations.first())))
        }

        val now = LocalDateTime.now()
        AppUser(
            username = username,
            display_name = "TOBEREMOVED",
            password_hash = PasswordService.hash(password),
            access_level = 1,
            must_change_password = forceChange,
            created_at = now,
            updated_at = now
        ).save()
        AuthService.invalidateHasUsersCache()
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/users/{userId}/promote")
    fun promote(ctx: ServiceRequestContext, @Param("userId") userId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        val u = AppUser.findById(userId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        u.access_level = 2; u.save()
        AuthService.invalidateUserSessions(userId)
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/users/{userId}/demote")
    fun demote(ctx: ServiceRequestContext, @Param("userId") userId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        if (AuthService.countAdmins() <= 1) return jsonResponse("""{"ok":false,"error":"Cannot demote the last admin"}""")
        val u = AppUser.findById(userId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        u.access_level = 1; u.save()
        AuthService.invalidateUserSessions(userId)
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/users/{userId}/unlock")
    fun unlock(ctx: ServiceRequestContext, @Param("userId") userId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        val u = AppUser.findById(userId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        u.locked = false; u.updated_at = LocalDateTime.now(); u.save()
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/users/{userId}/force-password-change")
    fun forcePasswordChange(ctx: ServiceRequestContext, @Param("userId") userId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        val u = AppUser.findById(userId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        u.must_change_password = true; u.updated_at = LocalDateTime.now(); u.save()
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/users/{userId}/reset-password")
    fun resetPassword(ctx: ServiceRequestContext, @Param("userId") userId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val newPassword = map["password"] as? String ?: return badRequest("password required")

        val u = AppUser.findById(userId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val violations = PasswordService.validate(newPassword, u.username)
        if (violations.isNotEmpty()) return jsonResponse(gson.toJson(mapOf("ok" to false, "error" to violations.first())))

        u.password_hash = PasswordService.hash(newPassword)
        u.must_change_password = true
        u.updated_at = LocalDateTime.now()
        u.save()
        AuthService.invalidateUserSessions(userId)
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/users/{userId}/rating-ceiling")
    fun setRatingCeiling(ctx: ServiceRequestContext, @Param("userId") userId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val ceiling = (map["rating_ceiling"] as? Number)?.toInt()

        val u = AppUser.findById(userId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        u.rating_ceiling = ceiling
        u.updated_at = LocalDateTime.now()
        u.save()
        AuthService.invalidateUserSessions(userId)
        return jsonResponse("""{"ok":true}""")
    }

    @Delete("/api/v2/admin/users/{userId}")
    fun deleteUser(ctx: ServiceRequestContext, @Param("userId") userId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        if (caller.id == userId) return jsonResponse("""{"ok":false,"error":"Cannot delete yourself"}""")

        val u = AppUser.findById(userId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        if (u.isAdmin() && AuthService.countAdmins() <= 1) return jsonResponse("""{"ok":false,"error":"Cannot delete the last admin"}""")

        AuthService.invalidateUserSessions(userId)
        u.delete()
        AuthService.invalidateHasUsersCache()
        return jsonResponse("""{"ok":true}""")
    }

    @Get("/api/v2/admin/users/{userId}/sessions")
    fun listSessions(ctx: ServiceRequestContext, @Param("userId") userId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val browserSessions = net.stewart.mediamanager.service.PairingService.getSessionTokensForUser(userId).map { token ->
            mapOf("id" to token.id, "type" to "browser", "user_agent" to token.user_agent,
                "created_at" to token.created_at?.toString(), "last_used_at" to token.last_used_at?.toString(),
                "expires_at" to token.expires_at?.toString())
        }
        val deviceSessions = net.stewart.mediamanager.service.PairingService.getDeviceTokensForUser(userId).map { token ->
            mapOf("id" to token.id, "type" to "device", "device_name" to token.device_name,
                "created_at" to token.created_at?.toString(), "last_used_at" to token.last_used_at?.toString())
        }
        return jsonResponse(gson.toJson(mapOf("sessions" to browserSessions + deviceSessions)))
    }

    @Delete("/api/v2/admin/users/{userId}/sessions/{sessionId}")
    fun revokeSession(ctx: ServiceRequestContext, @Param("userId") userId: Long, @Param("sessionId") sessionId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        // Try browser session first, then device token
        val revoked = AuthService.revokeSession(sessionId, null)
        if (!revoked) net.stewart.mediamanager.service.PairingService.revokeToken(sessionId)
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/users/{userId}/revoke-all-sessions")
    fun revokeAllSessions(ctx: ServiceRequestContext, @Param("userId") userId: Long): HttpResponse {
        val caller = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!caller.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        AuthService.invalidateUserSessions(userId)
        return jsonResponse("""{"ok":true}""")
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
