package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.TmdbSearchResult
import net.stewart.mediamanager.service.TmdbService

/**
 * Handles GET /api/v1/tmdb/search?q=...&type=movie|tv|all
 *
 * Proxies TMDB search so clients don't need the TMDB API key.
 * Serves multiple use cases: wish list additions, UPC-to-title linking,
 * transcode-to-title linking.
 *
 * Content rating filtering: results whose TMDB content rating exceeds the
 * authenticated user's rating_ceiling are excluded, consistent with catalog
 * visibility rules. Admin users and users with no ceiling see everything.
 *
 * Returns up to 10 results with poster URLs, release years, and popularity.
 */
object TmdbSearchHandler {

    private val tmdbService = TmdbService()

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val query = req.getParameter("q")?.trim()
        if (query.isNullOrEmpty()) {
            ApiV1Servlet.sendJson(resp, 200, mapOf("results" to emptyList<Any>()), mapper)
            MetricsRegistry.countHttpResponse("api_v1", 200)
            return
        }

        val type = req.getParameter("type") ?: "all"
        val maxResults = 10

        val results = when (type) {
            "movie" -> tmdbService.searchMovieMultiple(query, maxResults)
            "tv" -> tmdbService.searchTvMultiple(query, maxResults)
            else -> {
                val movies = tmdbService.searchMovieMultiple(query, maxResults)
                val tv = tmdbService.searchTvMultiple(query, maxResults)
                (movies + tv).sortedByDescending { it.popularity ?: 0.0 }.take(maxResults)
            }
        }

        val apiResults = results
            .filter { it.found }
            .filter { user.canSeeRating(it.contentRating) }
            .map { r ->
                val posterUrl = if (r.posterPath != null) "https://image.tmdb.org/t/p/w500${r.posterPath}" else null
                mapOf(
                    "tmdb_id" to r.tmdbId,
                    "title" to r.title,
                    "media_type" to r.mediaType,
                    "release_year" to r.releaseYear,
                    "poster_url" to posterUrl,
                    "poster_path" to r.posterPath,
                    "popularity" to r.popularity,
                    "overview" to r.overview,
                    "content_rating" to r.contentRating
                )
            }

        ApiV1Servlet.sendJson(resp, 200, mapOf("results" to apiResults), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
