package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.github.vokorm.findAll
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.RecordingCredit
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TrackArtist
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.TitleFamilyMember
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.SimilarTitlesService
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.mediamanager.service.UserTitleFlagService
import java.time.LocalDateTime

/**
 * REST endpoint for the title detail page.
 *
 * Returns all data needed to render a movie, TV show, or personal video
 * detail page in a single request. Behind [ArmeriaAuthDecorator].
 */
@Blocking
class TitleDetailHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/titles/{titleId}")
    fun titleDetail(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val userId = user.id!!

        val title = Title.findById(titleId)
            ?: return jsonError(HttpStatus.NOT_FOUND, "Title not found")

        // Rating enforcement
        if (!user.canSeeRating(title.content_rating)) {
            return jsonError(HttpStatus.FORBIDDEN, "Content restricted")
        }

        val isPersonal = title.media_type == MMMediaType.PERSONAL.name
        val isTv = title.media_type == MMMediaType.TV.name

        // User flags
        val userFlags = UserTitleFlag.findAll().filter { it.user_id == userId && it.title_id == titleId }
        val isStarred = userFlags.any { it.flag == UserFlagType.STARRED.name }
        val isHidden = userFlags.any { it.flag == UserFlagType.HIDDEN.name }

        // Genres
        val genres = if (!isPersonal) {
            val genreIds = TitleGenre.findAll().filter { it.title_id == titleId }.map { it.genre_id }.toSet()
            Genre.findAll().filter { it.id in genreIds }.map { it.name }
        } else emptyList()

        // Tags. Sort by IDF-style rarity — tags attached to the fewest
        // titles come first so the most discriminating chip (e.g. a
        // hand-made "mixtape" tag) is read before a noisy genre like
        // "Rock". Ties break alphabetically for determinism.
        val tagIds = TitleTag.findAll().filter { it.title_id == titleId }.map { it.tag_id }.toSet()
        val tagTitleCounts = net.stewart.mediamanager.service.TagService.getTagTitleCounts()
        val tags = Tag.findAll().filter { it.id in tagIds }
            .sortedWith(compareBy({ tagTitleCounts[it.id] ?: 0 }, { it.name.lowercase() }))
            .map { tag ->
                mapOf("id" to tag.id, "name" to tag.name, "bg_color" to tag.bg_color, "text_color" to tag.textColor())
            }

        // Formats from linked media items
        val mediaItemIds = MediaItemTitle.findAll().filter { it.title_id == titleId }.map { it.media_item_id }.toSet()
        val allLinkedItems = MediaItem.findAll().filter { it.id in mediaItemIds }
        val formats = allLinkedItems
            .map { it.media_format }
            .distinct()
            .filter { it != MediaFormat.UNKNOWN.name && it != MediaFormat.OTHER.name }
        // Admin-only list of the actual MediaItem rows so the title
        // page can link directly into the admin edit page for each
        // physical copy (paperback + hardcover of the same book, DVD
        // + Blu-ray of the same movie, etc.). Non-admin clients are
        // free to ignore this; the main badge strip comes from `formats`.
        val adminMediaItems = if (user.isAdmin()) {
            allLinkedItems.map {
                mapOf(
                    "media_item_id" to it.id,
                    "media_format" to it.media_format,
                    "upc" to it.upc
                )
            }
        } else emptyList()

        // Transcodes with playability
        val nasRoot = TranscoderAgent.getNasRoot()
        val transcodes = Transcode.findAll().filter { it.title_id == titleId && it.file_path != null }
        val progressMap = PlaybackProgressService.getProgressByTitleForUser(userId)
        val transcodeProgress = transcodes.associate { tc ->
            val progress = PlaybackProgressService.getProgressForUser(userId, tc.id!!)
            tc.id!! to progress
        }

        val transcodeList = transcodes.map { tc ->
            val fp = tc.file_path!!
            val needsTranscoding = TranscoderAgent.needsTranscoding(fp)
            val isTranscoded = if (needsTranscoding) {
                nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
            } else true
            val playable = isTranscoded
            val progress = transcodeProgress[tc.id]
            val episode = tc.episode_id?.let { Episode.findById(it) }

            val result = mutableMapOf<String, Any?>(
                "transcode_id" to tc.id,
                "file_name" to fp.substringAfterLast('/').substringAfterLast('\\'),
                "playable" to playable,
                "media_format" to (MediaItem.findAll()
                    .firstOrNull { it.id in mediaItemIds }?.media_format),
            )
            if (progress != null) {
                result["position_seconds"] = progress.position_seconds
                result["duration_seconds"] = progress.duration_seconds
            }
            if (episode != null) {
                result["season_number"] = episode.season_number
                result["episode_number"] = episode.episode_number
                result["episode_name"] = episode.name
            }
            result
        }

        // Cast (non-personal)
        val cast = if (!isPersonal) {
            CastMember.findAll()
                .filter { it.title_id == titleId }
                .sortedBy { it.cast_order }
                .map { cm ->
                    mapOf(
                        "id" to cm.id,
                        "name" to cm.name,
                        "character_name" to cm.character_name,
                        // Gate on profile_path (TMDB has a headshot for this person),
                        // not headshot_cache_id — the /headshots/{id} endpoint lazily
                        // populates the cache_id on first call, so gating on cache_id
                        // would prevent the endpoint from ever being called for any
                        // new cast member. Chicken-and-egg.
                        "headshot_url" to if (cm.profile_path != null) "/headshots/${cm.id}" else null,
                        "tmdb_person_id" to cm.tmdb_person_id
                    )
                }
        } else emptyList()

        // Episodes (TV)
        val episodes = if (isTv) {
            Episode.findAll()
                .filter { it.title_id == titleId }
                .sortedWith(compareBy({ it.season_number }, { it.episode_number }))
                .map { ep ->
                    val epTranscode = transcodes.firstOrNull { it.episode_id == ep.id }
                    val epProgress = epTranscode?.let { transcodeProgress[it.id] }
                    val epPlayable = epTranscode?.let { tc ->
                        val fp = tc.file_path!!
                        if (TranscoderAgent.needsTranscoding(fp)) {
                            nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
                        } else true
                    } ?: false

                    mapOf(
                        "id" to ep.id,
                        "season_number" to ep.season_number,
                        "episode_number" to ep.episode_number,
                        "name" to ep.name,
                        "transcode_id" to epTranscode?.id,
                        "playable" to epPlayable,
                        "position_seconds" to epProgress?.position_seconds,
                        "duration_seconds" to epProgress?.duration_seconds
                    )
                }
        } else emptyList()

        // Seasons (TV)
        val seasons = if (isTv) {
            TitleSeason.findAll()
                .filter { it.title_id == titleId && it.season_number > 0 }
                .sortedBy { it.season_number }
                .map { s ->
                    mapOf(
                        "season_number" to s.season_number,
                        "acquisition_status" to s.acquisition_status
                    )
                }
        } else emptyList()

        // Family members (personal)
        val familyMembers = if (isPersonal) {
            val fmtIds = TitleFamilyMember.findAll()
                .filter { it.title_id == titleId }
                .map { it.family_member_id }
                .toSet()
            FamilyMember.findAll()
                .filter { it.id in fmtIds }
                .map { fm -> mapOf("id" to fm.id, "name" to fm.name) }
        } else emptyList()

        // Similar titles (non-personal)
        val similarTitles = if (!isPersonal) {
            SimilarTitlesService.getSimilarTitlesForUser(title, userId, 12)
                .map { t ->
                    mapOf(
                        "title_id" to t.id,
                        "title_name" to t.name,
                        "poster_url" to t.posterUrl(PosterSize.THUMBNAIL),
                        "release_year" to t.release_year
                    )
                }
        } else emptyList()

        // Collection
        val collection = if (title.tmdb_collection_id != null) {
            val col = TmdbCollection.findAll().firstOrNull { it.tmdb_collection_id == title.tmdb_collection_id }
            if (col != null) {
                mapOf("id" to col.id, "name" to col.name)
            } else null
        } else null

        // Book-specific enrichment: authors, series link, physical metadata.
        // Only populated when media_type = BOOK; always null-or-empty on
        // movies / TV / personal so the client can treat them uniformly.
        val isBook = title.media_type == MMMediaType.BOOK.name
        val bookAuthors = if (isBook) {
            val orderedLinks = TitleAuthor.findAll()
                .filter { it.title_id == titleId }
                .sortedBy { it.author_order }
            val authorsById = Author.findAll()
                .filter { a -> orderedLinks.any { it.author_id == a.id } }
                .associateBy { it.id }
            orderedLinks.mapNotNull { link ->
                authorsById[link.author_id]?.let { a ->
                    mapOf("id" to a.id, "name" to a.name)
                }
            }
        } else emptyList()

        val bookSeries = if (isBook && title.book_series_id != null) {
            BookSeries.findById(title.book_series_id!!)?.let { s ->
                mapOf(
                    "id" to s.id,
                    "name" to s.name,
                    "number" to title.series_number
                )
            }
        } else null

        // Album-specific: artists (album-level), tracks with disc grouping,
        // per-track performer credits when they differ from the album-level
        // credit (compilation-shape). Empty for non-album titles.
        val isAlbum = title.media_type == MMMediaType.ALBUM.name
        val albumArtists = if (isAlbum) {
            val orderedLinks = TitleArtist.findAll()
                .filter { it.title_id == titleId }
                .sortedBy { it.artist_order }
            val artistsById = Artist.findAll()
                .filter { a -> orderedLinks.any { it.artist_id == a.id } }
                .associateBy { it.id }
            orderedLinks.mapNotNull { link ->
                artistsById[link.artist_id]?.let { a ->
                    mapOf(
                        "id" to a.id,
                        "name" to a.name,
                        "artist_type" to a.artist_type
                    )
                }
            }
        } else emptyList()

        val albumTracks = if (isAlbum) {
            val tracks = Track.findAll()
                .filter { it.title_id == titleId }
                .sortedWith(compareBy({ it.disc_number }, { it.track_number }))

            // Per-track artist credits are sparse (only populated for tracks
            // whose performer differs from the album-level credit) — one
            // query fetches all of them at once.
            val trackIds = tracks.mapNotNull { it.id }.toSet()
            val trackArtistLinks = if (trackIds.isEmpty()) emptyList()
                else TrackArtist.findAll().filter { it.track_id in trackIds }
            val perTrackArtistsById = Artist.findAll()
                .filter { a -> trackArtistLinks.any { it.artist_id == a.id } }
                .associateBy { it.id }

            // Per-track tags — surfaced so the album view can render a
            // chip next to each track row and the admin picker opens
            // already knowing which tags are attached.
            val trackTagLinks = if (trackIds.isEmpty()) emptyList()
                else net.stewart.mediamanager.entity.TrackTag.findAll()
                    .filter { it.track_id in trackIds }
            val tagsByTrack = trackTagLinks.groupBy { it.track_id }
            val tagsById = if (trackTagLinks.isEmpty()) emptyMap()
                else net.stewart.mediamanager.entity.Tag.findAll()
                    .filter { t -> trackTagLinks.any { it.tag_id == t.id } }
                    .associateBy { it.id }

            tracks.map { track ->
                val perTrack = trackArtistLinks
                    .filter { it.track_id == track.id }
                    .sortedBy { it.artist_order }
                    .mapNotNull { link ->
                        perTrackArtistsById[link.artist_id]?.let { a ->
                            mapOf("id" to a.id, "name" to a.name)
                        }
                    }
                // Track chips only surface what DIFFERS from the album
                // (an album-level "Jazz" badge implies every track on
                // it is Jazz; repeating the chip per row is pure noise).
                // Same IDF-rarity sort as the album chips so the scan
                // pattern is consistent.
                val perTrackTags = tagsByTrack[track.id].orEmpty()
                    .mapNotNull { tagsById[it.tag_id] }
                    .filter { it.id !in tagIds }
                    .sortedWith(compareBy({ tagTitleCounts[it.id] ?: 0 }, { it.name.lowercase() }))
                    .map { t ->
                        mapOf(
                            "id" to t.id,
                            "name" to t.name,
                            "bg_color" to t.bg_color,
                            "text_color" to t.textColor()
                        )
                    }
                mapOf(
                    "track_id" to track.id,
                    "disc_number" to track.disc_number,
                    "track_number" to track.track_number,
                    "name" to track.name,
                    "duration_seconds" to track.duration_seconds,
                    "track_artists" to perTrack,
                    "tags" to perTrackTags,
                    "bpm" to track.bpm,
                    "time_signature" to track.time_signature
                )
            }
        } else emptyList()

        // Digital editions for book titles — enumerated so the client can
        // render a "Read" button per edition. EPUB and PDF only; physical
        // editions don't belong here.
        val readableEditions = if (title.media_type == MMMediaType.BOOK.name) {
            MediaItem.findAll()
                .asSequence()
                .filter { it.id in mediaItemIds }
                .filter { it.file_path != null &&
                    (it.media_format == MediaFormat.EBOOK_EPUB.name ||
                     it.media_format == MediaFormat.EBOOK_PDF.name) }
                .map { item ->
                    val progress = net.stewart.mediamanager.service.ReadingProgressService.get(userId, item.id!!)
                    mapOf(
                        "media_item_id" to item.id,
                        "media_format" to item.media_format,
                        "percent" to (progress?.percent ?: 0.0),
                        "cfi" to progress?.cfi,
                        "updated_at" to progress?.updated_at?.toString()
                    )
                }
                .toList()
        } else emptyList()

        val response = mapOf(
            "title_id" to title.id,
            "title_name" to title.name,
            "media_type" to title.media_type,
            "release_year" to title.release_year,
            "description" to title.description,
            "content_rating" to title.content_rating,
            "poster_url" to title.posterUrl(PosterSize.FULL),
            "backdrop_url" to title.backdropUrl(),
            "event_date" to title.event_date?.toString(),
            "is_starred" to isStarred,
            "is_hidden" to isHidden,
            "genres" to genres,
            "tags" to tags,
            "formats" to formats,
            "admin_media_items" to adminMediaItems,
            "transcodes" to transcodeList,
            "readable_editions" to readableEditions,
            "cast" to cast,
            "episodes" to episodes,
            "seasons" to seasons,
            "family_members" to familyMembers,
            "similar_titles" to similarTitles,
            "collection" to collection,
            "authors" to bookAuthors,
            "book_series" to bookSeries,
            "page_count" to title.page_count,
            "first_publication_year" to title.first_publication_year,
            "open_library_work_id" to title.open_library_work_id,
            // Album-specific. Null/empty for non-album titles.
            "artists" to albumArtists,
            "tracks" to albumTracks,
            "track_count" to title.track_count,
            "total_duration_seconds" to title.total_duration_seconds,
            "label" to title.label,
            "musicbrainz_release_group_id" to title.musicbrainz_release_group_id,
            "musicbrainz_release_id" to title.musicbrainz_release_id,
            "personnel" to albumPersonnel(titleId, isAlbum)
        )

        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(response))
            .build()
    }

    /** Toggle star flag on a title for the current user. */
    @Post("/api/v2/catalog/titles/{titleId}/star")
    fun toggleStar(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        Title.findById(titleId) ?: return jsonError(HttpStatus.NOT_FOUND, "Title not found")

        val wasStarred = UserTitleFlagService.hasFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        if (wasStarred) UserTitleFlagService.clearFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        else UserTitleFlagService.setFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        val starred = !wasStarred
        return jsonOk(mapOf("is_starred" to starred))
    }

    /** Toggle hidden flag on a title for the current user. */
    @Post("/api/v2/catalog/titles/{titleId}/hide")
    fun toggleHide(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        Title.findById(titleId) ?: return jsonError(HttpStatus.NOT_FOUND, "Title not found")

        val wasHidden = UserTitleFlagService.hasFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        if (wasHidden) UserTitleFlagService.clearFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        else UserTitleFlagService.setFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        val hidden = !wasHidden
        return jsonOk(mapOf("is_hidden" to hidden))
    }

    /** Set tags for a title (admin only). Replaces all existing tags. */
    @Post("/api/v2/catalog/titles/{titleId}/tags")
    fun setTags(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        Title.findById(titleId) ?: return jsonError(HttpStatus.NOT_FOUND, "Title not found")

        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val tagIds = (body["tag_ids"] as? List<Number>)?.map { it.toLong() } ?: emptyList()

        // Remove existing tags
        TitleTag.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        // Add new tags
        for (tagId in tagIds) {
            TitleTag(title_id = titleId, tag_id = tagId).save()
        }
        SearchIndexService.onTitleChanged(titleId)

        return jsonOk(mapOf("ok" to true))
    }

    /**
     * Per-album personnel credits (M6), grouped by [CreditRole]. Empty
     * for non-album titles; also empty when MusicBrainz hasn't
     * documented personnel for this release yet. The client groups the
     * rendered output by role and collapses the section by default.
     */
    private fun albumPersonnel(titleId: Long, isAlbum: Boolean): List<Map<String, Any?>> {
        if (!isAlbum) return emptyList()
        val trackIds = Track.findAll()
            .asSequence()
            .filter { it.title_id == titleId }
            .mapNotNull { it.id }
            .toSet()
        if (trackIds.isEmpty()) return emptyList()

        val credits = RecordingCredit.findAll().filter { it.track_id in trackIds }
        if (credits.isEmpty()) return emptyList()

        val artists = net.stewart.mediamanager.entity.Artist.findAll()
            .filter { a -> credits.any { it.artist_id == a.id } }
            .associateBy { it.id }
        val tracksById = Track.findAll()
            .filter { it.id in trackIds }
            .associateBy { it.id }

        return credits
            .sortedWith(compareBy(
                { it.role },
                { tracksById[it.track_id]?.disc_number ?: 0 },
                { tracksById[it.track_id]?.track_number ?: 0 },
                { it.credit_order }
            ))
            .mapNotNull { credit ->
                val artist = artists[credit.artist_id] ?: return@mapNotNull null
                val track = tracksById[credit.track_id]
                mapOf(
                    "artist_id" to artist.id,
                    "artist_name" to artist.name,
                    "role" to credit.role,
                    "instrument" to credit.instrument,
                    "track_id" to credit.track_id,
                    "track_name" to track?.name,
                    "disc_number" to track?.disc_number,
                    "track_number" to track?.track_number
                )
            }
    }

    private fun jsonOk(data: Any): HttpResponse =
        HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(data))
            .build()

    private fun jsonError(status: HttpStatus, message: String): HttpResponse =
        HttpResponse.builder()
            .status(status)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to message)))
            .build()
}
