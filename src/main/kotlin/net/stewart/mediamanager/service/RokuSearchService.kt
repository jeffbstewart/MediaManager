package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Provides search and detail endpoints for the Roku channel.
 *
 * Search returns a single ranked list of heterogeneous results (movies, series,
 * collections, tags, genres, actors). Detail endpoints return full data for
 * collection, tag, genre, and actor landing pages.
 */
object RokuSearchService {

    private val log = LoggerFactory.getLogger(RokuSearchService::class.java)

    private val DIRECT_EXTENSIONS = setOf("mp4", "m4v")
    private val TRANSCODE_EXTENSIONS = setOf("mkv", "avi")

    // --- Data classes ---

    data class SearchResult(
        val resultType: String,
        // Movie/Series fields
        val titleId: Long? = null,
        val name: String,
        val posterUrl: String? = null,
        val year: Int? = null,
        val quality: String? = null,
        val contentRating: String? = null,
        val transcodeId: Long? = null,
        val subtitleUrl: String? = null,
        val mediaType: String? = null,
        // Collection fields
        val tmdbCollectionId: Int? = null,
        // Actor fields
        val tmdbPersonId: Int? = null,
        val headshotUrl: String? = null,
        val titleCount: Int? = null,
        // Tag/Genre fields
        val id: Long? = null,
        // Internal score (not serialized — used for sorting)
        @Transient val score: Double = 0.0
    )

    data class SearchResponse(
        val query: String,
        val results: List<SearchResult>,
        val counts: Map<String, Int>
    )

    data class CollectionDetail(
        val name: String,
        val posterUrl: String?,
        val items: List<CollectionItemDetail>
    )

    data class CollectionItemDetail(
        val tmdbMovieId: Int,
        val name: String,
        val posterUrl: String?,
        val year: Int?,
        val owned: Boolean,
        // Playable item fields (only when owned + playable)
        val titleId: Long? = null,
        val quality: String? = null,
        val contentRating: String? = null,
        val transcodeId: Long? = null,
        val subtitleUrl: String? = null,
        val mediaType: String? = null
    )

    data class TagDetail(
        val name: String,
        val items: List<RokuHomeService.CarouselItem>
    )

    data class ActorDetail(
        val name: String,
        val headshotUrl: String?,
        val items: List<ActorTitleItem>
    )

    data class ActorTitleItem(
        val titleId: Long,
        val name: String,
        val posterUrl: String?,
        val year: Int?,
        val mediaType: String,
        val quality: String?,
        val contentRating: String?,
        val transcodeId: Long?,
        val subtitleUrl: String?,
        val playable: Boolean,
        val wished: Boolean = false,
        val characterName: String? = null,
        val tmdbId: Int? = null,
        val posterPath: String? = null
    )

    // --- Search ---

