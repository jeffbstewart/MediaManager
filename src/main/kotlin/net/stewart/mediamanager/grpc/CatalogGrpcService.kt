package net.stewart.mediamanager.grpc

import com.github.vokorm.findAll
import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.TitleFamilyMember
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.TmdbCollectionPart
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.entity.Transcode as TranscodeEntity
import net.stewart.mediamanager.entity.CastMember as CastMemberEntity
import net.stewart.mediamanager.entity.Episode as EpisodeEntity
import net.stewart.mediamanager.entity.Genre as GenreEntity
import net.stewart.mediamanager.entity.Tag as TagEntity
import net.stewart.mediamanager.entity.PlaybackProgress as ProgressEntity
import net.stewart.mediamanager.entity.MediaType as MediaTypeEnum
import net.stewart.mediamanager.entity.MediaFormat as MediaFormatEnum
import net.stewart.mediamanager.entity.EnrichmentStatus as EnrichmentStatusEnum
import net.stewart.mediamanager.entity.WishType as WishTypeEnum
import net.stewart.mediamanager.entity.WishStatus as WishStatusEnum
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.service.MissingSeasonService
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TmdbService
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.mediamanager.service.UserTitleFlagService
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * gRPC service porting CatalogHandler, BrowseHandler, and TvHandler business logic.
 */
