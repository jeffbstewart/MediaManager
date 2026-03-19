package net.stewart.mediamanager.api

/**
 * Shared DTOs for the iOS REST API.
 *
 * Property names use camelCase; the ObjectMapper's SNAKE_CASE naming strategy
 * converts them to snake_case in JSON output (e.g., posterUrl -> poster_url).
 */

data class ApiTitle(
    val id: Long,
    val name: String,
    val mediaType: String,
    val year: Int?,
    val description: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val contentRating: String?,
    val popularity: Double?,
    val quality: String?,
    val playable: Boolean,
    val transcodeId: Long?,
    val tmdbId: Int?,
    val tmdbCollectionId: Int?,
    val tmdbCollectionName: String?
)

data class ApiTitleDetail(
    val id: Long,
    val name: String,
    val mediaType: String,
    val year: Int?,
    val description: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val contentRating: String?,
    val popularity: Double?,
    val quality: String?,
    val playable: Boolean,
    val transcodeId: Long?,
    val tmdbId: Int?,
    val tmdbCollectionId: Int?,
    val tmdbCollectionName: String?,
    val cast: List<ApiCastMember>,
    val genres: List<ApiGenre>,
    val tags: List<ApiTag>,
    val transcodes: List<ApiTranscode>,
    val playbackProgress: ApiPlaybackProgress?
)

data class ApiCastMember(
    val tmdbPersonId: Int,
    val name: String,
    val characterName: String?,
    val headshotUrl: String?,
    val order: Int
)

data class ApiGenre(
    val id: Long,
    val name: String
)

data class ApiTag(
    val id: Long,
    val name: String,
    val color: String
)

data class ApiTranscode(
    val id: Long,
    val mediaFormat: String?,
    val quality: String,
    val episodeId: Long?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeName: String?,
    val playable: Boolean,
    val hasSubtitles: Boolean
)

data class ApiPlaybackProgress(
    val transcodeId: Long,
    val positionSeconds: Double,
    val durationSeconds: Double?,
    val updatedAt: String?
)

data class ApiCarousel(
    val name: String,
    val items: List<ApiTitle>
)

data class ApiHomeFeed(
    val carousels: List<ApiCarousel>
)

data class ApiSearchResult(
    val resultType: String,
    val name: String,
    // Title fields
    val titleId: Long? = null,
    val posterUrl: String? = null,
    val year: Int? = null,
    val quality: String? = null,
    val contentRating: String? = null,
    val transcodeId: Long? = null,
    val mediaType: String? = null,
    // Collection fields
    val tmdbCollectionId: Int? = null,
    // Actor fields
    val tmdbPersonId: Int? = null,
    val headshotUrl: String? = null,
    val titleCount: Int? = null,
    // Tag/Genre fields
    val id: Long? = null
)

data class ApiSearchResponse(
    val query: String,
    val results: List<ApiSearchResult>,
    val counts: Map<String, Int>
)

data class ApiTitlePage(
    val titles: List<ApiTitle>,
    val total: Int,
    val page: Int,
    val limit: Int,
    val totalPages: Int
)

// --- Phase 3: TV + Landing Pages ---

data class ApiSeason(
    val seasonNumber: Int,
    val name: String?,
    val episodeCount: Int
)

data class ApiEpisode(
    val episodeId: Long,
    val transcodeId: Long?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String?,
    val quality: String?,
    val playable: Boolean,
    val hasSubtitles: Boolean,
    val resumePosition: Double,
    val watchedPercent: Int
)

data class ApiActorDetail(
    val name: String,
    val headshotUrl: String?,
    val titles: List<ApiTitle>
)

data class ApiCollectionDetail(
    val name: String,
    val posterUrl: String?,
    val items: List<ApiCollectionItem>
)

data class ApiCollectionItem(
    val tmdbMovieId: Int,
    val name: String,
    val posterUrl: String?,
    val year: Int?,
    val owned: Boolean,
    val playable: Boolean,
    val titleId: Long?,
    val quality: String?,
    val contentRating: String?,
    val transcodeId: Long?
)

data class ApiTagDetail(
    val name: String,
    val color: String,
    val titles: List<ApiTitle>
)

data class ApiGenreDetail(
    val name: String,
    val titles: List<ApiTitle>
)