    fun search(query: String, baseUrl: String, apiKey: String, user: AppUser): SearchResponse {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchResponse(query, emptyList(), emptyMap())

        val nasRoot = TranscoderAgent.getNasRoot()
        val allTitles = Title.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesById = allTitles.associateBy { it.id }

        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val playableTranscodes = allTranscodes.filter { isPlayable(it, nasRoot) }
        val playableByTitle = playableTranscodes.groupBy { it.title_id }

        val playableTitleIds = allTitles.filter { title ->
            val titleTranscodes = playableByTitle[title.id] ?: return@filter false
            if (title.media_type == MediaType.TV.name) {
                titleTranscodes.any { it.episode_id != null }
            } else {
                true
            }
        }.mapNotNull { it.id }.toSet()

        val results = mutableListOf<SearchResult>()

        // 1. Title search via SearchIndexService
        val matchingTitleIds = SearchIndexService.search(trimmed)
        if (matchingTitleIds != null) {
            for (titleId in matchingTitleIds) {
                if (titleId !in playableTitleIds) continue
                val title = titlesById[titleId] ?: continue
                val tc = playableByTitle[titleId]?.firstOrNull()
                results.add(buildTitleResult(title, tc, baseUrl, apiKey, nasRoot))
            }
        }

        // 2. Actor search
        val queryTokens = trimmed.lowercase().split(Regex("\\s+"))
        val castMembers = CastMember.findAll()
        val actorsByPerson = castMembers.groupBy { it.tmdb_person_id }
        for ((personId, members) in actorsByPerson) {
            val name = members.first().name
            val nameLC = name.lowercase()
            if (queryTokens.all { token -> nameLC.contains(token) }) {
                val ownedCount = members.count { m ->
                    val tid = m.title_id
                    tid in playableTitleIds || titlesById.containsKey(tid)
                }
                if (ownedCount == 0) continue

                val headshotMember = members.maxByOrNull { it.popularity ?: 0.0 }
                val headshotUrl = if (headshotMember?.id != null) {
                    "$baseUrl/headshots/${headshotMember.id}?key=$apiKey"
                } else null

                val popularity = members.maxOfOrNull { it.popularity ?: 0.0 } ?: 0.0
                results.add(SearchResult(
                    resultType = "actor",
                    name = name,
                    tmdbPersonId = personId,
                    headshotUrl = headshotUrl,
                    titleCount = ownedCount,
                    score = popularity
                ))
            }
        }

        // 3. Collection search
        val collections = TmdbCollection.findAll()
        val collectionParts = TmdbCollectionPart.findAll()
        val partsByCollection = collectionParts.groupBy { it.collection_id }
        // Title tmdb_collection_id -> Title mapping
        val titlesByCollectionId = allTitles.filter { it.tmdb_collection_id != null }
            .groupBy { it.tmdb_collection_id!! }

        for (coll in collections) {
            val collNameLC = coll.name.lowercase()
            // Match if name contains query OR any matching title belongs to this collection
            val nameMatch = queryTokens.all { collNameLC.contains(it) }
            val titleMatch = matchingTitleIds?.any { tid ->
                val t = titlesById[tid]
                t?.tmdb_collection_id == coll.tmdb_collection_id
            } ?: false

            if (!nameMatch && !titleMatch) continue

            val ownedTitles = titlesByCollectionId[coll.tmdb_collection_id] ?: emptyList()
            val maxPop = ownedTitles.maxOfOrNull { it.popularity ?: 0.0 } ?: 0.0
            val posterUrl = if (coll.poster_path != null) {
                "$baseUrl/collection-posters/${coll.tmdb_collection_id}?key=$apiKey"
            } else null

            results.add(SearchResult(
                resultType = "collection",
                name = coll.name,
                tmdbCollectionId = coll.tmdb_collection_id,
                posterUrl = posterUrl,
                score = maxPop + 10000.0
            ))
        }

        // 4. Tag search
        val tags = Tag.findAll()
        val titleTags = TitleTag.findAll()
        val titleIdsByTag = titleTags.groupBy { it.tag_id }.mapValues { (_, tts) -> tts.map { it.title_id }.toSet() }

        for (tag in tags) {
            val tagNameLC = tag.name.lowercase()
            if (!queryTokens.all { tagNameLC.contains(it) }) continue

            val tagTitleIds = titleIdsByTag[tag.id] ?: continue
            val playableCount = tagTitleIds.count { it in playableTitleIds }
            if (playableCount == 0) continue

            val popSum = tagTitleIds.filter { it in playableTitleIds }
                .sumOf { tid -> titlesById[tid]?.popularity ?: 0.0 }

            results.add(SearchResult(
                resultType = "tag",
                name = tag.name,
                id = tag.id,
                titleCount = playableCount,
                score = popSum + 5000.0
            ))
        }

        // 5. Genre search
        val genres = Genre.findAll()
        val titleGenres = TitleGenre.findAll()
        val titleIdsByGenre = titleGenres.groupBy { it.genre_id }.mapValues { (_, tgs) -> tgs.map { it.title_id }.toSet() }

        for (genre in genres) {
            val genreNameLC = genre.name.lowercase()
            if (!queryTokens.all { genreNameLC.contains(it) }) continue

            val genreTitleIds = titleIdsByGenre[genre.id] ?: continue
            val playableCount = genreTitleIds.count { it in playableTitleIds }
            if (playableCount == 0) continue

            val popSum = genreTitleIds.filter { it in playableTitleIds }
                .sumOf { tid -> titlesById[tid]?.popularity ?: 0.0 }

            results.add(SearchResult(
                resultType = "genre",
                name = genre.name,
                id = genre.id,
                titleCount = playableCount,
                score = popSum + 5000.0
            ))
        }

        // Sort by score descending
        val sorted = results.sortedByDescending { it.score }

        // Count by type
        val counts = sorted.groupBy { it.resultType }.mapValues { (_, v) -> v.size }

        log.info("Roku search for '{}': {} results ({})", trimmed, sorted.size,
            counts.entries.joinToString(", ") { "${it.key}=${it.value}" })

        return SearchResponse(trimmed, sorted, counts)
    }

