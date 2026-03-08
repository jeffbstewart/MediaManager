package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import net.stewart.mediamanager.entity.TmdbId
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Result of a TMDB credits API call — one cast member entry. */
data class TmdbCastResult(
    val tmdbPersonId: Int,
    val name: String,
    val characterName: String?,
    val profilePath: String?,
    val order: Int
)

/** Result of a TMDB person details API call. */
data class TmdbPersonResult(
    val found: Boolean,
    val name: String? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val placeOfBirth: String? = null,
    val knownForDepartment: String? = null,
    val profilePath: String? = null,
    val popularity: Double? = null,
    val errorMessage: String? = null
)

/** One entry from a person's combined credits (movie or TV appearance). */
data class TmdbCreditEntry(
    val tmdbId: Int,
    val title: String,
    val mediaType: String,       // "MOVIE" or "TV"
    val characterName: String?,
    val releaseYear: Int?,
    val posterPath: String?,
    val popularity: Double
) {
    /** Returns a type-safe TMDB key. Non-null — credits always have both fields. */
    fun tmdbKey(): TmdbId = TmdbId.of(tmdbId, mediaType)!!
}

/** A recommended title from TMDB's recommendations endpoint. */
data class TmdbRecommendation(
    val tmdbId: Int,
    val mediaType: String  // "MOVIE" or "TV"
)

/** Result of a TMDB collection details API call. */
data class TmdbCollectionResult(
    val found: Boolean,
    val collectionId: Int? = null,
    val name: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val parts: List<TmdbCollectionPartResult> = emptyList(),
    val errorMessage: String? = null
)

/** One movie entry in a TMDB collection, with its position (1-based, by release date). */
data class TmdbCollectionPartResult(
    val tmdbMovieId: Int,
    val title: String,
    val releaseDate: String?,
    val position: Int
)

/** Season metadata from TMDB TV detail response. */
data class TmdbSeasonInfo(
    val seasonNumber: Int,
    val name: String?,
    val episodeCount: Int?,
    val airDate: String?
)

/**
 * Result of a TMDB API call (search or get-by-ID).
 *
 * Three outcome categories:
 *   - found=true:  TMDB returned a match; tmdbId, title, etc. are populated
 *   - found=false, apiError=false:  no match or no API key configured (check errorMessage)
 *   - found=false, apiError=true:   transient API failure (rate limit, auth error, server error)
 */
data class TmdbSearchResult(
    val found: Boolean,
    val tmdbId: Int? = null,
    val title: String? = null,
    val releaseYear: Int? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val mediaType: String? = null,  // "MOVIE" or "TV"
    val popularity: Double? = null,
    val contentRating: String? = null,  // MPAA or TV rating (e.g. "PG-13", "TV-MA")
    val seasons: List<TmdbSeasonInfo>? = null,  // TV only — from detail response
    val collectionId: Int? = null,              // TMDB belongs_to_collection.id (movies only)
    val collectionName: String? = null,         // TMDB belongs_to_collection.name
    val apiError: Boolean = false,
    val errorMessage: String? = null
) {
    /** Returns a type-safe TMDB key, or null if tmdbId or mediaType is missing. */
    fun tmdbKey(): TmdbId? = TmdbId.of(tmdbId, mediaType)
}

/**
 * TMDB (The Movie Database) API v3 client.
 *
 * Provides three operations:
 *   - searchMovie(title) — search for a movie by cleaned title string
 *   - searchTv(title)    — search for a TV show by cleaned title string
 *   - getDetails(tmdbId) — fetch a specific movie/show by TmdbId (for reassignment)
 *
 * API key is read from System.getProperty("TMDB_API_KEY"), which is loaded from .env
 * at startup by Bootstrap. If no key is configured, all methods return a non-error "not found"
 * result so the caller can skip TMDB enrichment gracefully.
 *
 * Uses java.net.http.HttpClient (JDK standard) and Jackson (already on classpath via Vaadin).
 * Same pattern as UpcItemDbLookupService — no additional dependencies.
 */