class CatalogGrpcService : CatalogServiceGrpcKt.CatalogServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(CatalogGrpcService::class.java)
    private val tmdbService = TmdbService()

    companion object {
        private const val MAX_CAROUSEL_ITEMS = 25
        private const val DEFAULT_PAGE_LIMIT = 25
        private const val MAX_PAGE_LIMIT = 100
    }

    // ========================================================================
    // Shared catalog loading
    // ========================================================================

    private data class CatalogData(
        val allTitles: List<TitleEntity>,
        val titlesById: Map<Long?, TitleEntity>,
        val playableTitles: List<TitleEntity>,
        val playableTranscodes: List<TranscodeEntity>,
        val playableByTitle: Map<Long, List<TranscodeEntity>>,
        val episodesById: Map<Long, EpisodeEntity>
    )

    private fun loadCatalog(user: AppUser, nasRoot: String?): CatalogData {
        val allTitles = TitleEntity.findAll()
            .filter { !it.hidden }
            .filter { it.enrichment_status == EnrichmentStatusEnum.ENRICHED.name || it.media_type == MediaTypeEnum.PERSONAL.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesById = allTitles.associateBy { it.id }

        val allTranscodes = TranscodeEntity.findAll().filter { it.file_path != null }
        val playableTranscodes = allTranscodes.filter { isPlayable(it, nasRoot) }
        val playableByTitle = playableTranscodes.groupBy { it.title_id }

        val playableTitles = allTitles.filter { title ->
            val titleTranscodes = playableByTitle[title.id] ?: return@filter false
            if (title.media_type == MediaTypeEnum.TV.name) {
                titleTranscodes.any { it.episode_id != null }
            } else {
                true
            }
        }

        val episodesById = EpisodeEntity.findAll().associateBy { it.id!! }

        return CatalogData(allTitles, titlesById, playableTitles, playableTranscodes, playableByTitle, episodesById)
    }

    private fun loadFamilyMemberNames(): Map<Long, List<String>> {
        val members = FamilyMember.findAll().associateBy { it.id }
        val links = TitleFamilyMember.findAll()
        return links.groupBy { it.title_id }.mapValues { (_, tfms) ->
            tfms.mapNotNull { members[it.family_member_id]?.name }
        }
    }

    // ========================================================================
    // Home Feed
    // ========================================================================

    override suspend fun homeFeed(request: Empty): HomeFeedResponse {
        val user = currentUser()
        val nasRoot = TranscoderAgent.getNasRoot()
        val catalog = loadCatalog(user, nasRoot)
        val familyNames = loadFamilyMemberNames()

        val carousels = mutableListOf<Carousel>()

        // 1. Resume Playing
        val allProgress = ProgressEntity.findAll().filter { it.user_id == user.id }
        val progressByTranscode = allProgress.associate { it.transcode_id to it }
        val resumeItems = mutableListOf<Pair<TitleEntity, TranscodeEntity>>()
        val resumeProgress = mutableListOf<ProgressEntity>()
        for (title in catalog.playableTitles) {
            val titleTranscodes = catalog.playableByTitle[title.id] ?: continue
            for (tc in titleTranscodes) {
                val progress = progressByTranscode[tc.id]
                if (progress != null && progress.position_seconds > 0) {
                    resumeItems.add(title to tc)
                    resumeProgress.add(progress)
                    break
                }
            }
        }
        if (resumeItems.isNotEmpty()) {
            val indices = resumeItems.indices.sortedByDescending { resumeProgress[it].updated_at }
                .take(MAX_CAROUSEL_ITEMS)
            carousels.add(carousel {
                name = "Resume Playing"
                items.addAll(indices.map { i ->
                    val (titleEntity, transcodeEntity) = resumeItems[i]
                    val progress = resumeProgress[i]
                    val base = titleEntity.toProto(transcodeEntity, nasRoot, familyNames[titleEntity.id])
                    // Augment with resume progress and episode info
                    base.toBuilder().apply {
                        resumePosition = progress.position_seconds.toPlaybackOffset()
                        progress.duration_seconds?.let { resumeDuration = it.toPlaybackOffset() }
                        // For TV episodes, add season/episode context
                        transcodeEntity.episode_id?.let { epId ->
                            val episode = catalog.episodesById[epId]
                            if (episode != null) {
                                resumeSeasonNumber = episode.season_number
                                resumeEpisodeNumber = episode.episode_number
                                episode.name?.let { resumeEpisodeName = it }
                            }
                        }
                    }.build()
                })
            })
        }

        // 2. Recently Added
        val seenTitleIds = mutableSetOf<Long>()
        val recentItems = mutableListOf<Title>()
        val playableTitleIds = catalog.playableTitles.mapNotNull { it.id }.toSet()
        for (tc in catalog.playableTranscodes.sortedByDescending { it.created_at }) {
            if (recentItems.size >= MAX_CAROUSEL_ITEMS) break
            val title = catalog.titlesById[tc.title_id] ?: continue
            if (title.id!! !in playableTitleIds) continue
            if (title.id!! in seenTitleIds) continue
            seenTitleIds.add(title.id!!)
            recentItems.add(title.toProto(tc, nasRoot, familyNames[title.id]))
        }
        if (recentItems.isNotEmpty()) {
            carousels.add(carousel {
                name = "Recently Added"
                items.addAll(recentItems)
            })
        }

        // 3. Movies by popularity
        val movieItems = catalog.playableTitles
            .filter { it.media_type == MediaTypeEnum.MOVIE.name }
            .sortedByDescending { it.popularity ?: 0.0 }
            .take(MAX_CAROUSEL_ITEMS)
            .map { it.toProto(catalog.playableByTitle[it.id]?.firstOrNull(), nasRoot) }
        if (movieItems.isNotEmpty()) {
            carousels.add(carousel {
                name = "Movies"
                items.addAll(movieItems)
            })
        }

        // 4. TV Series by popularity
        val tvItems = catalog.playableTitles
            .filter { it.media_type == MediaTypeEnum.TV.name }
            .sortedByDescending { it.popularity ?: 0.0 }
            .take(MAX_CAROUSEL_ITEMS)
            .map { it.toProto(catalog.playableByTitle[it.id]?.firstOrNull(), nasRoot) }
        if (tvItems.isNotEmpty()) {
            carousels.add(carousel {
                name = "TV Series"
                items.addAll(tvItems)
            })
        }

        // 5. Family / Personal Videos by event date
        val familyItems = catalog.playableTitles
            .filter { it.media_type == MediaTypeEnum.PERSONAL.name }
            .sortedByDescending { it.event_date }
            .take(MAX_CAROUSEL_ITEMS)
            .map { it.toProto(catalog.playableByTitle[it.id]?.firstOrNull(), nasRoot, familyNames[it.id]) }
        if (familyItems.isNotEmpty()) {
            carousels.add(carousel {
                name = "Family"
                items.addAll(familyItems)
            })
        }

        // Missing seasons
        val missingSeasonsData = MissingSeasonService.getMissingSeasonsForUser(user.id!!)
        val missingSeasons = missingSeasonsData.map { ms ->
            missingSeason {
                titleId = ms.titleId
                titleName = ms.titleName
                ms.posterPath?.let { posterUrl = "/posters/w500/${ms.titleId}" }
                ms.tmdbId?.let { tmdbId = it }
                mediaType = ms.tmdbMediaType.toProtoMediaType()
                seasons.addAll(ms.missingSeasons.map { s ->
                    missingSeasonEntry {
                        seasonNumber = s.season_number
                        s.name?.let { name = it }
                        s.episode_count?.let { episodeCount = it }
                    }
                })
            }
        }

        return homeFeedResponse {
            this.carousels.addAll(carousels)
            this.missingSeasons.addAll(missingSeasons)
        }
    }

    // ========================================================================
    // Dismiss Continue Watching
    // ========================================================================

    override suspend fun dismissContinueWatching(request: TitleIdRequest): Empty {
        val user = currentUser()
        val transcodeIds = TranscodeEntity.findAll()
            .filter { it.title_id == request.titleId }
            .mapNotNull { it.id }
            .toSet()
        ProgressEntity.findAll()
            .filter { it.user_id == user.id && it.transcode_id in transcodeIds }
            .forEach { it.delete() }
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // Dismiss Missing Season
    // ========================================================================

    override suspend fun dismissMissingSeason(request: DismissMissingSeasonRequest): Empty {
        val user = currentUser()
        if (request.hasSeasonNumber()) {
            MissingSeasonService.dismiss(user.id!!, request.titleId, request.seasonNumber)
        } else {
            MissingSeasonService.dismissAllForTitle(user.id!!, request.titleId)
        }
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // List Titles (paginated, filterable)
    // ========================================================================

    override suspend fun listTitles(request: ListTitlesRequest): TitlePageResponse {
        val user = currentUser()
        val nasRoot = TranscoderAgent.getNasRoot()
        val catalog = loadCatalog(user, nasRoot)

        val page = request.page.coerceAtLeast(1)
        val limit = if (request.limit <= 0) DEFAULT_PAGE_LIMIT else request.limit.coerceIn(1, MAX_PAGE_LIMIT)
        val sort = if (request.hasSort()) request.sort else "popularity"
        val typeFilter = if (request.type != MediaType.MEDIA_TYPE_UNKNOWN) request.type else null
        val genreFilter = if (request.hasGenre()) request.genre else null
        val tagFilter = if (request.hasTag()) request.tag else null
        val query = if (request.hasQ()) request.q else null
        val playableOnly = request.playableOnly

        val playableTitleIds = catalog.playableTitles.mapNotNull { it.id }.toSet()

        var titles = catalog.allTitles.toList()

        // Search filter
        if (!query.isNullOrBlank()) {
            val matchIds = SearchIndexService.search(query)
            if (matchIds != null) {
                val matchSet = matchIds.toSet()
                titles = titles.filter { it.id in matchSet }
            }
        }

        // Type filter
        if (typeFilter != null) {
            val typeStr = when (typeFilter) {
                MediaType.MEDIA_TYPE_MOVIE -> MediaTypeEnum.MOVIE.name
                MediaType.MEDIA_TYPE_TV -> MediaTypeEnum.TV.name
                MediaType.MEDIA_TYPE_PERSONAL -> MediaTypeEnum.PERSONAL.name
                else -> null
            }
            if (typeStr != null) {
                titles = titles.filter { it.media_type == typeStr }
            }
        }

        // Genre filter
        if (genreFilter != null) {
            val genres = GenreEntity.findAll()
            val genreId = genres.firstOrNull { it.name.equals(genreFilter, ignoreCase = true) }?.id
            if (genreId != null) {
                val genreTitleIds = TitleGenre.findAll().filter { it.genre_id == genreId }.map { it.title_id }.toSet()
                titles = titles.filter { it.id in genreTitleIds }
            } else {
                titles = emptyList()
            }
        }

        // Tag filter
        if (tagFilter != null) {
            val tags = TagEntity.findAll()
            val tagId = tags.firstOrNull { it.name.equals(tagFilter, ignoreCase = true) }?.id
            if (tagId != null) {
                val tagTitleIds = TitleTag.findAll().filter { it.tag_id == tagId }.map { it.title_id }.toSet()
                titles = titles.filter { it.id in tagTitleIds }
            } else {
                titles = emptyList()
            }
        }

        // Playable filter
        if (playableOnly) {
            titles = titles.filter { it.id in playableTitleIds }
        }

        // Sort
        titles = when (sort) {
            "name" -> titles.sortedBy { it.sort_name ?: it.name.lowercase() }
            "year" -> titles.sortedByDescending { it.release_year ?: 0 }
            "recent" -> titles.sortedByDescending { it.created_at }
            else -> titles.sortedByDescending { it.popularity ?: 0.0 }
        }

        val total = titles.size
        val totalPages = if (total == 0) 0 else (total + limit - 1) / limit
        val offset = (page - 1) * limit
        val pageTitles = titles.drop(offset).take(limit)

        val familyNames = loadFamilyMemberNames()
        val protoTitles = pageTitles.map { title ->
            val tc = catalog.playableByTitle[title.id]?.firstOrNull()
            title.toProto(tc, nasRoot, familyNames[title.id])
        }

        return titlePageResponse {
            this.titles.addAll(protoTitles)
            pagination = paginationInfo {
                this.total = total
                this.page = page
                this.limit = limit
                this.totalPages = totalPages
            }
        }
    }

    // ========================================================================
    // Title Detail
    // ========================================================================

    override suspend fun getTitleDetail(request: TitleIdRequest): TitleDetail {
        val user = currentUser()
        val titleId = request.titleId
        val titleEntity = TitleEntity.findById(titleId)
        if (titleEntity == null || titleEntity.hidden || !user.canSeeRating(titleEntity.content_rating)) {
            throw StatusException(Status.NOT_FOUND)
        }

        val nasRoot = TranscoderAgent.getNasRoot()

        // Cast
        val castMembers = CastMemberEntity.findAll()
            .filter { it.title_id == titleId }
            .sortedBy { it.cast_order }

        // Genres
        val titleGenreIds = TitleGenre.findAll().filter { it.title_id == titleId }.map { it.genre_id }.toSet()
        val genres = GenreEntity.findAll().filter { it.id in titleGenreIds }

        // Tags
        val titleTagIds = TitleTag.findAll().filter { it.title_id == titleId }.map { it.tag_id }.toSet()
        val tags = TagEntity.findAll().filter { it.id in titleTagIds }

        // Transcodes with episode info
        val allTranscodes = TranscodeEntity.findAll().filter { it.title_id == titleId && it.file_path != null }
        val episodes = if (titleEntity.media_type == MediaTypeEnum.TV.name) {
            EpisodeEntity.findAll().filter { it.title_id == titleId }.associateBy { it.id }
        } else emptyMap()

        val protoTranscodes = allTranscodes.map { tc ->
            val episode = tc.episode_id?.let { episodes[it] }
            tc.toProto(episode, nasRoot)
        }.sortedWith(compareBy(
            { it.seasonNumber.let { sn -> if (sn == 0 && !it.hasSeasonNumber()) Int.MAX_VALUE else sn } },
            { it.episodeNumber.let { en -> if (en == 0 && !it.hasEpisodeNumber()) Int.MAX_VALUE else en } }
        ))

        // Playback progress for first playable transcode
        val firstPlayable = protoTranscodes.firstOrNull { it.playable }
        val progress = if (firstPlayable != null) {
            ProgressEntity.findAll()
                .firstOrNull { it.user_id == user.id && it.transcode_id == firstPlayable.id }
                ?.toProto()
        } else null

        val anyPlayable = protoTranscodes.any { it.playable }
        val bestTc = allTranscodes.firstOrNull { isPlayable(it, nasRoot) }

        // User-specific flags
        val isFavorite = UserTitleFlagService.hasFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        val isHidden = UserTitleFlagService.hasFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)

        // Wish list status
        val wished = if (titleEntity.tmdb_id != null) {
            WishListItem.findAll().any {
                it.wish_type == WishTypeEnum.MEDIA.name &&
                    it.status == WishStatusEnum.ACTIVE.name &&
                    it.tmdb_id == titleEntity.tmdb_id &&
                    it.tmdb_media_type == titleEntity.media_type
            }
        } else false

        val familyNames = loadFamilyMemberNames()

        val albumDetail = if (titleEntity.media_type == MediaTypeEnum.ALBUM.name) {
            buildAlbumDetail(titleEntity)
        } else null

        val bookDetail = if (titleEntity.media_type == MediaTypeEnum.BOOK.name) {
            buildBookDetail(user.id!!, titleEntity)
        } else null

        return titleDetail {
            title = titleEntity.toProto(bestTc, nasRoot, familyNames[titleEntity.id])
            // Override playable on the title to reflect all transcodes, not just best
            title = title.toBuilder().setPlayable(anyPlayable).build()
            cast.addAll(castMembers.map { it.toProto() })
            this.genres.addAll(genres.map { it.toProto() })
            this.tags.addAll(tags.map { it.toProto() })
            transcodes.addAll(protoTranscodes)
            progress?.let { playbackProgress = it }
            this.isFavorite = isFavorite
            this.isHidden = isHidden
            this.wished = wished
            albumDetail?.let { album = it }
            bookDetail?.let { book = it }
        }
    }

    private fun buildAlbumDetail(title: TitleEntity): Album {
        val titleId = title.id!!
        val artistLinks = TitleArtist.findAll()
            .filter { it.title_id == titleId }
            .sortedBy { it.artist_order }
        val artistIds = artistLinks.map { it.artist_id }.toSet()
        val artists = Artist.findAll().filter { it.id in artistIds }
        val artistsById = artists.associateBy { it.id }

        val tracks = Track.findAll()
            .filter { it.title_id == titleId }
            .sortedWith(compareBy({ it.disc_number }, { it.track_number }))

        val protoTitle = title.toProto()
        return album {
            this.title = protoTitle
            albumArtists.addAll(artistLinks.mapNotNull { artistsById[it.artist_id]?.toProto() })
            this.tracks.addAll(tracks.map { it.toProto() })
            title.track_count?.let { trackCount = it }
            title.total_duration_seconds?.let { totalDuration = it.toDouble().toPlaybackOffset() }
            title.label?.takeIf { it.isNotBlank() }?.let { label = it }
            title.musicbrainz_release_group_id?.takeIf { it.isNotBlank() }?.let { musicbrainzReleaseGroupId = it }
            title.musicbrainz_release_id?.takeIf { it.isNotBlank() }?.let { musicbrainzReleaseId = it }
        }
    }

    private fun buildBookDetail(userId: Long, title: TitleEntity): BookDetail {
        val titleId = title.id!!
        val authorLinks = TitleAuthor.findAll()
            .filter { it.title_id == titleId }
            .sortedBy { it.author_order }
        val authorIds = authorLinks.map { it.author_id }.toSet()
        val authors = Author.findAll().filter { it.id in authorIds }
        val authorsById = authors.associateBy { it.id }

        val mediaItemIds = MediaItemTitle.findAll()
            .filter { it.title_id == titleId }
            .map { it.media_item_id }
            .toSet()
        val editions = net.stewart.mediamanager.entity.MediaItem.findAll()
            .filter { it.id in mediaItemIds }

        val readingProgress = editions.mapNotNull { mi ->
            net.stewart.mediamanager.service.ReadingProgressService.get(userId, mi.id!!)
        }.firstOrNull()

        return bookDetail {
            this.authors.addAll(authorLinks.mapNotNull { authorsById[it.author_id]?.toProto() })
            this.editions.addAll(editions.map { it.toBookEdition() })
            readingProgress?.let { this.readingProgress = it.toProto() }
        }
    }

    // ========================================================================
    // Search
    // ========================================================================

    override suspend fun search(request: SearchRequest): SearchResponse {
        val user = currentUser()
        val query = request.query.trim()
        if (query.isEmpty()) {
            return searchResponse {
                this.query = query
            }
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val catalog = loadCatalog(user, nasRoot)
        val playableTitleIds = catalog.playableTitles.mapNotNull { it.id }.toSet()
        val results = mutableListOf<Pair<SearchResult, Double>>()

        // 1. Title search via SearchIndexService
        // Legacy clients get video-only (MOVIE + SERIES). Books and albums
        // are unlocked via the opt-in request flags.
        val matchingTitleIds = SearchIndexService.search(query)
        if (matchingTitleIds != null) {
            for (titleId in matchingTitleIds) {
                val title = catalog.titlesById[titleId] ?: continue
                val mediaType = title.media_type
                val isBook = mediaType == MediaTypeEnum.BOOK.name
                val isAlbum = mediaType == MediaTypeEnum.ALBUM.name
                val isVideo = mediaType == MediaTypeEnum.MOVIE.name ||
                    mediaType == MediaTypeEnum.TV.name ||
                    mediaType == MediaTypeEnum.PERSONAL.name

                when {
                    isVideo -> {
                        if (titleId !in playableTitleIds) continue
                        val tc = catalog.playableByTitle[titleId]?.firstOrNull()
                        results.add(searchResult {
                            resultType = if (mediaType == MediaTypeEnum.TV.name) {
                                SearchResultType.SEARCH_RESULT_TYPE_SERIES
                            } else {
                                SearchResultType.SEARCH_RESULT_TYPE_MOVIE
                            }
                            name = title.name
                            this.titleId = title.id!!
                            title.posterUrl(PosterSize.FULL)?.let { posterUrl = it }
                            title.release_year?.let { year = it }
                            quality = tc?.media_format.toProtoQuality()
                            contentRating = title.content_rating.toProtoContentRating()
                            tc?.id?.let { transcodeId = it }
                            this.mediaType = mediaType.toProtoMediaType()
                        } to (title.popularity ?: 0.0))
                    }
                    isBook && request.includeBooks -> {
                        results.add(searchResult {
                            resultType = SearchResultType.SEARCH_RESULT_TYPE_BOOK
                            name = title.name
                            this.titleId = title.id!!
                            title.posterUrl(PosterSize.FULL)?.let { posterUrl = it }
                            title.release_year?.let { year = it }
                            contentRating = title.content_rating.toProtoContentRating()
                            this.mediaType = mediaType.toProtoMediaType()
                        } to (title.popularity ?: 0.0))
                    }
                    isAlbum && request.includeAudio -> {
                        val albumArtistName = titleArtistLeadName(title.id!!)
                        results.add(searchResult {
                            resultType = SearchResultType.SEARCH_RESULT_TYPE_ALBUM
                            name = title.name
                            this.titleId = title.id!!
                            title.posterUrl(PosterSize.FULL)?.let { posterUrl = it }
                            title.release_year?.let { year = it }
                            contentRating = title.content_rating.toProtoContentRating()
                            this.mediaType = mediaType.toProtoMediaType()
                            albumArtistName?.let { artistName = it }
                        } to (title.popularity ?: 0.0))
                    }
                }
            }
        }

        // 1b. Artist search (when include_audio)
        if (request.includeAudio) {
            val queryTokensAudio = query.lowercase().split(Regex("\\s+"))
            val ownedAlbumsByArtist = TitleArtist.findAll().groupingBy { it.artist_id }.eachCount()
            for (artist in Artist.findAll()) {
                if (!queryTokensAudio.all { artist.name.lowercase().contains(it) }) continue
                val ownedCount = ownedAlbumsByArtist[artist.id] ?: 0
                if (ownedCount == 0) continue
                results.add(searchResult {
                    resultType = SearchResultType.SEARCH_RESULT_TYPE_ARTIST
                    name = artist.name
                    artist.id?.let { artistId = it }
                    titleCount = ownedCount
                } to ownedCount.toDouble())
            }

            // 1c. Track search (when include_audio)
            for (track in Track.findAll()) {
                if (!queryTokensAudio.all { track.name.lowercase().contains(it) }) continue
                val album = catalog.titlesById[track.title_id] ?: continue
                val albumArtistName = titleArtistLeadName(album.id!!)
                results.add(searchResult {
                    resultType = SearchResultType.SEARCH_RESULT_TYPE_TRACK
                    name = track.name
                    trackId = track.id!!
                    albumTitleId = album.id!!
                    albumName = album.name
                    albumArtistName?.let { artistName = it }
                    album.posterUrl(PosterSize.FULL)?.let { posterUrl = it }
                } to (album.popularity ?: 0.0))
            }
        }

        // 1d. Author search (when include_books)
        if (request.includeBooks) {
            val queryTokensBooks = query.lowercase().split(Regex("\\s+"))
            val ownedBooksByAuthor = TitleAuthor.findAll().groupingBy { it.author_id }.eachCount()
            for (author in Author.findAll()) {
                if (!queryTokensBooks.all { author.name.lowercase().contains(it) }) continue
                val ownedCount = ownedBooksByAuthor[author.id] ?: 0
                if (ownedCount == 0) continue
                results.add(searchResult {
                    resultType = SearchResultType.SEARCH_RESULT_TYPE_AUTHOR
                    name = author.name
                    author.id?.let { authorId = it }
                    titleCount = ownedCount
                } to ownedCount.toDouble())
            }
        }

        // 2. Actor search
        val queryTokens = query.lowercase().split(Regex("\\s+"))
        val castMembers = CastMemberEntity.findAll()
        val actorsByPerson = castMembers.groupBy { it.tmdb_person_id }
        for ((personId, members) in actorsByPerson) {
            val actorName = members.first().name
            if (!queryTokens.all { actorName.lowercase().contains(it) }) continue
            val ownedCount = members.count { m -> catalog.titlesById.containsKey(m.title_id) }
            if (ownedCount == 0) continue
            val headshotMember = members.maxByOrNull { it.popularity ?: 0.0 }
            val headshotUrl = if (headshotMember?.profile_path != null) "/headshots/${headshotMember.id}" else null
            val popularity = members.maxOfOrNull { it.popularity ?: 0.0 } ?: 0.0
            results.add(searchResult {
                resultType = SearchResultType.SEARCH_RESULT_TYPE_ACTOR
                name = actorName
                tmdbPersonId = personId
                headshotUrl?.let { this.headshotUrl = it }
                titleCount = ownedCount
            } to popularity)
        }

        // 3. Collection search
        val collections = TmdbCollection.findAll()
        for (coll in collections) {
            val nameMatch = queryTokens.all { coll.name.lowercase().contains(it) }
            val titleMatch = matchingTitleIds?.any { tid ->
                catalog.titlesById[tid]?.tmdb_collection_id == coll.tmdb_collection_id
            } ?: false
            if (!nameMatch && !titleMatch) continue
            val ownedTitles = catalog.allTitles.filter { it.tmdb_collection_id == coll.tmdb_collection_id }
            val maxPop = ownedTitles.maxOfOrNull { it.popularity ?: 0.0 } ?: 0.0
            results.add(searchResult {
                resultType = SearchResultType.SEARCH_RESULT_TYPE_COLLECTION
                name = coll.name
                tmdbCollectionId = coll.tmdb_collection_id
                if (coll.poster_path != null) posterUrl = "/collection-posters/${coll.tmdb_collection_id}"
            } to maxPop)
        }

        // 4. Tag search
        val allTags = TagEntity.findAll()
        val titleTags = TitleTag.findAll()
        val titleIdsByTag = titleTags.groupBy { it.tag_id }.mapValues { (_, tts) -> tts.map { it.title_id }.toSet() }
        for (tag in allTags) {
            if (!queryTokens.all { tag.name.lowercase().contains(it) }) continue
            val tagTitleIds = titleIdsByTag[tag.id] ?: continue
            val playableCount = tagTitleIds.count { it in playableTitleIds }
            if (playableCount == 0) continue
            val popSum = tagTitleIds.filter { it in playableTitleIds }
                .sumOf { catalog.titlesById[it]?.popularity ?: 0.0 }
            results.add(searchResult {
                resultType = SearchResultType.SEARCH_RESULT_TYPE_TAG
                name = tag.name
                itemId = tag.id!!
                titleCount = playableCount
            } to popSum)
        }

        // 5. Genre search
        val allGenres = GenreEntity.findAll()
        val titleGenres = TitleGenre.findAll()
        val titleIdsByGenre = titleGenres.groupBy { it.genre_id }.mapValues { (_, tgs) -> tgs.map { it.title_id }.toSet() }
        for (genre in allGenres) {
            if (!queryTokens.all { genre.name.lowercase().contains(it) }) continue
            val genreTitleIds = titleIdsByGenre[genre.id] ?: continue
            val playableCount = genreTitleIds.count { it in playableTitleIds }
            if (playableCount == 0) continue
            val popSum = genreTitleIds.filter { it in playableTitleIds }
                .sumOf { catalog.titlesById[it]?.popularity ?: 0.0 }
            results.add(searchResult {
                resultType = SearchResultType.SEARCH_RESULT_TYPE_GENRE
                name = genre.name
                itemId = genre.id!!
                titleCount = playableCount
            } to popSum)
        }

        // Sort by score descending
        val sorted = results.sortedByDescending { it.second }.map { it.first }
        val countsByType = sorted.groupBy { it.resultType.name.removePrefix("SEARCH_RESULT_TYPE_").lowercase() }
            .mapValues { (_, v) -> v.size }

        log.info("gRPC search for '{}': {} results ({})", query, sorted.size,
            countsByType.entries.joinToString(", ") { "${it.key}=${it.value}" })

        return searchResponse {
            this.query = query
            this.results.addAll(sorted)
            this.counts.putAll(countsByType)
        }
    }

    // Primary album artist name for a title_id. Returns null when no
    // title_artist rows exist (non-album titles or unlinked albums).
    private fun titleArtistLeadName(titleId: Long): String? {
        val link = TitleArtist.findAll()
            .filter { it.title_id == titleId }
            .minByOrNull { it.artist_order }
            ?: return null
        return Artist.findById(link.artist_id)?.name
    }

    // ========================================================================
    // TV: Seasons & Episodes
    // ========================================================================

    override suspend fun listSeasons(request: TitleIdRequest): SeasonsResponse {
        val user = currentUser()
        val titleId = request.titleId
        val title = TitleEntity.findById(titleId)
        if (title == null || title.hidden || title.media_type != MediaTypeEnum.TV.name || !user.canSeeRating(title.content_rating)) {
            throw StatusException(Status.NOT_FOUND)
        }

        val episodes = EpisodeEntity.findAll().filter { it.title_id == titleId }
        val titleSeasons = TitleSeason.findAll().filter { it.title_id == titleId }.associateBy { it.season_number }

        val seasons = episodes
            .groupBy { it.season_number }
            .toSortedMap()
            .map { (seasonNum, eps) ->
                val titleSeason = titleSeasons[seasonNum]
                season {
                    seasonNumber = seasonNum
                    titleSeason?.name?.let { name = it }
                    episodeCount = eps.size
                }
            }

        return seasonsResponse {
            this.seasons.addAll(seasons)
        }
    }

    override suspend fun listEpisodes(request: ListEpisodesRequest): EpisodesResponse {
        val user = currentUser()
        val titleId = request.titleId
        val seasonNumber = request.seasonNumber
        val title = TitleEntity.findById(titleId)
        if (title == null || title.hidden || title.media_type != MediaTypeEnum.TV.name || !user.canSeeRating(title.content_rating)) {
            throw StatusException(Status.NOT_FOUND)
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val episodes = EpisodeEntity.findAll()
            .filter { it.title_id == titleId && it.season_number == seasonNumber }
            .sortedBy { it.episode_number }

        if (episodes.isEmpty()) {
            throw StatusException(Status.NOT_FOUND)
        }

        val episodeIds = episodes.mapNotNull { it.id }.toSet()
        val transcodes = TranscodeEntity.findAll()
            .filter { it.title_id == titleId && it.episode_id in episodeIds && it.file_path != null }
        val transcodesByEpisode = transcodes.groupBy { it.episode_id }

        // Playback progress for this user
        val transcodeIds = transcodes.mapNotNull { it.id }.toSet()
        val progressRecords = ProgressEntity.findAll()
            .filter { it.user_id == user.id && it.transcode_id in transcodeIds }
        val progressByTranscode = progressRecords.associateBy { it.transcode_id }

        val protoEpisodes = episodes.map { ep ->
            val epTranscodes = transcodesByEpisode[ep.id] ?: emptyList()
            val bestTc = epTranscodes
                .filter { isPlayable(it, nasRoot) }
                .maxByOrNull { formatPriority(it) }
            val progress = bestTc?.id?.let { progressByTranscode[it] }
            val resumePos = progress?.position_seconds ?: 0.0
            val duration = progress?.duration_seconds

            ep.toProtoEpisode(bestTc, nasRoot, resumePos, duration)
        }

        return episodesResponse {
            this.episodes.addAll(protoEpisodes)
        }
    }

    // ========================================================================
    // Actor Detail
    // ========================================================================

    override suspend fun getActorDetail(request: ActorIdRequest): ActorDetail {
        val user = currentUser()
        val tmdbPersonId = request.tmdbPersonId
        val nasRoot = TranscoderAgent.getNasRoot()
        val castMembers = CastMemberEntity.findAll().filter { it.tmdb_person_id == tmdbPersonId }
        if (castMembers.isEmpty()) {
            throw StatusException(Status.NOT_FOUND)
        }

        val person = tmdbService.fetchPersonDetails(tmdbPersonId)
        val actorName = person.name ?: castMembers.first().name
        val headshotMember = castMembers.maxByOrNull { it.popularity ?: 0.0 }
        val headshotUrl = if (headshotMember?.profile_path != null) "/headshots/${headshotMember.id}" else null

        val allTitles = TitleEntity.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatusEnum.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesById = allTitles.associateBy { it.id }
        val characterByTitleId = castMembers.associate { it.title_id to it.character_name }

        val allTranscodes = TranscodeEntity.findAll().filter { it.file_path != null }
        val playableByTitle = allTranscodes.filter { isPlayable(it, nasRoot) }.groupBy { it.title_id }

        // Build owned titles with character names
        val seenTmdbKeys = mutableSetOf<TmdbId>()
        val ownedTitles = mutableListOf<OwnedCredit>()

        for (cm in castMembers.sortedByDescending { titlesById[it.title_id]?.popularity ?: 0.0 }) {
            val title = titlesById[cm.title_id] ?: continue
            val tmdbKey = title.tmdbKey()
            if (tmdbKey != null && tmdbKey in seenTmdbKeys) continue
            if (tmdbKey != null) seenTmdbKeys.add(tmdbKey)

            val tc = playableByTitle[title.id]?.firstOrNull()
            val playable = if (tc != null) {
                if (title.media_type == MediaTypeEnum.TV.name) {
                    playableByTitle[title.id]?.any { it.episode_id != null } == true
                } else true
            } else false
            if (!playable) continue

            ownedTitles.add(ownedCredit {
                this.title = title.toProto(tc, nasRoot)
                characterByTitleId[cm.title_id]?.let { characterName = it }
            })
        }

        // TMDB credits for other works (not owned)
        val allCredits = tmdbService.fetchPersonCredits(tmdbPersonId)
        val ownedTmdbKeys = allTitles.mapNotNull { it.tmdbKey() }.toSet()
        val wishedTmdbKeys = WishListItem.findAll()
            .filter { it.wish_type == WishTypeEnum.MEDIA.name && it.status == WishStatusEnum.ACTIVE.name }
            .mapNotNull { it.tmdbKey() }
            .toSet()

        val otherWorks = allCredits
            .filter { it.tmdbKey() !in ownedTmdbKeys }
            .map { credit ->
                creditEntry {
                    tmdbId = credit.tmdbId
                    title = credit.title
                    mediaType = credit.mediaType.toProtoMediaType()
                    credit.characterName?.let { characterName = it }
                    credit.releaseYear?.let { releaseYear = it }
                    credit.posterPath?.let { posterUrl = "https://image.tmdb.org/t/p/w500$it" }
                    popularity = credit.popularity
                    wished = credit.tmdbKey() in wishedTmdbKeys
                }
            }

        return actorDetail {
            name = actorName
            headshotUrl?.let { this.headshotUrl = it }
            person.biography?.takeIf { it.isNotBlank() }?.let { biography = it }
            person.birthday?.let { birthday = LocalDate.parse(it).toProtoCalendarDate() }
            person.deathday?.let { deathday = LocalDate.parse(it).toProtoCalendarDate() }
            person.placeOfBirth?.let { placeOfBirth = it }
            person.knownForDepartment?.let { knownForDepartment = it }
            this.ownedTitles.addAll(ownedTitles)
            this.otherWorks.addAll(otherWorks)
        }
    }

    // ========================================================================
    // Collections
    // ========================================================================

    override suspend fun listCollections(request: Empty): CollectionListResponse {
        val user = currentUser()
        val collections = TmdbCollection.findAll()
        val allTitles = TitleEntity.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatusEnum.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }

        val titlesByCollection = allTitles
            .filter { it.tmdb_collection_id != null }
            .groupBy { it.tmdb_collection_id!! }

        val items = collections
            .mapNotNull { coll ->
                val titles = titlesByCollection[coll.tmdb_collection_id] ?: return@mapNotNull null
                collectionListItem {
                    tmdbCollectionId = coll.tmdb_collection_id
                    name = coll.name
                    if (coll.poster_path != null) posterUrl = "/collection-posters/${coll.tmdb_collection_id}"
                    titleCount = titles.size
                }
            }
            .sortedBy { it.name }

        return collectionListResponse {
            this.collections.addAll(items)
        }
    }

    override suspend fun getCollectionDetail(request: CollectionIdRequest): CollectionDetail {
        val user = currentUser()
        val nasRoot = TranscoderAgent.getNasRoot()
        val tmdbCollectionId = request.tmdbCollectionId
        val coll = TmdbCollection.findAll().firstOrNull { it.tmdb_collection_id == tmdbCollectionId }
            ?: throw StatusException(Status.NOT_FOUND)

        val parts = TmdbCollectionPart.findAll()
            .filter { it.collection_id == coll.id }
            .sortedBy { it.position }

        val allTitles = TitleEntity.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatusEnum.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesByTmdbId = allTitles
            .filter { it.media_type == MediaTypeEnum.MOVIE.name && it.tmdb_id != null }
            .associateBy { it.tmdb_id!! }

        val allTranscodes = TranscodeEntity.findAll().filter { it.file_path != null }
        val playableByTitle = allTranscodes.filter { isPlayable(it, nasRoot) }.groupBy { it.title_id }
        // Title IDs that have a physical disc (MediaItemTitle link) — wish-only titles don't count as owned
        val ownedTitleIds = MediaItemTitle.findAll().map { it.title_id }.toSet()

        val items = parts.map { part ->
            val title = titlesByTmdbId[part.tmdb_movie_id]
            val tc = if (title != null) playableByTitle[title.id]?.firstOrNull() else null
            val posterUrl = when {
                title?.poster_path != null -> "/posters/w500/${title.id}"
                part.poster_path != null -> "https://image.tmdb.org/t/p/w500${part.poster_path}"
                else -> null
            }
            val year = part.release_date?.take(4)?.toIntOrNull() ?: title?.release_year

            collectionItem {
                tmdbMovieId = part.tmdb_movie_id
                name = title?.name ?: part.title
                posterUrl?.let { this.posterUrl = it }
                year?.let { this.year = it }
                owned = title != null && title.id!! in ownedTitleIds
                playable = tc != null
                title?.id?.let { titleId = it }
                quality = tc?.media_format.toProtoQuality()
                contentRating = title?.content_rating.toProtoContentRating()
                tc?.id?.let { transcodeId = it }
            }
        }

        return collectionDetail {
            name = coll.name
            if (coll.poster_path != null) posterUrl = "/collection-posters/${coll.tmdb_collection_id}"
            this.items.addAll(items)
        }
    }

    // ========================================================================
    // Tags
    // ========================================================================

    override suspend fun listTags(request: Empty): TagListResponse {
        val user = currentUser()
        val nasRoot = TranscoderAgent.getNasRoot()
        val catalog = loadCatalog(user, nasRoot)
        val playableTitleIds = catalog.playableTitles.mapNotNull { it.id }.toSet()

        val allTags = TagEntity.findAll()
        val titleTags = TitleTag.findAll()
        val titleIdsByTag = titleTags.groupBy { it.tag_id }.mapValues { (_, tts) -> tts.map { it.title_id }.toSet() }

        val items = allTags.mapNotNull { tag ->
            val tagTitleIds = titleIdsByTag[tag.id] ?: return@mapNotNull null
            val playableCount = tagTitleIds.count { it in playableTitleIds }
            if (playableCount == 0) return@mapNotNull null
            tagListItem {
                id = tag.id!!
                name = tag.name
                color = tag.bg_color.toProtoColor()
                titleCount = playableCount
            }
        }.sortedBy { it.name }

        return tagListResponse {
            tags.addAll(items)
        }
    }

    override suspend fun getTagDetail(request: TagIdRequest): TagDetail {
        val user = currentUser()
        val tagId = request.tagId
        val tag = TagEntity.findAll().firstOrNull { it.id == tagId }
            ?: throw StatusException(Status.NOT_FOUND)

        val titleIds = TitleTag.findAll().filter { it.tag_id == tagId }.map { it.title_id }.toSet()
        val titles = buildPlayableTitleList(titleIds, user)

        return tagDetail {
            name = tag.name
            color = tag.bg_color.toProtoColor()
            this.titles.addAll(titles)
        }
    }

    // ========================================================================
    // Genres
    // ========================================================================

    override suspend fun getGenreDetail(request: GenreIdRequest): GenreDetail {
        val user = currentUser()
        val genreId = request.genreId
        val genre = GenreEntity.findAll().firstOrNull { it.id == genreId }
            ?: throw StatusException(Status.NOT_FOUND)

        val titleIds = TitleGenre.findAll().filter { it.genre_id == genreId }.map { it.title_id }.toSet()
        val titles = buildPlayableTitleList(titleIds, user)

        return genreDetail {
            name = genre.name
            this.titles.addAll(titles)
        }
    }

    // ========================================================================
    // Title Actions: Favorite / Hidden
    // ========================================================================

    override suspend fun setFavorite(request: SetFlagRequest): Empty {
        val user = currentUser()
        val titleId = request.titleId
        val title = TitleEntity.findById(titleId)
        if (title == null || title.hidden || !user.canSeeRating(title.content_rating)) {
            throw StatusException(Status.NOT_FOUND)
        }
        if (request.value) {
            UserTitleFlagService.setFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        } else {
            UserTitleFlagService.clearFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun setHidden(request: SetFlagRequest): Empty {
        val user = currentUser()
        val titleId = request.titleId
        val title = TitleEntity.findById(titleId)
        if (title == null || !user.canSeeRating(title.content_rating)) {
            throw StatusException(Status.NOT_FOUND)
        }
        if (request.value) {
            UserTitleFlagService.setFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        } else {
            UserTitleFlagService.clearFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        }
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // Retranscode / Low-Storage Transcode Requests
    // ========================================================================

    override suspend fun requestRetranscode(request: TitleIdRequest): QueuedResponse {
        val user = currentUser()
        val titleId = request.titleId
        val title = TitleEntity.findById(titleId)
        if (title == null || title.hidden || !user.canSeeRating(title.content_rating)) {
            throw StatusException(Status.NOT_FOUND)
        }
        val transcodes = TranscodeEntity.findAll().filter { it.title_id == titleId && it.file_path != null }
        val updated = transcodes.filter { !it.retranscode_requested }.onEach {
            it.retranscode_requested = true
            it.save()
        }
        log.info("Re-transcode requested for title_id={} by user_id={} ({} transcodes)", titleId, user.id, updated.size)
        return queuedResponse {
            queued = updated.isNotEmpty()
            count = updated.size
        }
    }

    override suspend fun requestLowStorageTranscode(request: TitleIdRequest): QueuedResponse {
        val user = currentUser()
        val titleId = request.titleId
        val title = TitleEntity.findById(titleId)
        if (title == null || title.hidden || !user.canSeeRating(title.content_rating)) {
            throw StatusException(Status.NOT_FOUND)
        }
        val transcodes = TranscodeEntity.findAll().filter { it.title_id == titleId && it.file_path != null }
        val eligible = transcodes.filter { !it.for_mobile_available && !it.for_mobile_requested }
        eligible.forEach {
            it.for_mobile_requested = true
            it.save()
        }
        log.info("Low-storage transcode requested for title_id={} by user_id={} ({} transcodes)", titleId, user.id, eligible.size)
        return queuedResponse {
            queued = eligible.isNotEmpty()
            count = eligible.size
        }
    }

    // ========================================================================
    // Shared helpers
    // ========================================================================

    /**
     * Builds a list of playable Title protos for a given set of title IDs,
     * filtered by enrichment, visibility, rating, and playability.
     * Sorted by popularity descending.
     */
    private fun buildPlayableTitleList(titleIds: Set<Long>, user: AppUser): List<Title> {
        val nasRoot = TranscoderAgent.getNasRoot()
        val allTitles = TitleEntity.findAll()
            .filter { it.id in titleIds && !it.hidden && it.enrichment_status == EnrichmentStatusEnum.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val allTranscodes = TranscodeEntity.findAll().filter { it.file_path != null }
        val playableByTitle = allTranscodes.filter { isPlayable(it, nasRoot) }.groupBy { it.title_id }

        return allTitles
            .filter { title ->
                val tcs = playableByTitle[title.id] ?: return@filter false
                if (title.media_type == MediaTypeEnum.TV.name) tcs.any { it.episode_id != null } else true
            }
            .sortedByDescending { it.popularity ?: 0.0 }
            .map { title ->
                val tc = playableByTitle[title.id]?.firstOrNull()
                title.toProto(tc, nasRoot)
            }
    }

    private fun formatPriority(tc: TranscodeEntity): Int = when (tc.media_format) {
        MediaFormatEnum.UHD_BLURAY.name -> 3
        MediaFormatEnum.BLURAY.name -> 2
        MediaFormatEnum.HD_DVD.name -> 1
        else -> 0
    }
}
