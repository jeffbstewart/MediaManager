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
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.SessionToken
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.service.UserTitleFlagService
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PairingService
import net.stewart.mediamanager.service.PasswordService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * REST endpoints for the profile page in the Angular web app.
 */
@Blocking
class ProfileHttpService {

    private val log = LoggerFactory.getLogger(ProfileHttpService::class.java)
    private val gson = Gson()

    @Get("/api/v2/profile")
    fun getProfile(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val hasLiveTv = LiveTvTuner.findAll().any { it.enabled }

        val profile = mapOf(
            "id" to user.id,
            "username" to user.username,
            "display_name" to user.display_name,
            "is_admin" to user.isAdmin(),
            "rating_ceiling" to user.rating_ceiling,
            "live_tv_min_quality" to (user.live_tv_min_quality ?: 4),
            "has_live_tv" to hasLiveTv
        )
        return jsonResponse(gson.toJson(profile))
    }

    @Post("/api/v2/profile/tv-quality")
    fun updateTvQuality(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val quality = (map["quality"] as? Number)?.toInt() ?: return badRequest("quality required")

        val fresh = AppUser.findById(user.id!!) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        fresh.live_tv_min_quality = quality.coerceIn(1, 5)
        fresh.save()
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    @Post("/api/v2/profile/change-password")
    fun changePassword(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val currentPassword = map["current_password"] as? String ?: return badRequest("current_password required")
        val newPassword = map["new_password"] as? String ?: return badRequest("new_password required")

        val fresh = AppUser.findById(user.id!!) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        // Verify current password
        if (!PasswordService.verify(currentPassword, fresh.password_hash)) {
            return jsonResponse(gson.toJson(mapOf("ok" to false, "error" to "Current password is incorrect")))
        }

        // Validate new password
        val violations = PasswordService.validate(newPassword, fresh.username)
        if (violations.isNotEmpty()) {
            return jsonResponse(gson.toJson(mapOf("ok" to false, "error" to violations.first())))
        }

        // Update password
        fresh.password_hash = PasswordService.hash(newPassword)
        fresh.must_change_password = false
        fresh.updated_at = LocalDateTime.now()
        fresh.save()

        // Invalidate all other sessions
        AuthService.invalidateUserSessions(user.id!!)

        log.info("AUDIT: Password changed for user '{}' via Angular profile", fresh.username)
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    @Get("/api/v2/profile/sessions")
    fun listSessions(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val currentTokenHash = ctx.request().headers().cookies()
            .firstOrNull { it.name() == "mm_auth" }
            ?.value()
            ?.let { AuthService.hashToken(it) }

        val browserSessions = SessionToken.findAll()
            .filter { it.user_id == user.id!! }
            .sortedByDescending { it.last_used_at }
            .map { token ->
                mapOf(
                    "id" to token.id,
                    "type" to "browser",
                    "user_agent" to token.user_agent,
                    "created_at" to token.created_at?.toString(),
                    "last_used_at" to token.last_used_at?.toString(),
                    "expires_at" to token.expires_at?.toString(),
                    "is_current" to (token.token_hash == currentTokenHash)
                )
            }

        val deviceSessions = PairingService.getDeviceTokensForUser(user.id!!).map { token ->
            mapOf(
                "id" to token.id,
                "type" to "device",
                "device_name" to token.device_name,
                "created_at" to token.created_at?.toString(),
                "last_used_at" to token.last_used_at?.toString(),
                "is_current" to false
            )
        }

        return jsonResponse(gson.toJson(mapOf("sessions" to (browserSessions + deviceSessions))))
    }

    @Delete("/api/v2/profile/sessions/{sessionId}")
    fun revokeSession(ctx: ServiceRequestContext, @Param("sessionId") sessionId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val currentTokenHash = ctx.request().headers().cookies()
            .firstOrNull { it.name() == "mm_auth" }
            ?.value()
            ?.let { AuthService.hashToken(it) }

        val revoked = AuthService.revokeSession(sessionId, currentTokenHash)
        if (!revoked) {
            // Try device token
            PairingService.revokeToken(sessionId)
        }
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    @Post("/api/v2/profile/sessions/revoke-others")
    fun revokeOtherSessions(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val currentTokenHash = ctx.request().headers().cookies()
            .firstOrNull { it.name() == "mm_auth" }
            ?.value()
            ?.let { AuthService.hashToken(it) }

        AuthService.revokeAllSessionsExceptCurrent(user.id!!, currentTokenHash)
        PairingService.revokeAllForUser(user.id!!)
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    /** List titles the current user has personally hidden. */
    @Get("/api/v2/profile/hidden-titles")
    fun hiddenTitles(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val hiddenIds = UserTitleFlagService.getHiddenTitleIdsForUser(user.id!!)
        val titles = if (hiddenIds.isEmpty()) emptyList()
        else Title.findAll()
            .filter { it.id in hiddenIds }
            .sortedBy { it.name.lowercase() }
            .map { t ->
                mapOf(
                    "title_id" to t.id,
                    "title_name" to t.name,
                    "poster_url" to t.posterUrl(PosterSize.THUMBNAIL),
                    "release_year" to t.release_year
                )
            }

        return jsonResponse(gson.toJson(mapOf("titles" to titles)))
    }

    /** Unhide a title for the current user. */
    @Delete("/api/v2/profile/hidden-titles/{titleId}")
    fun unhideTitle(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        UserTitleFlagService.clearFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
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
