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
import net.stewart.mediamanager.service.WishListService
import net.stewart.mediamanager.service.displayLabel

@Blocking
class PurchaseWishesHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/purchase-wishes")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val aggregates = WishListService.getMediaWishVoteCounts()
        val items = aggregates.map { agg ->
            mapOf(
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
        return jsonResponse(gson.toJson(mapOf("wishes" to items, "total" to items.size)))
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