open class TmdbService {
    private val log = LoggerFactory.getLogger(TmdbService::class.java)
    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // Simple in-memory TTL cache for person API results (1-hour expiry, max 500 entries)
    private data class CachedValue<T>(val value: T, val expiry: Long)
    private val personCache = ConcurrentHashMap<Int, CachedValue<TmdbPersonResult>>()
    private val creditsCache = ConcurrentHashMap<Int, CachedValue<List<TmdbCreditEntry>>>()
    private val cacheTtlMs = Duration.ofHours(1).toMillis()
    private val maxCacheSize = 500

    /** Removes expired entries and evicts oldest if over size limit. */
    private fun <T> evictIfNeeded(cache: ConcurrentHashMap<Int, CachedValue<T>>) {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { it.value.expiry < now }
        if (cache.size > maxCacheSize) {
            cache.entries.sortedBy { it.value.expiry }.take(cache.size - maxCacheSize)
                .forEach { cache.remove(it.key) }
        }
    }

    private fun getApiKey(): String? {
        val key = System.getProperty("TMDB_API_KEY")
        return if (key.isNullOrBlank()) null else key
    }

    private fun getReadAccessToken(): String? {
        val token = System.getProperty("TMDB_API_READ_ACCESS_TOKEN")
        return if (token.isNullOrBlank()) null else token
    }

    private fun hasAuth(): Boolean = getReadAccessToken() != null || getApiKey() != null

    private fun buildUrl(path: String, params: Map<String, String> = emptyMap()): String {
        val allParams = buildMap {
            if (getReadAccessToken() == null) put("api_key", getApiKey()!!)
            putAll(params)
        }
        val query = allParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        return "$BASE_URL$path?$query"
    }

