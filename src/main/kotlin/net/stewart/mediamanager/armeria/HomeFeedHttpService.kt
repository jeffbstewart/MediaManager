package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.DiscoveredFileStatus
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.ProblemReport
import net.stewart.mediamanager.entity.ReportStatus
import net.stewart.mediamanager.service.ContinueWatchingItem
import net.stewart.mediamanager.service.MissingSeasonService
import net.stewart.mediamanager.service.PairingService
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.ReadingProgressService
import net.stewart.mediamanager.service.WishListService

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

        val allTitlesForFeatures = Title.findAll()
        val hasBooks = allTitlesForFeatures.any { it.media_type == MMMediaType.BOOK.name }

        val recentlyAddedBooks = if (hasBooks) recentlyAddedBooks(user, 10) else emptyList()
        val resumeReading = if (hasBooks) resumeReading(user, 10) else emptyList()

        val hasMusic = allTitlesForFeatures.any { it.media_type == MMMediaType.ALBUM.name }
        val recentlyAddedAlbums = if (hasMusic) recentlyAddedAlbums(user, 10) else emptyList()

        val features = mapOf(
            "has_personal_videos" to allTitlesForFeatures.any { it.media_type == MMMediaType.PERSONAL.name },
            "has_books" to hasBooks,
            "has_music" to hasMusic,
            "has_cameras" to Camera.findAll().any { it.enabled },
            "has_live_tv" to LiveTvTuner.findAll().any { it.enabled },
            "is_admin" to user.isAdmin(),
            "wish_ready_count" to WishListService.getReadyToWatchWishCountForUser(user.id!!),
            "data_quality_count" to if (user.isAdmin()) {
                allTitlesForFeatures.count { it.enrichment_status != net.stewart.mediamanager.entity.EnrichmentStatus.ENRICHED.name && it.media_type != MMMediaType.PERSONAL.name && it.media_type != MMMediaType.BOOK.name }
            } else 0,
            "open_reports_count" to if (user.isAdmin()) {
                ProblemReport.findAll().count { it.status == ReportStatus.OPEN.name }
            } else 0
        )

        val feed = mapOf(
            "continue_watching" to continueWatching,
            "recently_added" to recentlyAdded,
            "recently_added_books" to recentlyAddedBooks,
            "recently_added_albums" to recentlyAddedAlbums,
            "resume_reading" to resumeReading,
            "recently_watched" to recentlyWatched,
            "missing_seasons" to missingSeasons,
            "features" to features
        )

        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(feed))
            .build()
    }

    /** Lightweight endpoint returning only feature flags for shell nav gating. */
    @Get("/api/v2/catalog/features")
    fun features(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val allTitles = Title.findAll()
        val unmatchedCount = if (user.isAdmin()) {
            DiscoveredFile.findAll().count { it.match_status == DiscoveredFileStatus.UNMATCHED.name }
        } else 0
        val unmatchedBooksCount = if (user.isAdmin()) {
            net.stewart.mediamanager.entity.UnmatchedBook.findAll().count {
                it.match_status == net.stewart.mediamanager.entity.UnmatchedBookStatus.UNMATCHED.name
            }
        } else 0
        val dataQualityCount = if (user.isAdmin()) {
            allTitles.count { it.enrichment_status != net.stewart.mediamanager.entity.EnrichmentStatus.ENRICHED.name && it.media_type != MMMediaType.PERSONAL.name }
        } else 0

        val features = mapOf(
            "has_personal_videos" to allTitles.any { it.media_type == MMMediaType.PERSONAL.name },
            "has_books" to allTitles.any { it.media_type == MMMediaType.BOOK.name },
            "has_music" to allTitles.any { it.media_type == MMMediaType.ALBUM.name },
            "has_cameras" to Camera.findAll().any { it.enabled },
            "has_live_tv" to LiveTvTuner.findAll().any { it.enabled },
            "is_admin" to user.isAdmin(),
            "wish_ready_count" to WishListService.getReadyToWatchWishCountForUser(user.id!!),
            "unmatched_count" to unmatchedCount,
            "unmatched_books_count" to unmatchedBooksCount,
            "data_quality_count" to dataQualityCount,
            "open_reports_count" to if (user.isAdmin()) {
                ProblemReport.findAll().count { it.status == ReportStatus.OPEN.name }
            } else 0
        )
        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(features))
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

    /** Check pairing code status for the confirm page. */
    @Get("/api/v2/pair/info")
    fun pairInfo(ctx: ServiceRequestContext, @Param("code") @Default("") code: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        if (code.isBlank()) {
            return HttpResponse.builder().status(HttpStatus.BAD_REQUEST)
                .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to "code required"))).build()
        }

        val status = PairingService.checkStatus(code)
        if (status == null) {
            return HttpResponse.builder().status(HttpStatus.OK)
                .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("status" to "expired"))).build()
        }

        val result = mapOf(
            "status" to status.status,
            "username" to status.username,
            "display_name" to user.display_name.ifEmpty { user.username }
        )
        return HttpResponse.builder().status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(result)).build()
    }

    /** Confirm a pairing code, linking the device to the current user. */
    @Post("/api/v2/pair/confirm")
    fun pairConfirm(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val code = map["code"] as? String
            ?: return HttpResponse.builder().status(HttpStatus.BAD_REQUEST)
                .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to "code required"))).build()

        val deviceName = PairingService.confirmPairing(code, user)
        if (deviceName == null) {
            return HttpResponse.builder().status(HttpStatus.OK)
                .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("ok" to false, "error" to "Invalid or expired code"))).build()
        }

        return HttpResponse.builder().status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("ok" to true, "device_name" to deviceName))).build()
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

    /**
     * "Recently added books" — books carousel on the home page, parallel to
     * [getRecentlyAddedForUser] on the movie/TV side. Sorted newest-first by
     * MediaItem.created_at, deduped by title (we own a book once regardless
     * of how many editions we have of it), respecting the user's rating
     * ceiling.
     */
    private fun recentlyAddedBooks(
        user: net.stewart.mediamanager.entity.AppUser,
        limit: Int
    ): List<Map<String, Any?>> {
        val allTitles = Title.findAll().associateBy { it.id }
        val allSeries = BookSeries.findAll().associateBy { it.id }
        val links = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val authorsByTitle = TitleAuthor.findAll()
            .sortedBy { it.author_order }
            .groupBy { it.title_id }
        val authors = Author.findAll().associateBy { it.id }

        val seen = mutableSetOf<Long>()
        val rows = mutableListOf<Map<String, Any?>>()
        val bookItems = MediaItem.findAll()
            .filter { it.media_format in BOOK_FORMAT_NAMES && it.created_at != null }
            .sortedByDescending { it.created_at }

        for (item in bookItems) {
            if (rows.size >= limit) break
            val titleIds = links[item.id]?.map { it.title_id }.orEmpty()
            for (titleId in titleIds) {
                if (titleId in seen) continue
                val title = allTitles[titleId] ?: continue
                if (title.media_type != MMMediaType.BOOK.name) continue
                if (title.hidden || !user.canSeeRating(title.content_rating)) continue
                seen += titleId

                val series = title.book_series_id?.let { allSeries[it] }
                val authorList = authorsByTitle[titleId]?.mapNotNull { authors[it.author_id]?.name }.orEmpty()

                rows += mapOf(
                    "title_id" to titleId,
                    "title_name" to title.name,
                    "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                    "release_year" to (title.first_publication_year ?: title.release_year),
                    "author_name" to authorList.firstOrNull(),
                    "series_name" to series?.name,
                    "series_number" to title.series_number?.toPlainString()
                )
                if (rows.size >= limit) break
            }
        }
        return rows
    }

    /**
     * Recently added albums, patterned on [recentlyAddedBooks]. Looks at
     * the newest physical + digital music MediaItems, joins to the linked
     * ALBUM Title, and surfaces artist + release year for the carousel.
     */
    private fun recentlyAddedAlbums(
        user: net.stewart.mediamanager.entity.AppUser,
        limit: Int
    ): List<Map<String, Any?>> {
        val allTitles = Title.findAll().associateBy { it.id }
        val links = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val artistsByTitle = net.stewart.mediamanager.entity.TitleArtist.findAll()
            .sortedBy { it.artist_order }
            .groupBy { it.title_id }
        val artists = net.stewart.mediamanager.entity.Artist.findAll().associateBy { it.id }

        val seen = mutableSetOf<Long>()
        val rows = mutableListOf<Map<String, Any?>>()
        val musicItems = MediaItem.findAll()
            .filter { it.media_format in MUSIC_FORMAT_NAMES && it.created_at != null }
            .sortedByDescending { it.created_at }

        for (item in musicItems) {
            if (rows.size >= limit) break
            val titleIds = links[item.id]?.map { it.title_id }.orEmpty()
            for (titleId in titleIds) {
                if (titleId in seen) continue
                val title = allTitles[titleId] ?: continue
                if (title.media_type != MMMediaType.ALBUM.name) continue
                if (title.hidden || !user.canSeeRating(title.content_rating)) continue
                seen += titleId

                val artistList = artistsByTitle[titleId]
                    ?.mapNotNull { artists[it.artist_id]?.name }
                    .orEmpty()

                rows += mapOf(
                    "title_id" to titleId,
                    "title_name" to title.name,
                    "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                    "release_year" to title.release_year,
                    "artist_name" to artistList.firstOrNull(),
                    "track_count" to title.track_count
                )
                if (rows.size >= limit) break
            }
        }
        return rows
    }

    /**
     * "Resume Reading" — most recent reading_progress rows, joined to the
     * title they're reading (via the media_item_id). Parallel to
     * Continue Watching on the video side. See docs/BOOKS.md (M5).
     */
    private fun resumeReading(
        user: net.stewart.mediamanager.entity.AppUser,
        limit: Int
    ): List<Map<String, Any?>> {
        val rows = ReadingProgressService.recentForUser(user.id!!, limit)
        if (rows.isEmpty()) return emptyList()

        val itemsById = MediaItem.findAll().associateBy { it.id }
        val allTitles = Title.findAll().associateBy { it.id }
        val linksByItem = MediaItemTitle.findAll().groupBy { it.media_item_id }

        return rows.mapNotNull { progress ->
            val item = itemsById[progress.media_item_id] ?: return@mapNotNull null
            val title = linksByItem[item.id]?.firstNotNullOfOrNull { allTitles[it.title_id] }
                ?: return@mapNotNull null
            if (title.hidden || !user.canSeeRating(title.content_rating)) return@mapNotNull null

            mapOf(
                "media_item_id" to item.id,
                "title_id" to title.id,
                "title_name" to title.name,
                "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                "percent" to progress.percent,
                "media_format" to item.media_format,
                "updated_at" to progress.updated_at?.toString()
            )
        }
    }

    companion object {
        /** Format enum names that represent a book edition. Mirrors MediaFormat.BOOK_FORMATS. */
        private val BOOK_FORMAT_NAMES: Set<String> = net.stewart.mediamanager.entity.MediaFormat
            .BOOK_FORMATS.mapTo(mutableSetOf()) { it.name }

        /** Format enum names that represent a music edition (CD / vinyl / digital audio). */
        private val MUSIC_FORMAT_NAMES: Set<String> =
            (net.stewart.mediamanager.entity.MediaFormat.PHYSICAL_MUSIC_FORMATS +
                net.stewart.mediamanager.entity.MediaFormat.DIGITAL_AUDIO_FORMATS)
                .mapTo(mutableSetOf()) { it.name }
    }
}
