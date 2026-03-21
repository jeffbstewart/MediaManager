package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.service.MetricsRegistry

/**
 * Handles /api/v1/profile/... endpoints:
 * - GET /profile — current user's profile info
 * - PUT /profile/tv-quality — update live TV minimum quality preference
 */
object ProfileHandler {

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val method = req.method

        when {
            path.isEmpty() && method == "GET" -> handleGetProfile(resp, mapper, user)
            path == "tv-quality" && method == "PUT" -> handleSetTvQuality(req, resp, mapper, user)
            else -> {
                ApiV1Servlet.sendError(resp, 404, "not_found")
                MetricsRegistry.countHttpResponse("api_v1", 404)
            }
        }
    }

    private fun handleGetProfile(resp: HttpServletResponse, mapper: ObjectMapper, user: AppUser) {
        val ratingCeilingLabel = if (user.rating_ceiling != null) {
            ContentRating.entries.firstOrNull { it.ordinalLevel == user.rating_ceiling }?.displayLabel
        } else null

        val profile = mapOf(
            "id" to user.id,
            "username" to user.username,
            "display_name" to user.display_name,
            "is_admin" to user.isAdmin(),
            "rating_ceiling" to user.rating_ceiling,
            "rating_ceiling_label" to ratingCeilingLabel,
            "live_tv_min_quality" to user.live_tv_min_quality,
            "subtitles_enabled" to user.subtitles_enabled,
            "must_change_password" to user.must_change_password
        )

        ApiV1Servlet.sendJson(resp, 200, profile, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleSetTvQuality(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, user: AppUser) {
        val body = try {
            mapper.readTree(req.reader)
        } catch (e: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val minQuality = body.get("min_quality")?.asInt()
        if (minQuality == null || minQuality !in 1..5) {
            ApiV1Servlet.sendError(resp, 400, "invalid_quality", mapOf("detail" to "min_quality must be 1-5"))
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val fresh = AppUser.findById(user.id!!)
        if (fresh == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        fresh.live_tv_min_quality = minQuality
        fresh.save()

        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }
}
