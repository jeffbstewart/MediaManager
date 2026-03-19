package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.WishListService

/**
 * Handles /api/v1/wishlist/... endpoints:
 * - GET /wishlist — aggregated wishes with vote counts and user's vote status
 * - POST /wishlist — add a media wish
 * - DELETE /wishlist/{id} — cancel a wish
 * - POST /wishlist/{id}/vote — vote for an existing wish (same TMDB item)
 * - DELETE /wishlist/{id}/vote — remove vote
 */
object WishListHandler {

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val method = req.method

        when {
            path.isEmpty() && method == "GET" -> handleList(req, resp, mapper, user.id!!)
            path.isEmpty() && method == "POST" -> handleAdd(req, resp, mapper, user.id!!)
            path.matches(Regex("\\d+/vote")) && method == "POST" -> {
                val wishId = path.removeSuffix("/vote").toLong()
                handleVote(req, resp, mapper, user.id!!, wishId)
            }
            path.matches(Regex("\\d+/vote")) && method == "DELETE" -> {
                val wishId = path.removeSuffix("/vote").toLong()
                handleUnvote(req, resp, mapper, user.id!!, wishId)
            }
            path.matches(Regex("\\d+")) && method == "DELETE" -> {
                val wishId = path.toLong()
                handleCancel(req, resp, mapper, user.id!!, wishId)
            }
            else -> {
                ApiV1Servlet.sendError(resp, 404, "not_found")
                MetricsRegistry.countHttpResponse("api_v1", 404)
            }
        }
    }

    private fun handleList(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, userId: Long) {
        val aggregates = WishListService.getMediaWishVoteCounts()
        val userWishes = WishListItem.findAll().filter {
            it.user_id == userId &&
                it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.ACTIVE.name
        }
        val userTmdbKeys = userWishes.mapNotNull { it.tmdbKey() }.toSet()
        val userWishByKey = userWishes.associateBy { Pair(it.tmdbKey(), it.season_number) }

        val items = aggregates.map { agg ->
            val tmdbKey = agg.tmdbKey()
            val voted = tmdbKey != null && tmdbKey in userTmdbKeys
            val userWish = userWishByKey[Pair(tmdbKey, agg.seasonNumber)]
            val posterUrl = if (agg.tmdbPosterPath != null) "https://image.tmdb.org/t/p/w500${agg.tmdbPosterPath}" else null

            mapOf(
                "tmdb_id" to agg.tmdbId,
                "media_type" to agg.tmdbMediaType,
                "title" to agg.tmdbTitle,
                "poster_url" to posterUrl,
                "release_year" to agg.tmdbReleaseYear,
                "season_number" to agg.seasonNumber,
                "vote_count" to agg.voteCount,
                "voters" to agg.voters,
                "voted" to voted,
                "wish_id" to userWish?.id,
                "acquisition_status" to agg.acquisitionStatus
            )
        }

        ApiV1Servlet.sendJson(resp, 200, mapOf("wishes" to items), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleAdd(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, userId: Long) {
        val body = try {
            mapper.readTree(req.reader)
        } catch (e: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val tmdbIdVal = body.get("tmdb_id")?.asInt()
        val mediaType = body.get("media_type")?.asText()
        val title = body.get("title")?.asText()

        if (tmdbIdVal == null || mediaType.isNullOrBlank() || title.isNullOrBlank()) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val tmdbId = TmdbId.of(tmdbIdVal, mediaType)
        if (tmdbId == null) {
            ApiV1Servlet.sendError(resp, 400, "invalid_media_type")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val posterPath = body.get("poster_path")?.asText()
        val releaseYear = body.get("release_year")?.asInt()
        val popularity = body.get("popularity")?.asDouble()
        val seasonNumber = body.get("season_number")?.asInt()

        val wish = WishListService.addMediaWishForUser(userId, tmdbId, title, posterPath, releaseYear, popularity, seasonNumber)
        if (wish == null) {
            ApiV1Servlet.sendError(resp, 409, "already_wished")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }

        ApiV1Servlet.sendJson(resp, 201, mapOf("id" to wish.id, "created" to true), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 201)
    }

    private fun handleCancel(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, userId: Long, wishId: Long) {
        val success = WishListService.cancelWishForUser(wishId, userId)
        if (!success) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }
        ApiV1Servlet.sendJson(resp, 200, mapOf("cancelled" to true), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleVote(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, userId: Long, wishId: Long) {
        // Find the target wish to get its TMDB identity
        val targetWish = WishListItem.findById(wishId)
        if (targetWish == null || targetWish.wish_type != WishType.MEDIA.name || targetWish.status != WishStatus.ACTIVE.name) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val tmdbId = targetWish.tmdbKey()
        if (tmdbId == null) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        val wish = WishListService.addMediaWishForUser(
            userId, tmdbId,
            targetWish.tmdb_title ?: "",
            targetWish.tmdb_poster_path,
            targetWish.tmdb_release_year,
            targetWish.tmdb_popularity,
            targetWish.season_number
        )
        if (wish == null) {
            ApiV1Servlet.sendError(resp, 409, "already_voted")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }

        ApiV1Servlet.sendJson(resp, 201, mapOf("id" to wish.id, "voted" to true), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 201)
    }

    private fun handleUnvote(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, userId: Long, wishId: Long) {
        // Find the target wish to get its TMDB identity
        val targetWish = WishListItem.findById(wishId)
        if (targetWish == null || targetWish.wish_type != WishType.MEDIA.name) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val tmdbKey = targetWish.tmdbKey()
        if (tmdbKey == null) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        // Find and cancel the current user's wish for the same TMDB item
        val userWish = WishListItem.findAll().firstOrNull {
            it.user_id == userId &&
                it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.tmdb_id == tmdbKey.id &&
                it.tmdb_media_type == tmdbKey.typeString &&
                (targetWish.season_number == null || it.season_number == targetWish.season_number)
        }

        if (userWish == null) {
            ApiV1Servlet.sendError(resp, 404, "not_voted")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        userWish.status = WishStatus.CANCELLED.name
        userWish.save()
        ApiV1Servlet.sendJson(resp, 200, mapOf("unvoted" to true), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
