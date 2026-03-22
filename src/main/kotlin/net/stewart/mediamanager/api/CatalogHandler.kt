package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.MissingSeasonService
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.mediamanager.service.UserTitleFlagService
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Handles GET /api/v1/catalog/... endpoints:
 * - /catalog/home — carousel feed (resume, recently added, movies, TV)
 * - /catalog/titles — paginated, filterable title list
 * - /catalog/titles/{id} — full title detail
 * - /catalog/search?q= — search across titles, actors, collections, tags, genres
 */
object CatalogHandler {

    private val log = LoggerFactory.getLogger(CatalogHandler::class.java)

    private val DIRECT_EXTENSIONS = setOf("mp4", "m4v")
    private val TRANSCODE_EXTENSIONS = setOf("mkv", "avi")
    private const val MAX_CAROUSEL_ITEMS = 25
    private const val DEFAULT_PAGE_LIMIT = 25
    private const val MAX_PAGE_LIMIT = 100

    private val TITLE_SEASONS_EPISODES = Regex("titles/(\\d+)/seasons/(\\d+)/episodes")
    private val TITLE_SEASONS = Regex("titles/(\\d+)/seasons")
    private val TITLE_DETAIL = Regex("titles/(\\d+)")
    private val TITLE_FAVORITE = Regex("titles/(\\d+)/favorite")
    private val TITLE_HIDDEN = Regex("titles/(\\d+)/hidden")
    private val TITLE_RETRANSCODE = Regex("titles/(\\d+)/request-retranscode")
    private val TITLE_MOBILE_TRANSCODE = Regex("titles/(\\d+)/request-mobile-transcode")
    private val CONTINUE_WATCHING_DISMISS = Regex("home/continue-watching/(\\d+)")
    private val ACTOR_DETAIL = Regex("actors/(\\d+)")
    private val COLLECTION_DETAIL = Regex("collections/(\\d+)")
    private val TAG_DETAIL = Regex("tags/(\\d+)")
    private val GENRE_DETAIL = Regex("genres/(\\d+)")

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val method = req.method

        // Non-GET action endpoints (title personalization + home interactions)
        val favoriteMatch = TITLE_FAVORITE.matchEntire(path)
        val hiddenMatch = TITLE_HIDDEN.matchEntire(path)
        val retranscodeMatch = TITLE_RETRANSCODE.matchEntire(path)
        val mobileTranscodeMatch = TITLE_MOBILE_TRANSCODE.matchEntire(path)
        val dismissCwMatch = CONTINUE_WATCHING_DISMISS.matchEntire(path)

        when {
            favoriteMatch != null && method in setOf("PUT", "DELETE") -> {
                val titleId = favoriteMatch.groupValues[1].toLong()
                handleFavorite(req, resp, mapper, titleId, method == "PUT")
                return
            }
            hiddenMatch != null && method in setOf("PUT", "DELETE") -> {
                val titleId = hiddenMatch.groupValues[1].toLong()
                handleHidden(req, resp, mapper, titleId, method == "PUT")
                return
            }
            retranscodeMatch != null && method == "POST" -> {
                val titleId = retranscodeMatch.groupValues[1].toLong()
                handleRequestRetranscode(req, resp, mapper, titleId)
                return
            }
            mobileTranscodeMatch != null && method == "POST" -> {
                val titleId = mobileTranscodeMatch.groupValues[1].toLong()
                handleRequestMobileTranscode(req, resp, mapper, titleId)
                return
            }
            dismissCwMatch != null && method == "DELETE" -> {
                val titleId = dismissCwMatch.groupValues[1].toLong()
                handleDismissContinueWatching(req, resp, mapper, titleId)
                return
            }
            path == "home/dismiss-missing-season" && method == "POST" -> {
                handleDismissMissingSeason(req, resp, mapper)
                return
            }
        }

        // GET-only endpoints
        if (method != "GET") {
            ApiV1Servlet.sendError(resp, 405, "method_not_allowed")
            MetricsRegistry.countHttpResponse("api_v1", 405)
            return
        }

