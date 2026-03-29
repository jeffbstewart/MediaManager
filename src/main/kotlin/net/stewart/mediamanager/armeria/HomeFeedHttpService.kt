package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.ContinueWatchingItem
import net.stewart.mediamanager.service.MissingSeasonService
import net.stewart.mediamanager.service.PlaybackProgressService

/**
 * REST endpoint for the Angular home page carousels.
 *
 * Returns all carousel data in a single request to avoid waterfall loading.
 * Behind [ArmeriaAuthDecorator] — user is on the request context.
 */
@Blocking
class HomeFeedHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/home")
    fun homeFeed(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val userId = user.id!!

        val continueWatching = PlaybackProgressService.getContinueWatchingForUser(userId, 5)
            .map { it.toJson() }

        val recentlyAdded = PlaybackProgressService.getRecentlyAddedForUser(user, 10)
            .map { (title, _) -> title.toCarouselJson() }

        val recentlyWatched = PlaybackProgressService.getRecentlyWatchedForUser(userId, 10)
            .map { it.toCarouselJson() }

        val missingSeasons = MissingSeasonService.getMissingSeasonsForUser(userId)
            .map { summary ->
                mapOf(
                    "title_id" to summary.titleId,
                    "title_name" to summary.titleName,
                    "poster_url" to summary.posterPath?.let { "/posters/w185/${summary.titleId}" },
                    "tmdb_id" to summary.tmdbId,
                    "tmdb_media_type" to summary.tmdbMediaType,
                    "missing_seasons" to summary.missingSeasons.map { s ->
                        mapOf("season_number" to s.season_number)
                    }
                )
            }

        val feed = mapOf(
            "continue_watching" to continueWatching,
            "recently_added" to recentlyAdded,
            "recently_watched" to recentlyWatched,
            "missing_seasons" to missingSeasons
        )

        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(feed))
            .build()
    }

    @Delete("/api/v2/playback-progress/{transcodeId}")
    fun clearProgress(ctx: ServiceRequestContext, @Param("transcodeId") transcodeId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        PlaybackProgressService.deleteProgressForUser(user.id!!, transcodeId)
        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("ok" to true)))
            .build()
    }

    @Post("/api/v2/catalog/dismiss-missing-seasons/{titleId}")
    fun dismissMissingSeasons(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        MissingSeasonService.dismissAllForTitle(user.id!!, titleId)
        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("ok" to true)))
            .build()
    }

    private fun ContinueWatchingItem.toJson(): Map<String, Any?> = mapOf(
        "transcode_id" to transcodeId,
        "title_id" to titleId,
        "title_name" to titleName,
        "poster_url" to posterUrl,
        "position_seconds" to positionSeconds,
        "duration_seconds" to durationSeconds,
        "progress_fraction" to progressFraction,
        "time_remaining" to timeRemaining,
        "is_episode" to isEpisode,
        "episode_label" to episodeLabel,
        "season_number" to seasonNumber,
        "episode_number" to episodeNumber,
        "episode_name" to episodeName
    )

    private fun Title.toCarouselJson(): Map<String, Any?> = mapOf(
        "title_id" to id,
        "title_name" to name,
        "poster_url" to posterUrl(PosterSize.THUMBNAIL),
        "release_year" to release_year
    )
}