    // --- Collection Detail ---

    fun getCollectionDetail(tmdbCollectionId: Int, baseUrl: String, apiKey: String, user: AppUser): CollectionDetail? {
        val coll = TmdbCollection.findAll().firstOrNull { it.tmdb_collection_id == tmdbCollectionId }
            ?: return null

        val nasRoot = TranscoderAgent.getNasRoot()
        val parts = TmdbCollectionPart.findAll()
            .filter { it.collection_id == coll.id }
            .sortedBy { it.position }

        // Find owned titles by tmdb_id (movie type)
        val allTitles = Title.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesByTmdbId = allTitles
            .filter { it.media_type == MediaType.MOVIE.name && it.tmdb_id != null }
            .associateBy { it.tmdb_id!! }

        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val playableTranscodes = allTranscodes.filter { isPlayable(it, nasRoot) }
        val playableByTitle = playableTranscodes.groupBy { it.title_id }

        val collPosterUrl = if (coll.poster_path != null) {
            "$baseUrl/collection-posters/${coll.tmdb_collection_id}?key=$apiKey"
        } else null

        val items = parts.map { part ->
            val title = titlesByTmdbId[part.tmdb_movie_id]
            val tc = if (title != null) playableByTitle[title.id]?.firstOrNull() else null
            val playable = tc != null

            if (title != null && playable) {
                CollectionItemDetail(
                    tmdbMovieId = part.tmdb_movie_id,
                    name = title.name,
                    posterUrl = "$baseUrl/posters/w500/${title.id}?key=$apiKey",
                    year = title.release_year,
                    owned = true,
                    titleId = title.id,
                    quality = qualityLabel(tc),
                    contentRating = title.content_rating,
                    transcodeId = tc.id,
                    subtitleUrl = if (hasSubtitleFile(tc, nasRoot)) "$baseUrl/stream/${tc.id}/subs.srt?key=$apiKey" else null,
                    mediaType = title.media_type
                )
            } else {
                val partPosterUrl = if (part.poster_path != null) {
                    "https://image.tmdb.org/t/p/w500${part.poster_path}"
                } else null
                val year = part.release_date?.take(4)?.toIntOrNull()

                CollectionItemDetail(
                    tmdbMovieId = part.tmdb_movie_id,
                    name = if (title != null) title.name else part.title,
                    posterUrl = if (title != null) "$baseUrl/posters/w500/${title.id}?key=$apiKey" else partPosterUrl,
                    year = year ?: title?.release_year,
                    owned = title != null,
                    titleId = title?.id
                )
            }
        }

        return CollectionDetail(coll.name, collPosterUrl, items)
    }

    // --- Tag Detail ---

    fun getTagDetail(tagId: Long, baseUrl: String, apiKey: String, user: AppUser): TagDetail? {
        val tag = Tag.findAll().firstOrNull { it.id == tagId } ?: return null
        return getGroupedTitleDetail(tag.name, TitleTag.findAll().filter { it.tag_id == tagId }.map { it.title_id }, baseUrl, apiKey, user)
    }

    // --- Genre Detail ---

