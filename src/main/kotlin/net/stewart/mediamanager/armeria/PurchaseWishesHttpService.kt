package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.service.WishLifecycleStage
import net.stewart.mediamanager.service.WishListService
import net.stewart.mediamanager.service.displayLabel

@Blocking
class PurchaseWishesHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/purchase-wishes")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val statusOrder = mapOf(
            WishLifecycleStage.WISHED_FOR to 0,
            WishLifecycleStage.ORDERED to 1,
            WishLifecycleStage.NEEDS_ASSISTANCE to 2,
            WishLifecycleStage.IN_HOUSE_PENDING_NAS to 3,
            WishLifecycleStage.ON_NAS_PENDING_DESKTOP to 4,
            WishLifecycleStage.READY_TO_WATCH to 5,
            WishLifecycleStage.NOT_FEASIBLE to 6,
            WishLifecycleStage.WONT_ORDER to 7
        )
        val mediaAggregates = WishListService.getMediaWishVoteCounts()
        val albumAggregates = WishListService.getAlbumWishVoteCounts()

        val mediaItems = mediaAggregates.map { agg ->
            mapOf(
                "wish_type" to "MEDIA",
                "tmdb_id" to agg.tmdbId,
                "title" to agg.tmdbTitle,
                "display_title" to agg.displayTitle,
                "media_type" to agg.tmdbMediaType,
                "poster_path" to agg.tmdbPosterPath,
                "release_year" to agg.tmdbReleaseYear,
                "season_number" to agg.seasonNumber,
                "vote_count" to agg.voteCount,
                "voters" to agg.voters,
                "lifecycle_stage" to agg.lifecycleStage.name,
                "lifecycle_label" to agg.lifecycleStage.displayLabel()
            )
        }
        val albumItems = albumAggregates.map { agg ->
            mapOf(
                "wish_type" to "ALBUM",
                "release_group_id" to agg.releaseGroupId,
                "title" to agg.title,
                "display_title" to agg.displayTitle,
                "primary_artist" to agg.primaryArtist,
                "is_compilation" to agg.isCompilation,
                "release_year" to agg.year,
                "cover_release_id" to agg.coverReleaseId,
                "vote_count" to agg.voteCount,
                "voters" to agg.voters,
                "lifecycle_stage" to agg.lifecycleStage.name,
                "lifecycle_label" to agg.lifecycleStage.displayLabel()
            )
        }

        // Sort: by lifecycle stage priority, then by display title alpha.
        // Common envelope across both wish types so the client renders
        // them in one table.
        val all = (mediaItems + albumItems).sortedWith(
            compareBy<Map<String, Any?>>(
                { statusOrder[WishLifecycleStage.valueOf(it["lifecycle_stage"] as String)] ?: 99 },
                { (it["display_title"] as String).lowercase() }
            )
        )
        return jsonResponse(gson.toJson(mapOf("wishes" to all, "total" to all.size)))
    }

    @Post("/api/v2/admin/purchase-wishes/set-status")
    fun setStatus(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val tmdbId = (map["tmdb_id"] as? Number)?.toInt() ?: return badRequest("tmdb_id required")
        val mediaType = map["media_type"] as? String ?: return badRequest("media_type required")
        val statusStr = map["status"] as? String ?: return badRequest("status required")
        val seasonNumber = (map["season_number"] as? Number)?.toInt()

        val status = try { AcquisitionStatus.valueOf(statusStr) } catch (_: Exception) {
            return badRequest("Invalid status")
        }

        // Find the aggregate to pass to setAcquisitionStatus
        val aggregates = WishListService.getMediaWishVoteCounts()
        val agg = aggregates.firstOrNull { a ->
            a.tmdbId == tmdbId && a.tmdbMediaType == mediaType && a.seasonNumber == seasonNumber
        } ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        WishListService.setAcquisitionStatus(agg, status)
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
