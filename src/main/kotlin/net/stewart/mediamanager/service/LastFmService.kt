package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Client for the [Last.fm](https://www.last.fm/api) artist.getSimilar
 * endpoint — the similarity data source for Start Radio (M7) and the
 * library-recommendations surface (M8). See docs/MUSIC.md.
 *
 * The API key is optional; admin pastes it into `app_config.lastfm_api_key`
 * via the Settings page. When absent, this service returns [NoKey] from
 * every call and the caller falls back to whatever cached similar-artist
 * JSON is already on each artist row — so removing the key later does
 * not hard-disable features for artists we've already radio'd from.
 *
 * Rate limit: Last.fm asks for ≤5 req/sec; we shape to 1 req/sec as a
 * defensive margin, same policy we use for MusicBrainz.
 */

/** One entry in a similar-artists response. Match is Last.fm's 0..1 similarity score. */
data class LastFmSimilarArtist(
    val name: String,
    /** Last.fm exposes MBIDs where known. Null for entries that didn't map. */
    val musicBrainzArtistId: String?,
    val match: Double
)

sealed class LastFmResult {
    data class Success(val similar: List<LastFmSimilarArtist>, val rawJson: String) : LastFmResult()
    data object NotFound : LastFmResult()
    data object NoKey : LastFmResult()
    data class Error(val message: String, val rateLimited: Boolean = false) : LastFmResult()
}

interface LastFmService {
    /**
     * Return the top [limit] similar artists for [artistMbid] if Last.fm
     * recognizes the MBID. [NoKey] when the admin hasn't configured a
     * key; [NotFound] when MBID is valid but Last.fm has no data; [Error]
     * for transport / rate-limit problems.
     */
    fun fetchSimilarArtists(artistMbid: String, limit: Int = 50): LastFmResult
}

class LastFmHttpService : LastFmService {

    private val log = LoggerFactory.getLogger(LastFmHttpService::class.java)
    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val rateLimiter = RateLimiter(MIN_GAP_MILLIS)

    override fun fetchSimilarArtists(artistMbid: String, limit: Int): LastFmResult {
        if (!MBID_RE.matches(artistMbid)) {
            return LastFmResult.Error("MBID format invalid: $artistMbid")
        }
        val apiKey = apiKey() ?: return LastFmResult.NoKey

        val url = "$BASE?method=artist.getsimilar" +
            "&mbid=${URLEncoder.encode(artistMbid, Charsets.UTF_8)}" +
            "&limit=$limit" +
            "&autocorrect=1" +
            "&api_key=${URLEncoder.encode(apiKey, Charsets.UTF_8)}" +
            "&format=json"

        val body = try {
            fetch(url) ?: return LastFmResult.NotFound
        } catch (e: RateLimitedException) {
            return LastFmResult.Error("rate limited", rateLimited = true)
        } catch (e: Exception) {
            log.warn("Last.fm similar fetch failed for {}: {}", artistMbid, e.message)
            return LastFmResult.Error("fetch failed: ${e.message}")
        }

        return try {
            parseSimilar(body)
        } catch (e: Exception) {
            log.warn("Last.fm similar parse failed for {}: {}", artistMbid, e.message)
            LastFmResult.Error("parse failed: ${e.message}")
        }
    }

    internal fun parseSimilar(body: String): LastFmResult {
        val root = mapper.readTree(body)
        // Last.fm wraps errors in a top-level `error` / `message` pair.
        root.path("error").takeIf { it.isInt }?.let {
            val msg = root.path("message").asText("last.fm error ${it.asInt()}")
            return if (it.asInt() == 6) LastFmResult.NotFound else LastFmResult.Error(msg)
        }
        val artists = root.path("similarartists").path("artist")
        if (!artists.isArray) return LastFmResult.Success(emptyList(), body)
        val list = mutableListOf<LastFmSimilarArtist>()
        for (a in artists) {
            val name = a.path("name").takeIf { it.isTextual }?.asText() ?: continue
            val mbid = a.path("mbid").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            val match = a.path("match").takeIf { it.isTextual || it.isNumber }
                ?.let { if (it.isTextual) it.asText().toDoubleOrNull() else it.asDouble() }
                ?: 0.0
            list += LastFmSimilarArtist(name = name, musicBrainzArtistId = mbid, match = match)
        }
        return LastFmResult.Success(list, body)
    }

    private fun apiKey(): String? =
        AppConfig.findAll().firstOrNull { it.config_key == CONFIG_KEY }?.config_val
            ?.takeIf { it.isNotBlank() }

    private fun fetch(url: String): String? {
        rateLimiter.awaitSlot()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build()
        val response: HttpResponse<String> =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> response.body()
            404 -> null
            429, 503 -> throw RateLimitedException()
            else -> throw RuntimeException("HTTP ${response.statusCode()} from $url")
        }
    }

    /** Defensive 1 req/sec gate shared across all call sites of this instance. */
    private class RateLimiter(private val minGapMillis: Long) {
        private val lock = ReentrantLock()
        private var lastCall: Long = 0

        fun awaitSlot() = lock.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastCall
            if (elapsed < minGapMillis) {
                Thread.sleep(minGapMillis - elapsed)
            }
            lastCall = System.currentTimeMillis()
        }
    }

    companion object {
        const val CONFIG_KEY = "lastfm_api_key"
        private const val BASE = "https://ws.audioscrobbler.com/2.0/"
        private const val USER_AGENT =
            "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
        private const val MIN_GAP_MILLIS = 1100L
        private val MBID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }

    private class RateLimitedException : RuntimeException("rate limited")
}