    private fun buildRequest(url: String): HttpRequest {
        val builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15)).GET()
        getReadAccessToken()?.let { builder.header("Authorization", "Bearer $it") }
        return builder.build()
    }

    open fun searchMovie(title: String): TmdbSearchResult {
        if (!hasAuth())
            return TmdbSearchResult(found = false, errorMessage = "No TMDB API key configured")

        val encoded = URLEncoder.encode(title, Charsets.UTF_8)
        val url = buildUrl("/search/movie", mapOf("query" to encoded))
        return executeSearch(url, "MOVIE")
    }

    open fun searchTv(title: String): TmdbSearchResult {
        if (!hasAuth())
            return TmdbSearchResult(found = false, errorMessage = "No TMDB API key configured")

        val encoded = URLEncoder.encode(title, Charsets.UTF_8)
        val url = buildUrl("/search/tv", mapOf("query" to encoded))
        return executeSearch(url, "TV")
    }

    open fun searchMovieMultiple(title: String, maxResults: Int = 5): List<TmdbSearchResult> {
        if (!hasAuth()) return emptyList()

        val encoded = URLEncoder.encode(title, Charsets.UTF_8)
        val url = buildUrl("/search/movie", mapOf("query" to encoded))
        return executeSearchMultiple(url, "MOVIE", maxResults)
    }

    open fun searchTvMultiple(title: String, maxResults: Int = 5): List<TmdbSearchResult> {
        if (!hasAuth()) return emptyList()

        val encoded = URLEncoder.encode(title, Charsets.UTF_8)
        val url = buildUrl("/search/tv", mapOf("query" to encoded))
        return executeSearchMultiple(url, "TV", maxResults)
    }

    /**
     * Fetches cast credits for a title from TMDB.
     * Returns top 20 cast members sorted by billing order (actors only).
     */
    open fun fetchCredits(tmdbId: TmdbId): List<TmdbCastResult> {
        if (!hasAuth()) return emptyList()

        val type = if (tmdbId.typeString == "TV") "tv" else "movie"
        val url = buildUrl("/$type/${tmdbId.id}/credits")
        val request = buildRequest(url)

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("TMDB credits HTTP request failed: {}", e.message)
            return emptyList()
        }

        return parseCreditsResponse(response.statusCode(), response.body())
    }

    internal fun parseCreditsResponse(statusCode: Int, body: String): List<TmdbCastResult> {
        if (statusCode != 200) return emptyList()
        return try {
            val root = mapper.readTree(body)
            val cast = root.get("cast")
            if (cast == null || !cast.isArray || cast.isEmpty) return emptyList()

            (0 until minOf(cast.size(), 20)).mapNotNull { i ->
                val item = cast[i]
                val id = item.intOrNull("id") ?: return@mapNotNull null
                val name = item.textOrNull("name") ?: return@mapNotNull null
                val character = item.textOrNull("character")
                val profilePath = item.textOrNull("profile_path")
                val order = item.intOrNull("order") ?: i
                TmdbCastResult(
                    tmdbPersonId = id,
                    name = name,
                    characterName = character,
                    profilePath = profilePath,
                    order = order
                )
            }
        } catch (e: Exception) {
            log.error("Failed to parse TMDB credits response: {}", e.message)
            emptyList()
        }
    }

    open fun getDetails(tmdbId: TmdbId): TmdbSearchResult {
        if (!hasAuth())
            return TmdbSearchResult(found = false, errorMessage = "No TMDB API key configured")

        val mediaType = tmdbId.typeString
        val type = if (mediaType == "TV") "tv" else "movie"
        val appendTo = if (mediaType == "TV") "content_ratings" else "release_dates"
        val url = buildUrl("/$type/${tmdbId.id}", mapOf("append_to_response" to appendTo))
        return executeGetDetails(url, mediaType)
    }

    // --- Person API ---

    /** Fetches person details from TMDB. Results are cached for 1 hour. */
    open fun fetchPersonDetails(personId: Int): TmdbPersonResult {
        val cached = personCache[personId]
        if (cached != null && System.currentTimeMillis() < cached.expiry) return cached.value

        if (!hasAuth())
            return TmdbPersonResult(found = false, errorMessage = "No TMDB API key configured")

        val url = buildUrl("/person/$personId")
        val request = buildRequest(url)

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("TMDB person details HTTP request failed: {}", e.message)
            return TmdbPersonResult(found = false, errorMessage = "HTTP request failed: ${e.message}")
        }

        val result = parsePersonDetailsResponse(response.statusCode(), response.body())
        if (result.found) {
            evictIfNeeded(personCache)
            personCache[personId] = CachedValue(result, System.currentTimeMillis() + cacheTtlMs)
        }
        return result
    }

    internal fun parsePersonDetailsResponse(statusCode: Int, body: String): TmdbPersonResult {
        if (statusCode == 404) return TmdbPersonResult(found = false, errorMessage = "Person not found (404)")
        if (statusCode != 200) return TmdbPersonResult(found = false, errorMessage = "HTTP $statusCode")
        return try {
            val item = mapper.readTree(body)
            TmdbPersonResult(
                found = true,
                name = item.textOrNull("name"),
                biography = item.textOrNull("biography"),
                birthday = item.textOrNull("birthday"),
                deathday = item.textOrNull("deathday"),
                placeOfBirth = item.textOrNull("place_of_birth"),
                knownForDepartment = item.textOrNull("known_for_department"),
                profilePath = item.textOrNull("profile_path"),
                popularity = item.doubleOrNull("popularity")
            )
        } catch (e: Exception) {
            log.error("Failed to parse TMDB person response: {}", e.message)
            TmdbPersonResult(found = false, errorMessage = "JSON parse error: ${e.message}")
        }
    }

    /**
     * Fetches TMDB recommendations for a movie or TV show.
     * Returns a list of TMDB IDs with their media type, filtered to top 20 results.
     */
    open fun fetchRecommendations(tmdbId: TmdbId): List<TmdbRecommendation> {
        if (!hasAuth()) return emptyList()

        val typeSegment = if (tmdbId.typeString == "TV") "tv" else "movie"
        val url = buildUrl("/$typeSegment/${tmdbId.id}/recommendations")
        val request = buildRequest(url)

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("TMDB recommendations HTTP request failed: {}", e.message)
            return emptyList()
        }

        return parseRecommendationsResponse(response.statusCode(), response.body(), tmdbId.typeString)
    }

    internal fun parseRecommendationsResponse(statusCode: Int, body: String, mediaType: String): List<TmdbRecommendation> {
        if (statusCode != 200) return emptyList()
        return try {
            val root = mapper.readTree(body)
            val results = root.get("results")
            if (results == null || !results.isArray || results.isEmpty) return emptyList()

            (0 until minOf(results.size(), 20)).mapNotNull { i ->
                val item = results[i]
                val id = item.intOrNull("id") ?: return@mapNotNull null
                TmdbRecommendation(tmdbId = id, mediaType = mediaType)
            }
        } catch (e: Exception) {
            log.error("Failed to parse TMDB recommendations response: {}", e.message)
            emptyList()
        }
    }

    /** Fetches TMDB collection details including parts (movies) in release date order. */
    open fun fetchCollection(collectionId: Int): TmdbCollectionResult {
        if (!hasAuth())
            return TmdbCollectionResult(found = false, errorMessage = "No TMDB API key configured")

        val url = buildUrl("/collection/$collectionId")
        val request = buildRequest(url)

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("TMDB collection HTTP request failed: {}", e.message)
            return TmdbCollectionResult(found = false, errorMessage = "HTTP request failed: ${e.message}")
        }

        return parseCollectionResponse(response.statusCode(), response.body())
    }

    internal fun parseCollectionResponse(statusCode: Int, body: String): TmdbCollectionResult {
        if (statusCode == 404) return TmdbCollectionResult(found = false, errorMessage = "Collection not found (404)")
        if (statusCode != 200) return TmdbCollectionResult(found = false, errorMessage = "HTTP $statusCode")
        return try {
            val root = mapper.readTree(body)
            val id = root.intOrNull("id") ?: return TmdbCollectionResult(found = false, errorMessage = "Missing id")
            val name = root.textOrNull("name") ?: "Unknown Collection"
            val posterPath = root.textOrNull("poster_path")
            val backdropPath = root.textOrNull("backdrop_path")

            val partsNode = root.get("parts")
            val rawParts = if (partsNode != null && partsNode.isArray) {
                (0 until partsNode.size()).mapNotNull { i ->
                    val item = partsNode[i]
                    val movieId = item.intOrNull("id") ?: return@mapNotNull null
                    val title = item.textOrNull("title") ?: return@mapNotNull null
                    val releaseDate = item.textOrNull("release_date")
                    Triple(movieId, title, releaseDate)
                }
            } else emptyList()

            // Sort by release date (nulls last) to derive position
            val sorted = rawParts.sortedWith(compareBy(nullsLast()) { it.third })
            val parts = sorted.mapIndexed { idx, (movieId, title, releaseDate) ->
                TmdbCollectionPartResult(
                    tmdbMovieId = movieId,
                    title = title,
                    releaseDate = releaseDate,
                    position = idx + 1
                )
            }

            TmdbCollectionResult(
                found = true,
                collectionId = id,
                name = name,
                posterPath = posterPath,
                backdropPath = backdropPath,
                parts = parts
            )
        } catch (e: Exception) {
            log.error("Failed to parse TMDB collection response: {}", e.message)
            TmdbCollectionResult(found = false, errorMessage = "JSON parse error: ${e.message}")
        }
    }

    /** Fetches a person's combined credits from TMDB. Deduped by tmdbId, capped at 50, sorted by popularity. */
    open fun fetchPersonCredits(personId: Int): List<TmdbCreditEntry> {
        val cached = creditsCache[personId]
        if (cached != null && System.currentTimeMillis() < cached.expiry) return cached.value

        if (!hasAuth()) return emptyList()

        val url = buildUrl("/person/$personId/combined_credits")
        val request = buildRequest(url)

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("TMDB person credits HTTP request failed: {}", e.message)
            return emptyList()
        }

        val result = parsePersonCreditsResponse(response.statusCode(), response.body())
        if (result.isNotEmpty()) {
            evictIfNeeded(creditsCache)
            creditsCache[personId] = CachedValue(result, System.currentTimeMillis() + cacheTtlMs)
        }
        return result
    }

    companion object {
        private const val BASE_URL = "https://api.themoviedb.org/3"

        // TMDB genre IDs for low-signal TV categories (talk shows, news, reality, soap operas)
        val NOISE_GENRE_IDS = setOf(10767, 10763, 10764, 10766)

        // Character names that indicate a non-acting appearance (interview, guest spot, award show)
        private val SELF_PATTERN = Regex(
            "^(self|themselves|himself|herself|host|guest)$",
            RegexOption.IGNORE_CASE
        )

        /** Returns true if this TV credit is low-relevance noise (talk show guest, etc.). */
        internal fun isLowRelevanceCredit(
            genreIds: Set<Int>,
            character: String?,
            episodeCount: Int?
        ): Boolean {
            // Any credit on a noise-genre show is filtered regardless of role
            if (genreIds.any { it in NOISE_GENRE_IDS }) return true

            // "Self" / "Themselves" with few episodes = guest appearance on a non-noise show
            if (character != null && episodeCount != null && episodeCount <= 3) {
                val cleaned = character.trim().removeSuffix(" (voice)").removeSuffix(" (uncredited)")
                if (SELF_PATTERN.matches(cleaned)) return true
            }

            return false
        }
    }

    internal fun parsePersonCreditsResponse(statusCode: Int, body: String): List<TmdbCreditEntry> {
        if (statusCode != 200) return emptyList()
        return try {
            val root = mapper.readTree(body)
            val cast = root.get("cast")
            if (cast == null || !cast.isArray || cast.isEmpty) return emptyList()

            val seen = mutableSetOf<Int>()
            val entries = mutableListOf<TmdbCreditEntry>()

            for (i in 0 until cast.size()) {
                val item = cast[i]
                val id = item.intOrNull("id") ?: continue
                if (!seen.add(id)) continue  // deduplicate

                val mediaTypeRaw = item.textOrNull("media_type") ?: continue
                val mediaType = when (mediaTypeRaw) {
                    "movie" -> "MOVIE"
                    "tv" -> "TV"
                    else -> continue
                }

                val titleField = if (mediaType == "TV") "name" else "title"
                val dateField = if (mediaType == "TV") "first_air_date" else "release_date"

                val title = item.textOrNull(titleField) ?: continue
                val dateStr = item.textOrNull(dateField)
                val releaseYear = dateStr?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
                val character = item.textOrNull("character")
                val posterPath = item.textOrNull("poster_path")
                val popularity = item.doubleOrNull("popularity") ?: 0.0

                // Filter low-relevance TV credits (talk shows, guest spots, etc.)
                if (mediaType == "TV") {
                    val genreIds = item.intArrayOrEmpty("genre_ids")
                    val episodeCount = item.intOrNull("episode_count")
                    if (isLowRelevanceCredit(genreIds, character, episodeCount)) continue
                }

                entries.add(TmdbCreditEntry(
                    tmdbId = id,
                    title = title,
                    mediaType = mediaType,
                    characterName = character,
                    releaseYear = releaseYear,
                    posterPath = posterPath,
                    popularity = popularity
                ))
            }

            entries.sortByDescending { it.popularity }
            entries.take(50)
        } catch (e: Exception) {
            log.error("Failed to parse TMDB person credits response: {}", e.message)
            emptyList()
        }
    }

    private fun executeSearch(url: String, mediaType: String): TmdbSearchResult {
        val request = buildRequest(url)

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("TMDB HTTP request failed: {}", e.message)
            return TmdbSearchResult(found = false, apiError = true,
                errorMessage = "HTTP request failed: ${e.message}")
        }

        return parseSearchResponse(response.statusCode(), response.body(), mediaType)
    }

    internal fun parseSearchResponse(statusCode: Int, body: String, mediaType: String): TmdbSearchResult {
        return when (statusCode) {
            200 -> parseSearchBody(body, mediaType)
            401 -> TmdbSearchResult(found = false, apiError = true, errorMessage = "Invalid API key (401)")
            429 -> TmdbSearchResult(found = false, apiError = true, errorMessage = "Rate limited (429)")
            else -> TmdbSearchResult(found = false, apiError = true, errorMessage = "HTTP $statusCode")
        }
    }

    private fun parseSearchBody(body: String, mediaType: String): TmdbSearchResult {
        try {
            val root = mapper.readTree(body)
            val results = root.get("results")
            if (results == null || !results.isArray || results.isEmpty) {
                return TmdbSearchResult(found = false)
            }

            val item = results[0]
            return parseResultItem(item, mediaType)
        } catch (e: Exception) {
            log.error("Failed to parse TMDB search response: {}", e.message)
            return TmdbSearchResult(found = false, apiError = true,
                errorMessage = "JSON parse error: ${e.message}")
        }
    }

    private fun executeSearchMultiple(url: String, mediaType: String, maxResults: Int): List<TmdbSearchResult> {
        val request = buildRequest(url)

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("TMDB HTTP request failed: {}", e.message)
            return emptyList()
        }

        return parseSearchResponseMultiple(response.statusCode(), response.body(), mediaType, maxResults)
    }

    internal fun parseSearchResponseMultiple(
        statusCode: Int, body: String, mediaType: String, maxResults: Int = 5
    ): List<TmdbSearchResult> {
        if (statusCode != 200) return emptyList()
        return parseSearchBodyMultiple(body, mediaType, maxResults)
    }

    private fun parseSearchBodyMultiple(body: String, mediaType: String, maxResults: Int): List<TmdbSearchResult> {
        try {
            val root = mapper.readTree(body)
            val results = root.get("results")
            if (results == null || !results.isArray || results.isEmpty) {
                return emptyList()
            }

            return (0 until minOf(results.size(), maxResults)).mapNotNull { i ->
                val item = results[i]
                val parsed = parseResultItem(item, mediaType)
                if (parsed.found) parsed else null
            }
        } catch (e: Exception) {
            log.error("Failed to parse TMDB multi-search response: {}", e.message)
            return emptyList()
        }
    }

    private fun executeGetDetails(url: String, mediaType: String): TmdbSearchResult {
        val request = buildRequest(url)

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("TMDB HTTP request failed: {}", e.message)
            return TmdbSearchResult(found = false, apiError = true,
                errorMessage = "HTTP request failed: ${e.message}")
        }

        return parseDetailsResponse(response.statusCode(), response.body(), mediaType)
    }

    internal fun parseDetailsResponse(statusCode: Int, body: String, mediaType: String): TmdbSearchResult {
        return when (statusCode) {
            200 -> parseDetailsBody(body, mediaType)
            401 -> TmdbSearchResult(found = false, apiError = true, errorMessage = "Invalid API key (401)")
            404 -> TmdbSearchResult(found = false, errorMessage = "TMDB ID not found (404)")
            429 -> TmdbSearchResult(found = false, apiError = true, errorMessage = "Rate limited (429)")
            else -> TmdbSearchResult(found = false, apiError = true, errorMessage = "HTTP $statusCode")
        }
    }

    private fun parseDetailsBody(body: String, mediaType: String): TmdbSearchResult {
        try {
            val item = mapper.readTree(body)
            val base = parseResultItem(item, mediaType)
            if (!base.found) return base

            // Extract content rating from appended response data
            val rating = if (mediaType == "TV") extractTvRating(item) else extractMovieRating(item)
            var result = if (rating != null) base.copy(contentRating = rating) else base

            // Extract season list for TV shows (included in base TV detail response)
            if (mediaType == "TV") {
                val seasons = extractSeasons(item)
                if (seasons.isNotEmpty()) result = result.copy(seasons = seasons)
            }

            // Extract belongs_to_collection for movies
            if (mediaType != "TV") {
                val collection = item.get("belongs_to_collection")
                if (collection != null && !collection.isNull) {
                    val colId = collection.intOrNull("id")
                    val colName = collection.textOrNull("name")
                    if (colId != null) {
                        result = result.copy(collectionId = colId, collectionName = colName)
                    }
                }
            }

            return result
        } catch (e: Exception) {
            log.error("Failed to parse TMDB details response: {}", e.message)
            return TmdbSearchResult(found = false, apiError = true,
                errorMessage = "JSON parse error: ${e.message}")
        }
    }

    /**
     * Extracts the season list from a TMDB TV detail response.
     * Filters out season 0 (specials) since we only track numbered seasons.
     */
    internal fun extractSeasons(item: JsonNode): List<TmdbSeasonInfo> {
        val seasonsNode = item.get("seasons") ?: return emptyList()
        if (!seasonsNode.isArray) return emptyList()

        return (0 until seasonsNode.size()).mapNotNull { i ->
            val s = seasonsNode[i]
            val num = s.intOrNull("season_number") ?: return@mapNotNull null
            if (num == 0) return@mapNotNull null // skip specials
            TmdbSeasonInfo(
                seasonNumber = num,
                name = s.textOrNull("name"),
                episodeCount = s.intOrNull("episode_count"),
                airDate = s.textOrNull("air_date")
            )
        }
    }

    /**
     * Extracts the US movie content rating (MPAA) from the release_dates appended response.
     * TMDB structure: release_dates.results[] → find iso_3166_1="US" → release_dates[] → certification
     */
    internal fun extractMovieRating(item: JsonNode): String? {
        val releaseDates = item.get("release_dates") ?: return null
        val results = releaseDates.get("results")
        if (results == null || !results.isArray) return null

        for (i in 0 until results.size()) {
            val entry = results[i]
            if (entry.textOrNull("iso_3166_1") == "US") {
                val dates = entry.get("release_dates")
                if (dates != null && dates.isArray) {
                    for (j in 0 until dates.size()) {
                        val cert = dates[j].textOrNull("certification")
                        if (!cert.isNullOrBlank()) return cert
                    }
                }
            }
        }
        return null
    }

    /**
     * Extracts the US TV content rating from the content_ratings appended response.
     * TMDB structure: content_ratings.results[] → find iso_3166_1="US" → rating
     */
    internal fun extractTvRating(item: JsonNode): String? {
        val contentRatings = item.get("content_ratings") ?: return null
        val results = contentRatings.get("results")
        if (results == null || !results.isArray) return null

        for (i in 0 until results.size()) {
            val entry = results[i]
            if (entry.textOrNull("iso_3166_1") == "US") {
                val rating = entry.textOrNull("rating")
                if (!rating.isNullOrBlank()) return rating
            }
        }
        return null
    }

    // TMDB uses different JSON field names for movies vs TV shows:
    //   Movies: "title", "release_date"
    //   TV:     "name", "first_air_date"
    private fun parseResultItem(item: JsonNode, mediaType: String): TmdbSearchResult {
        val id = item.intOrNull("id")
            ?: return TmdbSearchResult(found = false, apiError = true,
                errorMessage = "Missing id field in TMDB response")

        val titleField = if (mediaType == "TV") "name" else "title"
        val dateField = if (mediaType == "TV") "first_air_date" else "release_date"

        val title = item.textOrNull(titleField)
        val dateStr = item.textOrNull(dateField)
        val releaseYear = dateStr?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
        val overview = item.textOrNull("overview")
        val posterPath = item.textOrNull("poster_path")
        val backdropPath = item.textOrNull("backdrop_path")
        val popularity = item.doubleOrNull("popularity")

        return TmdbSearchResult(
            found = true,
            tmdbId = id,
            title = title,
            releaseYear = releaseYear,
            overview = overview,
            posterPath = posterPath,
            backdropPath = backdropPath,
            mediaType = mediaType,
            popularity = popularity
        )
    }

    private fun JsonNode.textOrNull(field: String): String? {
        val node = this.get(field) ?: return null
        if (node.isNull) return null
        val text = node.asText()
        return text.ifBlank { null }
    }

    private fun JsonNode.intOrNull(field: String): Int? {
        val node = this.get(field) ?: return null
        if (node.isNull) return null
        return if (node.isNumber) node.asInt() else node.asText().toIntOrNull()
    }

    private fun JsonNode.doubleOrNull(field: String): Double? {
        val node = this.get(field) ?: return null
        if (node.isNull) return null
        return if (node.isNumber) node.asDouble() else node.asText().toDoubleOrNull()
    }

    private fun JsonNode.intArrayOrEmpty(field: String): Set<Int> {
        val node = this.get(field) ?: return emptySet()
        if (!node.isArray) return emptySet()
        return (0 until node.size()).mapNotNull { i ->
            val elem = node[i]
            if (elem.isNumber) elem.asInt() else null
        }.toSet()
    }
}