    fun getGenreDetail(genreId: Long, baseUrl: String, apiKey: String, user: AppUser): TagDetail? {
        val genre = Genre.findAll().firstOrNull { it.id == genreId } ?: return null
        return getGroupedTitleDetail(genre.name, TitleGenre.findAll().filter { it.genre_id == genreId }.map { it.title_id }, baseUrl, apiKey, user)
    }

    private fun getGroupedTitleDetail(name: String, titleIds: List<Long>, baseUrl: String, apiKey: String, user: AppUser): TagDetail {
        val nasRoot = TranscoderAgent.getNasRoot()
        val allTitles = Title.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesById = allTitles.associateBy { it.id }

        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val playableTranscodes = allTranscodes.filter { isPlayable(it, nasRoot) }
        val playableByTitle = playableTranscodes.groupBy { it.title_id }

        val items = titleIds.mapNotNull { tid ->
            val title = titlesById[tid] ?: return@mapNotNull null
            val titleTranscodes = playableByTitle[tid] ?: return@mapNotNull null
            if (title.media_type == MediaType.TV.name && titleTranscodes.none { it.episode_id != null }) {
                return@mapNotNull null
            }
            val tc = titleTranscodes.firstOrNull()
            buildCarouselItem(title, tc, baseUrl, apiKey, nasRoot)
        }.sortedByDescending { titlesById[it.titleId]?.popularity ?: 0.0 }

        return TagDetail(name, items)
    }

    // --- Actor Detail ---

    fun getActorDetail(tmdbPersonId: Int, baseUrl: String, apiKey: String, user: AppUser): ActorDetail? {
        val nasRoot = TranscoderAgent.getNasRoot()
        val castMembers = CastMember.findAll().filter { it.tmdb_person_id == tmdbPersonId }
        if (castMembers.isEmpty()) return null

        val actorName = castMembers.first().name
        val headshotMember = castMembers.maxByOrNull { it.popularity ?: 0.0 }
        val headshotUrl = if (headshotMember?.id != null) {
            "$baseUrl/headshots/${headshotMember.id}?key=$apiKey"
        } else null

        val allTitles = Title.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesById = allTitles.associateBy { it.id }

        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val playableTranscodes = allTranscodes.filter { isPlayable(it, nasRoot) }
        val playableByTitle = playableTranscodes.groupBy { it.title_id }

        // Check wishes for this user
        val userWishes = WishListItem.findAll()
            .filter { it.user_id == user.id && it.status == WishStatus.ACTIVE.name && it.wish_type == WishType.MEDIA.name }
        val wishedTmdbKeys = userWishes.mapNotNull { it.tmdbKey() }.toSet()

        // Group cast by title, deduplicate, only include playable titles
        // Also deduplicate by tmdb_id+media_type to avoid showing the same movie added via different UPCs
        val seenTitles = mutableSetOf<Long>()
        val seenTmdbKeys = mutableSetOf<TmdbId>()
        val items = mutableListOf<ActorTitleItem>()

        for (cm in castMembers.sortedByDescending { titlesById[it.title_id]?.popularity ?: 0.0 }) {
            val title = titlesById[cm.title_id] ?: continue
            if (title.id!! in seenTitles) continue

            // Deduplicate by TMDB identity (same movie added twice gets different title IDs)
            val tmdbKey = title.tmdbKey()
            if (tmdbKey != null && tmdbKey in seenTmdbKeys) continue

            val titleTranscodes = playableByTitle[title.id]
            val playable = if (titleTranscodes != null) {
                if (title.media_type == MediaType.TV.name) titleTranscodes.any { it.episode_id != null }
                else true
            } else false

            // Only show playable titles
            if (!playable) continue

            seenTitles.add(title.id!!)
            if (tmdbKey != null) seenTmdbKeys.add(tmdbKey)

            val tc = titleTranscodes?.firstOrNull()
            val posterUrl = if (title.poster_path != null) "$baseUrl/posters/w500/${title.id}?key=$apiKey" else null
            val wished = title.tmdbKey()?.let { it in wishedTmdbKeys } ?: false

            items.add(ActorTitleItem(
                titleId = title.id!!,
                name = title.name,
                posterUrl = posterUrl,
                year = title.release_year,
                mediaType = title.media_type,
                quality = qualityLabel(tc!!),
                contentRating = title.content_rating,
                transcodeId = tc.id,
                subtitleUrl = if (hasSubtitleFile(tc, nasRoot)) "$baseUrl/stream/${tc.id}/subs.srt?key=$apiKey" else null,
                playable = true,
                wished = wished,
                characterName = cm.character_name,
                tmdbId = title.tmdb_id,
                posterPath = title.poster_path
            ))
        }

        return ActorDetail(actorName, headshotUrl, items)
    }