        when {
            path == "home" -> handleHome(req, resp, mapper)
            path == "titles" -> handleTitles(req, resp, mapper)
            path == "search" -> handleSearch(req, resp, mapper)
            path == "collections" -> handleCollectionsList(req, resp, mapper)
            path == "tags" -> handleTagsList(req, resp, mapper)
            else -> {
                // Match deeper paths first (most specific to least specific)
                val seasonsEpisodesMatch = TITLE_SEASONS_EPISODES.matchEntire(path)
                val seasonsMatch = TITLE_SEASONS.matchEntire(path)
                val titleMatch = TITLE_DETAIL.matchEntire(path)
                val actorMatch = ACTOR_DETAIL.matchEntire(path)
                val collectionMatch = COLLECTION_DETAIL.matchEntire(path)
                val tagMatch = TAG_DETAIL.matchEntire(path)
                val genreMatch = GENRE_DETAIL.matchEntire(path)

                when {
                    seasonsEpisodesMatch != null -> {
                        val titleId = seasonsEpisodesMatch.groupValues[1].toLong()
                        val seasonNum = seasonsEpisodesMatch.groupValues[2].toInt()
                        TvHandler.handleEpisodes(req, resp, mapper, titleId, seasonNum)
                    }
                    seasonsMatch != null -> {
                        val titleId = seasonsMatch.groupValues[1].toLong()
                        TvHandler.handleSeasons(req, resp, mapper, titleId)
                    }
                    titleMatch != null -> {
                        val titleId = titleMatch.groupValues[1].toLong()
                        handleTitleDetail(req, resp, mapper, titleId)
                    }
                    actorMatch != null -> {
                        val personId = actorMatch.groupValues[1].toInt()
                        BrowseHandler.handleActor(req, resp, mapper, personId)
                    }
                    collectionMatch != null -> {
                        val collId = collectionMatch.groupValues[1].toInt()
                        BrowseHandler.handleCollection(req, resp, mapper, collId)
                    }
                    tagMatch != null -> {
                        val tagId = tagMatch.groupValues[1].toLong()
                        BrowseHandler.handleTag(req, resp, mapper, tagId)
                    }
                    genreMatch != null -> {
                        val genreId = genreMatch.groupValues[1].toLong()
                        BrowseHandler.handleGenre(req, resp, mapper, genreId)
                    }
                    else -> {
                        ApiV1Servlet.sendError(resp, 404, "not_found")
                        MetricsRegistry.countHttpResponse("api_v1", 404)
                    }
                }
            }
        }
    }

    // --- Home Feed ---

    private fun handleHome(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val nasRoot = TranscoderAgent.getNasRoot()
        val catalog = loadCatalog(user, nasRoot)
        val familyNames = loadFamilyMemberNames()

        val carousels = mutableListOf<ApiCarousel>()

        // 1. Resume Playing
        val allProgress = PlaybackProgress.findAll().filter { it.user_id == user.id }
        val progressByTranscode = allProgress.associate { it.transcode_id to it }
        val resumeItems = mutableListOf<Pair<ApiTitle, PlaybackProgress>>()
        for (title in catalog.playableTitles) {
            val titleTranscodes = catalog.playableByTitle[title.id] ?: continue
            for (tc in titleTranscodes) {
                val progress = progressByTranscode[tc.id]
                if (progress != null && progress.position_seconds > 0) {
                    resumeItems.add(toApiTitle(title, tc, nasRoot, familyNames) to progress)
                    break
                }
            }
        }
        if (resumeItems.isNotEmpty()) {
            val sorted = resumeItems
                .sortedByDescending { it.second.updated_at }
                .take(MAX_CAROUSEL_ITEMS)
            carousels.add(ApiCarousel("Resume Playing", sorted.map { it.first }))
        }

        // 2. Recently Added
        val seenTitleIds = mutableSetOf<Long>()
        val recentItems = mutableListOf<ApiTitle>()
        val playableTitleIds = catalog.playableTitles.mapNotNull { it.id }.toSet()
        for (tc in catalog.playableTranscodes.sortedByDescending { it.created_at }) {
            if (recentItems.size >= MAX_CAROUSEL_ITEMS) break
            val title = catalog.titlesById[tc.title_id] ?: continue
            if (title.id!! !in playableTitleIds) continue
            if (title.id!! in seenTitleIds) continue
            seenTitleIds.add(title.id!!)
            recentItems.add(toApiTitle(title, tc, nasRoot, familyNames))
        }
        if (recentItems.isNotEmpty()) {
            carousels.add(ApiCarousel("Recently Added", recentItems))
        }

        // 3. Movies by popularity
        val movieItems = catalog.playableTitles
            .filter { it.media_type == MediaType.MOVIE.name }
            .sortedByDescending { it.popularity ?: 0.0 }
            .take(MAX_CAROUSEL_ITEMS)
            .map { toApiTitle(it, catalog.playableByTitle[it.id]?.firstOrNull(), nasRoot) }
        if (movieItems.isNotEmpty()) {
            carousels.add(ApiCarousel("Movies", movieItems))
        }

        // 4. TV Series by popularity
        val tvItems = catalog.playableTitles
            .filter { it.media_type == MediaType.TV.name }
            .sortedByDescending { it.popularity ?: 0.0 }
            .take(MAX_CAROUSEL_ITEMS)
            .map { toApiTitle(it, catalog.playableByTitle[it.id]?.firstOrNull(), nasRoot) }
        if (tvItems.isNotEmpty()) {
            carousels.add(ApiCarousel("TV Series", tvItems))
        }

        // 5. Family / Personal Videos by event date
        val familyItems = catalog.playableTitles
            .filter { it.media_type == MediaType.PERSONAL.name }
            .sortedByDescending { it.event_date }
            .take(MAX_CAROUSEL_ITEMS)
            .map { toApiTitle(it, catalog.playableByTitle[it.id]?.firstOrNull(), nasRoot, familyNames) }
        if (familyItems.isNotEmpty()) {
            carousels.add(ApiCarousel("Family", familyItems))
        }

        // Missing seasons notifications
        val missingSeasonsData = MissingSeasonService.getMissingSeasonsForUser(user.id!!)
        val missingSeasons = missingSeasonsData.map { ms ->
            val posterUrl = if (ms.posterPath != null) "/posters/w500/${ms.titleId}" else null
            mapOf(
                "title_id" to ms.titleId,
                "title_name" to ms.titleName,
                "poster_url" to posterUrl,
                "tmdb_id" to ms.tmdbId,
                "media_type" to ms.tmdbMediaType,
                "seasons" to ms.missingSeasons.map { s ->
                    mapOf("season_number" to s.season_number, "name" to s.name, "episode_count" to s.episode_count)
                }
            )
        }

        val homeFeed = mapOf(
            "carousels" to carousels,
            "missing_seasons" to missingSeasons
        )
        ApiV1Servlet.sendJson(resp, 200, homeFeed, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Paginated Title List ---

    private fun handleTitles(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val nasRoot = TranscoderAgent.getNasRoot()
        val catalog = loadCatalog(user, nasRoot)

        val page = (req.getParameter("page")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val limit = (req.getParameter("limit")?.toIntOrNull() ?: DEFAULT_PAGE_LIMIT).coerceIn(1, MAX_PAGE_LIMIT)
        val sort = req.getParameter("sort") ?: "popularity"
        val typeFilter = req.getParameter("type")
        val genreFilter = req.getParameter("genre")
        val tagFilter = req.getParameter("tag")
        val playableOnly = req.getParameter("playable") == "true"
        val query = req.getParameter("q")

        val playableTitleIds = catalog.playableTitles.mapNotNull { it.id }.toSet()

        // Start with all visible titles
        var titles = catalog.allTitles.toList()

        // Apply search filter
        if (!query.isNullOrBlank()) {
            val matchIds = SearchIndexService.search(query)
            if (matchIds != null) {
                val matchSet = matchIds.toSet()
                titles = titles.filter { it.id in matchSet }
            }
        }

        // Apply type filter
        if (typeFilter != null) {
            titles = titles.filter { it.media_type.equals(typeFilter, ignoreCase = true) }
        }

        // Apply genre filter
        if (genreFilter != null) {
            val genres = Genre.findAll()
            val genreId = genres.firstOrNull { it.name.equals(genreFilter, ignoreCase = true) }?.id
            if (genreId != null) {
                val genreTitleIds = TitleGenre.findAll().filter { it.genre_id == genreId }.map { it.title_id }.toSet()
                titles = titles.filter { it.id in genreTitleIds }
            } else {
                titles = emptyList()
            }
        }

        // Apply tag filter
        if (tagFilter != null) {
            val tags = Tag.findAll()
            val tagId = tags.firstOrNull { it.name.equals(tagFilter, ignoreCase = true) }?.id
            if (tagId != null) {
                val tagTitleIds = TitleTag.findAll().filter { it.tag_id == tagId }.map { it.title_id }.toSet()
                titles = titles.filter { it.id in tagTitleIds }
            } else {
                titles = emptyList()
            }
        }

        // Apply playable filter
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
        val apiTitles = pageTitles.map { title ->
            val tc = catalog.playableByTitle[title.id]?.firstOrNull()
            toApiTitle(title, tc, nasRoot, familyNames)
        }

        ApiV1Servlet.sendJson(resp, 200, ApiTitlePage(apiTitles, total, page, limit, totalPages), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Title Detail ---

    private fun handleTitleDetail(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val title = Title.findById(titleId)
        if (title == null || title.hidden || !user.canSeeRating(title.content_rating)) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val nasRoot = TranscoderAgent.getNasRoot()

        // Cast
        val castMembers = CastMember.findAll()
            .filter { it.title_id == titleId }
            .sortedBy { it.cast_order }
        val cast = castMembers.map { cm ->
            // Use profile_path (not headshot_cache_id) to decide — HeadshotServlet fetches on demand
            val headshotUrl = if (cm.profile_path != null) "/headshots/${cm.id}" else null
            ApiCastMember(cm.tmdb_person_id, cm.name, cm.character_name, headshotUrl, cm.cast_order)
        }

        // Genres
        val titleGenreIds = TitleGenre.findAll().filter { it.title_id == titleId }.map { it.genre_id }.toSet()
        val genres = Genre.findAll().filter { it.id in titleGenreIds }.map { ApiGenre(it.id!!, it.name) }

        // Tags
        val titleTagIds = TitleTag.findAll().filter { it.title_id == titleId }.map { it.tag_id }.toSet()
        val tags = Tag.findAll().filter { it.id in titleTagIds }.map { ApiTag(it.id!!, it.name, it.bg_color) }

        // Transcodes with episode info
        val allTranscodes = Transcode.findAll().filter { it.title_id == titleId && it.file_path != null }
        val episodes = if (title.media_type == MediaType.TV.name) {
            Episode.findAll().filter { it.title_id == titleId }.associateBy { it.id }
        } else emptyMap()

        val apiTranscodes = allTranscodes.map { tc ->
            val playable = isPlayable(tc, nasRoot)
            val episode = tc.episode_id?.let { episodes[it] }
            val hasSubtitles = hasSubtitleFile(tc, nasRoot)
            ApiTranscode(
                id = tc.id!!,
                mediaFormat = tc.media_format,
                quality = qualityLabel(tc),
                episodeId = tc.episode_id,
                seasonNumber = episode?.season_number,
                episodeNumber = episode?.episode_number,
                episodeName = episode?.name,
                playable = playable,
                hasSubtitles = hasSubtitles,
                forMobileAvailable = tc.for_mobile_available,
                forMobileRequested = tc.for_mobile_requested
            )
        }.sortedWith(compareBy(
            { it.seasonNumber ?: Int.MAX_VALUE },
            { it.episodeNumber ?: Int.MAX_VALUE }
        ))

        // Playback progress
        val firstPlayable = apiTranscodes.firstOrNull { it.playable }
        val progress = if (firstPlayable != null) {
            PlaybackProgress.findAll()
                .firstOrNull { it.user_id == user.id && it.transcode_id == firstPlayable.id }
                ?.let {
                    ApiPlaybackProgress(it.transcode_id, it.position_seconds, it.duration_seconds, it.updated_at?.toString())
                }
        } else null

        val anyPlayable = apiTranscodes.any { it.playable }
        val bestTc = allTranscodes.firstOrNull { isPlayable(it, nasRoot) }
        val posterUrl = when {
            title.media_type == MediaType.PERSONAL.name && title.poster_cache_id != null -> "/local-images/${title.poster_cache_id}"
            title.poster_path != null -> "/posters/w500/${title.id}"
            else -> null
        }
        val backdropUrl = if (title.backdrop_path != null) "/backdrops/${title.id}" else null

        // User-specific flags
        val isFavorite = UserTitleFlagService.hasFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        val isHidden = UserTitleFlagService.hasFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)

        // Wish list status — any user has an active media wish for this title's TMDB identity
        val wished = if (title.tmdb_id != null) {
            WishListItem.findAll().any {
                it.wish_type == WishType.MEDIA.name &&
                    it.status == WishStatus.ACTIVE.name &&
                    it.tmdb_id == title.tmdb_id &&
                    it.tmdb_media_type == title.media_type
            }
        } else false

        val detail = ApiTitleDetail(
            id = title.id!!,
            name = title.name,
            mediaType = title.media_type,
            year = title.release_year,
            description = title.description,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            contentRating = title.content_rating,
            popularity = title.popularity,
            quality = if (bestTc != null) qualityLabel(bestTc) else null,
            playable = anyPlayable,
            transcodeId = bestTc?.id,
            tmdbId = title.tmdb_id,
            tmdbCollectionId = title.tmdb_collection_id,
            tmdbCollectionName = title.tmdb_collection_name,
            cast = cast,
            genres = genres,
            tags = tags,
            transcodes = apiTranscodes,
            playbackProgress = progress,
            familyMembers = if (title.media_type == MediaType.PERSONAL.name) {
                loadFamilyMemberNames()[title.id!!]?.takeIf { it.isNotEmpty() }
            } else null,
            isFavorite = isFavorite,
            isHidden = isHidden,
            forMobileAvailable = bestTc?.for_mobile_available ?: false,
            wished = wished
        )

        ApiV1Servlet.sendJson(resp, 200, detail, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Collections List ---

    private fun handleCollectionsList(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val collections = TmdbCollection.findAll()
        val allTitles = Title.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }

        val titlesByCollection = allTitles
            .filter { it.tmdb_collection_id != null }
            .groupBy { it.tmdb_collection_id!! }

        val apiCollections = collections
            .mapNotNull { coll ->
                val titles = titlesByCollection[coll.tmdb_collection_id] ?: return@mapNotNull null
                val posterUrl = if (coll.poster_path != null) "/collection-posters/${coll.tmdb_collection_id}" else null
                mapOf(
                    "tmdb_collection_id" to coll.tmdb_collection_id,
                    "name" to coll.name,
                    "poster_url" to posterUrl,
                    "title_count" to titles.size
                )
            }
            .sortedBy { it["name"] as String }

        ApiV1Servlet.sendJson(resp, 200, mapOf("collections" to apiCollections), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Tags List ---

    private fun handleTagsList(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val nasRoot = TranscoderAgent.getNasRoot()
        val catalog = loadCatalog(user, nasRoot)
        val playableTitleIds = catalog.playableTitles.mapNotNull { it.id }.toSet()

        val allTags = Tag.findAll()
        val titleTags = TitleTag.findAll()
        val titleIdsByTag = titleTags.groupBy { it.tag_id }.mapValues { (_, tts) -> tts.map { it.title_id }.toSet() }

        val apiTags = allTags.mapNotNull { tag ->
            val tagTitleIds = titleIdsByTag[tag.id] ?: return@mapNotNull null
            val playableCount = tagTitleIds.count { it in playableTitleIds }
            if (playableCount == 0) return@mapNotNull null
            mapOf(
                "id" to tag.id,
                "name" to tag.name,
                "color" to tag.bg_color,
                "title_count" to playableCount
            )
        }.sortedBy { it["name"] as String }

        ApiV1Servlet.sendJson(resp, 200, mapOf("tags" to apiTags), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Search ---

    private fun handleSearch(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val query = req.getParameter("q")?.trim() ?: ""
        if (query.isEmpty()) {
            ApiV1Servlet.sendJson(resp, 200, ApiSearchResponse("", emptyList(), emptyMap()), mapper)
            MetricsRegistry.countHttpResponse("api_v1", 200)
            return
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val catalog = loadCatalog(user, nasRoot)
        val playableTitleIds = catalog.playableTitles.mapNotNull { it.id }.toSet()
        val results = mutableListOf<Pair<ApiSearchResult, Double>>()

        // 1. Title search via SearchIndexService
        val matchingTitleIds = SearchIndexService.search(query)
        if (matchingTitleIds != null) {
            for (titleId in matchingTitleIds) {
                if (titleId !in playableTitleIds) continue
                val title = catalog.titlesById[titleId] ?: continue
                val tc = catalog.playableByTitle[titleId]?.firstOrNull()
                val posterUrl = if (title.poster_path != null) "/posters/w500/${title.id}" else null
                results.add(ApiSearchResult(
                    resultType = if (title.media_type == MediaType.TV.name) "series" else "movie",
                    name = title.name,
                    titleId = title.id,
                    posterUrl = posterUrl,
                    year = title.release_year,
                    quality = if (tc != null) qualityLabel(tc) else null,
                    contentRating = title.content_rating,
                    transcodeId = tc?.id,
                    mediaType = title.media_type
                ) to (title.popularity ?: 0.0))
            }
        }

        // 2. Actor search
        val queryTokens = query.lowercase().split(Regex("\\s+"))
        val castMembers = CastMember.findAll()
        val actorsByPerson = castMembers.groupBy { it.tmdb_person_id }
        for ((personId, members) in actorsByPerson) {
            val name = members.first().name
            if (!queryTokens.all { name.lowercase().contains(it) }) continue
            val ownedCount = members.count { m -> catalog.titlesById.containsKey(m.title_id) }
            if (ownedCount == 0) continue
            val headshotMember = members.maxByOrNull { it.popularity ?: 0.0 }
            val headshotUrl = if (headshotMember?.profile_path != null) "/headshots/${headshotMember.id}" else null
            val popularity = members.maxOfOrNull { it.popularity ?: 0.0 } ?: 0.0
            results.add(ApiSearchResult(
                resultType = "actor", name = name,
                tmdbPersonId = personId, headshotUrl = headshotUrl,
                titleCount = ownedCount
            ) to popularity)
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
            val posterUrl = if (coll.poster_path != null) "/collection-posters/${coll.tmdb_collection_id}" else null
            results.add(ApiSearchResult(
                resultType = "collection", name = coll.name,
                tmdbCollectionId = coll.tmdb_collection_id, posterUrl = posterUrl
            ) to maxPop)
        }

        // 4. Tag search
        val allTags = Tag.findAll()
        val titleTags = TitleTag.findAll()
        val titleIdsByTag = titleTags.groupBy { it.tag_id }.mapValues { (_, tts) -> tts.map { it.title_id }.toSet() }
        for (tag in allTags) {
            if (!queryTokens.all { tag.name.lowercase().contains(it) }) continue
            val tagTitleIds = titleIdsByTag[tag.id] ?: continue
            val playableCount = tagTitleIds.count { it in playableTitleIds }
            if (playableCount == 0) continue
            val popSum = tagTitleIds.filter { it in playableTitleIds }
                .sumOf { catalog.titlesById[it]?.popularity ?: 0.0 }
            results.add(ApiSearchResult(
                resultType = "tag", name = tag.name, id = tag.id, titleCount = playableCount
            ) to popSum)
        }

        // 5. Genre search
        val allGenres = Genre.findAll()
        val titleGenres = TitleGenre.findAll()
        val titleIdsByGenre = titleGenres.groupBy { it.genre_id }.mapValues { (_, tgs) -> tgs.map { it.title_id }.toSet() }
        for (genre in allGenres) {
            if (!queryTokens.all { genre.name.lowercase().contains(it) }) continue
            val genreTitleIds = titleIdsByGenre[genre.id] ?: continue
            val playableCount = genreTitleIds.count { it in playableTitleIds }
            if (playableCount == 0) continue
            val popSum = genreTitleIds.filter { it in playableTitleIds }
                .sumOf { catalog.titlesById[it]?.popularity ?: 0.0 }
            results.add(ApiSearchResult(
                resultType = "genre", name = genre.name, id = genre.id, titleCount = playableCount
            ) to popSum)
        }

        // Sort by score descending
        val sorted = results.sortedByDescending { it.second }.map { it.first }
        val counts = sorted.groupBy { it.resultType }.mapValues { (_, v) -> v.size }

        log.info("API search for '{}': {} results ({})", query, sorted.size,
            counts.entries.joinToString(", ") { "${it.key}=${it.value}" })

        ApiV1Servlet.sendJson(resp, 200, ApiSearchResponse(query, sorted, counts), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Title Personalization Actions ---

    private fun handleFavorite(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long, setFlag: Boolean) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val title = Title.findById(titleId)
        if (title == null || title.hidden || !user.canSeeRating(title.content_rating)) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }
        if (setFlag) {
            UserTitleFlagService.setFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        } else {
            UserTitleFlagService.clearFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        }
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleHidden(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long, setFlag: Boolean) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val title = Title.findById(titleId)
        if (title == null || !user.canSeeRating(title.content_rating)) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }
        if (setFlag) {
            UserTitleFlagService.setFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        } else {
            UserTitleFlagService.clearFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        }
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleRequestRetranscode(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val title = Title.findById(titleId)
        if (title == null || title.hidden || !user.canSeeRating(title.content_rating)) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }
        val transcodes = Transcode.findAll().filter { it.title_id == titleId && it.file_path != null }
        val updated = transcodes.filter { !it.retranscode_requested }.onEach {
            it.retranscode_requested = true
            it.save()
        }
        if (updated.isEmpty()) {
            ApiV1Servlet.sendError(resp, 409, "already_requested")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }
        log.info("Re-transcode requested for title_id={} by user_id={} ({} transcodes)", titleId, user.id, updated.size)
        ApiV1Servlet.sendJson(resp, 200, mapOf("queued" to true, "count" to updated.size), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleRequestMobileTranscode(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val title = Title.findById(titleId)
        if (title == null || title.hidden || !user.canSeeRating(title.content_rating)) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }
        val transcodes = Transcode.findAll().filter { it.title_id == titleId && it.file_path != null }
        val eligible = transcodes.filter { !it.for_mobile_available && !it.for_mobile_requested }
        if (eligible.isEmpty()) {
            ApiV1Servlet.sendError(resp, 409, "already_requested")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }
        eligible.forEach {
            it.for_mobile_requested = true
            it.save()
        }
        log.info("Mobile transcode requested for title_id={} by user_id={} ({} transcodes)", titleId, user.id, eligible.size)
        ApiV1Servlet.sendJson(resp, 200, mapOf("queued" to true, "count" to eligible.size), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Home Feed Interactions ---

    private fun handleDismissContinueWatching(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        // Find all progress records for this user on transcodes belonging to this title
        val transcodeIds = Transcode.findAll().filter { it.title_id == titleId }.mapNotNull { it.id }.toSet()
        val progress = PlaybackProgress.findAll().filter {
            it.user_id == user.id && it.transcode_id in transcodeIds
        }
        progress.forEach { it.delete() }
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleDismissMissingSeason(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return
        val body = try {
            mapper.readTree(req.reader)
        } catch (e: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }
        val titleId = body.get("title_id")?.asLong()
        val seasonNumber = body.get("season_number")?.asInt()

        if (titleId == null) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }

        if (seasonNumber != null) {
            MissingSeasonService.dismiss(user.id!!, titleId, seasonNumber)
        } else {
            // Dismiss all missing seasons for this title
            MissingSeasonService.dismissAllForTitle(user.id!!, titleId)
        }
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    // --- Shared catalog loading ---

    private data class CatalogData(
        val allTitles: List<Title>,
        val titlesById: Map<Long?, Title>,
        val playableTitles: List<Title>,
        val playableTranscodes: List<Transcode>,
        val playableByTitle: Map<Long, List<Transcode>>
    )

    private fun loadCatalog(user: AppUser, nasRoot: String?): CatalogData {
        val allTitles = Title.findAll()
            .filter { !it.hidden }
            .filter { it.enrichment_status == EnrichmentStatus.ENRICHED.name || it.media_type == MediaType.PERSONAL.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesById = allTitles.associateBy { it.id }

        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val playableTranscodes = allTranscodes.filter { isPlayable(it, nasRoot) }
        val playableByTitle = playableTranscodes.groupBy { it.title_id }

        val playableTitles = allTitles.filter { title ->
            val titleTranscodes = playableByTitle[title.id] ?: return@filter false
            if (title.media_type == MediaType.TV.name) {
                titleTranscodes.any { it.episode_id != null }
            } else {
                true
            }
        }

        return CatalogData(allTitles, titlesById, playableTitles, playableTranscodes, playableByTitle)
    }

    private fun loadFamilyMemberNames(): Map<Long, List<String>> {
        val members = FamilyMember.findAll().associateBy { it.id }
        val links = TitleFamilyMember.findAll()
        return links.groupBy { it.title_id }.mapValues { (_, tfms) ->
            tfms.mapNotNull { members[it.family_member_id]?.name }
        }
    }

    private fun toApiTitle(title: Title, tc: Transcode?, nasRoot: String?, familyNames: Map<Long, List<String>>? = null): ApiTitle {
        val posterUrl = when {
            title.media_type == MediaType.PERSONAL.name && title.poster_cache_id != null -> "/local-images/${title.poster_cache_id}"
            title.poster_path != null -> "/posters/w500/${title.id}"
            else -> null
        }
        val backdropUrl = if (title.backdrop_path != null) "/backdrops/${title.id}" else null
        val familyMembers = if (title.media_type == MediaType.PERSONAL.name && familyNames != null) {
            familyNames[title.id]?.takeIf { it.isNotEmpty() }
        } else null

        return ApiTitle(
            id = title.id!!,
            name = title.name,
            mediaType = title.media_type,
            year = title.release_year,
            description = title.description,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            contentRating = title.content_rating,
            popularity = title.popularity,
            quality = if (tc != null) qualityLabel(tc) else null,
            playable = tc != null,
            transcodeId = tc?.id,
            tmdbId = title.tmdb_id,
            tmdbCollectionId = title.tmdb_collection_id,
            tmdbCollectionName = title.tmdb_collection_name,
            familyMembers = familyMembers,
            forMobileAvailable = tc?.for_mobile_available
        )
    }

    // --- Helpers ---

    private fun qualityLabel(tc: Transcode): String = when (tc.media_format) {
        MediaFormat.UHD_BLURAY.name -> "UHD"
        MediaFormat.DVD.name -> "SD"
        else -> "FHD"
    }

    private fun isPlayable(transcode: Transcode, nasRoot: String?): Boolean {
        val filePath = transcode.file_path ?: return false
        val ext = File(filePath).extension.lowercase()
        return when {
            ext in DIRECT_EXTENSIONS -> File(filePath).exists()
            ext in TRANSCODE_EXTENSIONS -> nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, filePath)
            else -> false
        }
    }

    private fun hasSubtitleFile(transcode: Transcode, nasRoot: String?): Boolean {
        val filePath = transcode.file_path ?: return false
        return TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt") != null
    }
}
