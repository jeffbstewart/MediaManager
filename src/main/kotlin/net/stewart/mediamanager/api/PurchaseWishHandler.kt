package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.WishListService
import org.slf4j.LoggerFactory

/**
 * Handles /api/v1/admin/purchase-wishes/... endpoints (admin-only):
 * - GET /purchase-wishes — aggregated media wishes with vote counts
 * - PUT /purchase-wishes/{wishId}/status — update acquisition status
 */
object PurchaseWishHandler {

    private val log = LoggerFactory.getLogger(PurchaseWishHandler::class.java)
    private val WISH_STATUS = Regex("(\\d+)/status")

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val method = req.method

        when {
            path.isEmpty() && method == "GET" -> handleList(resp, mapper)
            method == "PUT" -> {
                val match = WISH_STATUS.matchEntire(path)
                if (match != null) {
                    val wishId = match.groupValues[1].toLong()
                    handleUpdateStatus(req, resp, mapper, wishId)
                } else {
                    ApiV1Servlet.sendError(resp, 404, "not_found")
                    MetricsRegistry.countHttpResponse("api_v1", 404)
                }
            }
            else -> {
                ApiV1Servlet.sendError(resp, 404, "not_found")
                MetricsRegistry.countHttpResponse("api_v1", 404)
            }
        }
    }

    private fun handleList(resp: HttpServletResponse, mapper: ObjectMapper) {
        val aggregates = WishListService.getMediaWishVoteCounts()

        val items = aggregates.map { agg ->
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
                "acquisition_status" to agg.acquisitionStatus
            )
        }

        ApiV1Servlet.sendJson(resp, 200, mapOf("wishes" to items), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleUpdateStatus(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, wishId: Long) {
        val body = try { mapper.readTree(req.reader) } catch (_: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }

        val statusStr = body.get("status")?.asText()
        if (statusStr == null || AcquisitionStatus.entries.none { it.name == statusStr }) {
            ApiV1Servlet.sendError(resp, 400, "invalid_status",
                mapOf("valid" to AcquisitionStatus.entries.map { it.name }))
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        // The wishId here is the ID of any wish in the group. Find the TMDB identity from it,
        // then update the corresponding title_season acquisition status.
        val wish = WishListItem.findById(wishId)
        if (wish == null || wish.wish_type != WishType.MEDIA.name) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val tmdbKey = wish.tmdbKey()
        if (tmdbKey == null) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        // Find the title matching this TMDB ID
        val title = net.stewart.mediamanager.entity.Title.findAll().firstOrNull {
            it.tmdb_id == tmdbKey.id && it.media_type == tmdbKey.typeString
        }

        if (title != null) {
            val seasonNum = wish.season_number ?: 0
            val season = TitleSeason.findAll().firstOrNull {
                it.title_id == title.id && it.season_number == seasonNum
            }
            if (season != null) {
                season.acquisition_status = statusStr
                season.save()
                log.info("Acquisition status updated: title_id={} season={} status={}", title.id, seasonNum, statusStr)
            } else {
                // Create a title_season row if it doesn't exist
                TitleSeason(
                    title_id = title.id!!,
                    season_number = seasonNum,
                    acquisition_status = statusStr
                ).save()
                log.info("Acquisition status created: title_id={} season={} status={}", title.id, seasonNum, statusStr)
            }
        }

        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }
}
