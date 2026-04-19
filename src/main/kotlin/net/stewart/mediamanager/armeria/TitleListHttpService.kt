package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.TranscoderAgent
import com.github.vokorm.findAll

/**
 * Shared REST endpoint for browsing titles by media type.
 *
 * Used by Movies, TV Shows, and Family pages — all share the same poster grid
 * with filter/sort controls. The [media_type] parameter selects which titles
 * to return.
 *
 * Filtering (hidden, rating ceiling, per-user hidden) is applied server-side.
 * The response includes [available_ratings] so the client can render rating
 * filter chips without hardcoding which ratings exist.
 */
@Blocking
class TitleListHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/titles")
    fun listTitles(
        ctx: ServiceRequestContext,
        @Param("media_type") mediaType: String,
        @Param("sort") @Default("name") sort: String,
        @Param("ratings") @Default("") ratings: String,
        @Param("playable_only") @Default("true") playableOnly: Boolean
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val userId = user.id!!

        // Validate media_type
        val mmType = when (mediaType.uppercase()) {
            "MOVIE" -> MMMediaType.MOVIE
            "TV" -> MMMediaType.TV
            "PERSONAL" -> MMMediaType.PERSONAL
            "BOOK" -> MMMediaType.BOOK
            "ALBUM" -> MMMediaType.ALBUM
            else -> return HttpResponse.builder()
                .status(HttpStatus.BAD_REQUEST)
                .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to "Invalid media_type")))
                .build()
        }

        var titles = Title.findAll()
            .filter { it.media_type == mmType.name && !it.hidden }

        // Exclude per-user hidden titles
        val hiddenIds = UserTitleFlag.findAll()
            .filter { it.user_id == userId && it.flag == UserFlagType.HIDDEN.name }
            .map { it.title_id }
            .toSet()
        titles = titles.filter { it.id !in hiddenIds }

        // Rating ceiling
        titles = titles.filter { user.canSeeRating(it.content_rating) }

        // "Playable" = consumable right now through the web UI, per media type.
        //   VIDEO (MOVIE / TV / PERSONAL) — transcode exists and is browser-
        //     compatible or already transcoded to the ForBrowser mirror.
        //   BOOK — at least one MediaItem on the title has a digital
        //     file_path (.epub / .pdf) so the reader can open it.
        //   ALBUM — at least one Track on the title has a file_path.
        val playableTitleIds: Set<Long> = when (mmType) {
            MMMediaType.BOOK -> {
                val bookMediaItemIds = MediaItem.findAll()
                    .filter { !it.file_path.isNullOrBlank() }
                    .mapNotNull { it.id }
                    .toSet()
                MediaItemTitle.findAll()
                    .filter { it.media_item_id in bookMediaItemIds }
                    .map { it.title_id }
                    .toSet()
            }
            MMMediaType.ALBUM -> {
                Track.findAll()
                    .filter { !it.file_path.isNullOrBlank() }
                    .map { it.title_id }
                    .toSet()
            }
            else -> {
                val nasRoot = TranscoderAgent.getNasRoot()
                Transcode.findAll()
                    .filter { tc ->
                        val fp = tc.file_path
                        if (fp.isNullOrBlank()) false
                        else if (TranscoderAgent.needsTranscoding(fp))
                            nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
                        else true
                    }
                    .map { it.title_id }
                    .toSet()
            }
        }

        // Available rating values (before rating/playable filters, for chip display)
        val availableRatings = titles
            .let { if (playableOnly) it.filter { t -> t.id in playableTitleIds } else it }
            .mapNotNull { it.content_rating }
            .distinct()
            .sorted()

        // Rating filter (comma-separated)
        val ratingFilter = ratings.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (ratingFilter.isNotEmpty()) {
            titles = titles.filter { it.content_rating in ratingFilter }
        }

        if (playableOnly) {
            titles = titles.filter { it.id in playableTitleIds }
        }

        // Progress by title
        val progressByTitle = PlaybackProgressService.getProgressByTitleForUser(userId)

        // Sort
        titles = when (sort) {
            "year" -> titles.sortedByDescending { it.release_year ?: 0 }
            "recent" -> titles.sortedByDescending { it.created_at }
            "popular" -> titles.sortedByDescending { it.popularity ?: 0.0 }
            else -> titles.sortedBy { (it.sort_name ?: it.name).lowercase() }
        }

        val items = titles.map { title ->
            val progress = title.id?.let { progressByTitle[it] }
            val dur = progress?.duration_seconds
            val progressFraction = if (progress != null && dur != null && dur > 0.0) {
                (progress.position_seconds / dur).coerceIn(0.0, 1.0)
            } else null

            mapOf(
                "title_id" to title.id,
                "title_name" to title.name,
                "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                "release_year" to title.release_year,
                "content_rating" to title.content_rating,
                "playable" to (title.id in playableTitleIds),
                "progress_fraction" to progressFraction
            )
        }

        val response = mapOf(
            "titles" to items,
            "total" to items.size,
            "available_ratings" to availableRatings
        )

        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(response))
            .build()
    }
}