    // --- Wishlist ---

    data class WishResult(val success: Boolean, val reason: String? = null)

    fun addWish(tmdbId: Int, mediaType: String, title: String?, posterPath: String?, releaseYear: Int?, user: AppUser): WishResult {
        // Check for existing active wish
        val existing = WishListItem.findAll().firstOrNull {
            it.user_id == user.id &&
            it.tmdb_id == tmdbId &&
            it.tmdb_media_type == mediaType &&
            it.status == WishStatus.ACTIVE.name &&
            it.wish_type == WishType.MEDIA.name
        }
        if (existing != null) {
            return WishResult(false, "already_wished")
        }

        val wish = WishListItem(
            user_id = user.id!!,
            wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = tmdbId,
            tmdb_title = title,
            tmdb_media_type = mediaType,
            tmdb_poster_path = posterPath,
            tmdb_release_year = releaseYear,
            created_at = java.time.LocalDateTime.now()
        )
        wish.save()
        log.info("Roku wishlist: user {} added wish for tmdb_id={} type={} '{}'", user.id, tmdbId, mediaType, title)
        return WishResult(true)
    }

    // --- Helpers ---

    private fun buildTitleResult(title: Title, tc: Transcode?, baseUrl: String, apiKey: String, nasRoot: String?): SearchResult {
        val posterUrl = if (title.poster_path != null) "$baseUrl/posters/w500/${title.id}?key=$apiKey" else null
        val quality = if (tc != null) qualityLabel(tc) else null
        val subtitleUrl = if (tc != null && hasSubtitleFile(tc, nasRoot)) "$baseUrl/stream/${tc.id}/subs.srt?key=$apiKey" else null

        return SearchResult(
            resultType = if (title.media_type == MediaType.TV.name) "series" else "movie",
            titleId = title.id,
            name = title.name,
            posterUrl = posterUrl,
            year = title.release_year,
            quality = quality,
            contentRating = title.content_rating,
            transcodeId = tc?.id,
            subtitleUrl = subtitleUrl,
            mediaType = title.media_type,
            score = title.popularity ?: 0.0
        )
    }

    private fun buildCarouselItem(title: Title, tc: Transcode?, baseUrl: String, apiKey: String, nasRoot: String?): RokuHomeService.CarouselItem {
        val posterUrl = if (title.poster_path != null) "$baseUrl/posters/w500/${title.id}?key=$apiKey" else null
        val quality = if (tc != null) qualityLabel(tc) else "FHD"
        val subtitleUrl = if (tc != null && hasSubtitleFile(tc, nasRoot)) "$baseUrl/stream/${tc.id}/subs.srt?key=$apiKey" else null

        return RokuHomeService.CarouselItem(
            titleId = title.id!!,
            name = title.name,
            posterUrl = posterUrl,
            year = title.release_year,
            mediaType = title.media_type,
            quality = quality,
            contentRating = title.content_rating,
            transcodeId = tc?.id,
            subtitleUrl = subtitleUrl
        )
    }

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
        val ext = File(filePath).extension.lowercase()
        val mp4File = when {
            ext in DIRECT_EXTENSIONS -> File(filePath)
            ext in TRANSCODE_EXTENSIONS && nasRoot != null -> TranscoderAgent.getForBrowserPath(nasRoot, filePath)
            else -> return false
        }
        if (!mp4File.exists()) return false
        val srtFile = File(mp4File.parentFile, mp4File.nameWithoutExtension + ".en.srt")
        return srtFile.exists()
    }
}
