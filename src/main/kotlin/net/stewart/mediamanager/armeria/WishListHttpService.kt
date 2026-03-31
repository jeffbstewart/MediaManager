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
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import net.stewart.mediamanager.service.TmdbService
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.mediamanager.service.WishLifecycleStage
import net.stewart.mediamanager.service.WishListService
import net.stewart.mediamanager.service.displayLabel

/**
 * REST endpoints for the per-user wish list in the Angular web app.
 */
@Blocking
class WishListHttpService {

    private val gson = Gson()
    private val tmdbService = TmdbService()

    /** Returns the user's media wishes with lifecycle stage and transcode wishes with status. */
    @Get("/api/v2/wishlist")
    fun getWishList(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val userId = user.id!!

        // Media wishes
        val mediaSummaries = WishListService.getVisibleMediaWishSummariesForUser(userId)
        val mediaWishes = mediaSummaries.map { summary ->
            val wish = summary.wish
            mapOf(
                "id" to wish.id,
                "tmdb_id" to wish.tmdb_id,
                "tmdb_title" to wish.tmdb_title,
                "tmdb_media_type" to wish.tmdb_media_type,
                "tmdb_poster_path" to wish.tmdb_poster_path,
                "tmdb_release_year" to wish.tmdb_release_year,
                "season_number" to wish.season_number,
                "lifecycle_stage" to summary.lifecycleStage.name,
                "lifecycle_label" to summary.lifecycleStage.displayLabel(),
                "title_id" to summary.titleId,
                "vote_count" to summary.voteCount,
                "dismissible" to (summary.lifecycleStage == WishLifecycleStage.READY_TO_WATCH)
            )
        }

        // Transcode wishes
        val transcodeWishes = WishListService.getActiveTranscodeWishesForUser(userId)
        val titles = Title.findAll().associateBy { it.id }
        val allTranscodes = Transcode.findAll()
        val nasRoot = TranscoderAgent.getNasRoot()

        val transcodeItems = transcodeWishes.mapNotNull { wish ->
            val title = titles[wish.title_id] ?: return@mapNotNull null
            val titleTranscodes = allTranscodes.filter {
                it.title_id == title.id && it.file_path != null && TranscoderAgent.needsTranscoding(it.file_path!!)
            }
            val allTranscoded = nasRoot != null && titleTranscodes.isNotEmpty() &&
                titleTranscodes.all { TranscoderAgent.isTranscoded(nasRoot, it.file_path!!) }

            mapOf(
                "id" to wish.id,
                "title_id" to title.id,
                "title_name" to title.name,
                "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                "status" to if (allTranscoded) "ready" else "pending"
            )
        }

        val hasAnyMediaWish = WishListItem.findAll().any {
            it.user_id == userId && it.wish_type == WishType.MEDIA.name
        }

        return jsonResponse(gson.toJson(mapOf(
            "media_wishes" to mediaWishes,
            "transcode_wishes" to transcodeItems,
            "has_any_media_wish" to hasAnyMediaWish
        )))
    }

    /** Search TMDB for movies and TV shows. */
    @Get("/api/v2/wishlist/search")
    fun search(ctx: ServiceRequestContext, @Param("q") query: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val movieResults = tmdbService.searchMovieMultiple(query.trim(), 5)
        val tvResults = tmdbService.searchTvMultiple(query.trim(), 5)
        val all = (movieResults + tvResults)
            .sortedByDescending { it.popularity ?: 0.0 }
            .take(10)

        val wishedKeys = WishListItem.findAll()
            .filter { it.user_id == user.id && it.wish_type == WishType.MEDIA.name && it.status == WishStatus.ACTIVE.name }
            .mapNotNull { it.tmdbKey() }
            .toSet()

        val results = all.map { r ->
            val key = r.tmdbKey()
            mapOf(
                "tmdb_id" to r.tmdbId,
                "title" to r.title,
                "media_type" to r.mediaType,
                "poster_path" to r.posterPath,
                "release_year" to r.releaseYear,
                "popularity" to r.popularity,
                "already_wished" to (key != null && key in wishedKeys)
            )
        }

        return jsonResponse(gson.toJson(mapOf("results" to results)))
    }

    /** Add a media wish. */
    @Post("/api/v2/wishlist/add")
    fun addWish(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val tmdbId = (map["tmdb_id"] as? Number)?.toInt() ?: return badRequest("tmdb_id required")
        val mediaType = map["media_type"] as? String ?: return badRequest("media_type required")
        val title = map["title"] as? String ?: return badRequest("title required")
        val posterPath = map["poster_path"] as? String
        val releaseYear = (map["release_year"] as? Number)?.toInt()
        val popularity = (map["popularity"] as? Number)?.toDouble()

        val tmdbKey = TmdbId.of(tmdbId, mediaType) ?: return badRequest("Invalid media_type")

        val wish = WishListService.addMediaWishForUser(user.id!!, tmdbKey, title, posterPath, releaseYear, popularity)
        return if (wish != null) {
            jsonResponse(gson.toJson(mapOf("ok" to true, "id" to wish.id)))
        } else {
            jsonResponse(gson.toJson(mapOf("ok" to false, "reason" to "Already on wish list")))
        }
    }

    /** Cancel a wish (user changed their mind). */
    @Delete("/api/v2/wishlist/{wishId}")
    fun cancelWish(ctx: ServiceRequestContext, @Param("wishId") wishId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        WishListService.cancelWishForUser(wishId, user.id!!)
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    /** Dismiss a fulfilled wish. */
    @Post("/api/v2/wishlist/{wishId}/dismiss")
    fun dismissWish(ctx: ServiceRequestContext, @Param("wishId") wishId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val wish = WishListItem.findById(wishId)
        if (wish == null || wish.user_id != user.id) return HttpResponse.of(HttpStatus.NOT_FOUND)
        wish.status = WishStatus.DISMISSED.name
        wish.save()
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    /** Add a transcode wish (request a title be ripped). */
    @Post("/api/v2/wishlist/transcode/{titleId}")
    fun addTranscodeWish(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val wish = WishListService.addTranscodeWishForUser(user.id!!, titleId)
        return if (wish != null) {
            jsonResponse(gson.toJson(mapOf("ok" to true, "id" to wish.id)))
        } else {
            jsonResponse(gson.toJson(mapOf("ok" to false, "reason" to "Already on wish list")))
        }
    }

    /** Remove a transcode wish. */
    @Delete("/api/v2/wishlist/transcode/{wishId}")
    fun removeTranscodeWish(ctx: ServiceRequestContext, @Param("wishId") wishId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val wish = WishListItem.findById(wishId)
        if (wish == null || wish.user_id != user.id) return HttpResponse.of(HttpStatus.NOT_FOUND)
        wish.delete()
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

    private fun badRequest(message: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to message)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
