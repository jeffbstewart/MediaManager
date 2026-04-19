package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Artist
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime

/**
 * Façade over `artist.lastfm_similar_json` that lazy-hydrates from
 * [LastFmService] on miss or staleness. Backing store for M7 (Start
 * Radio) and M8 (library recommendations). See docs/MUSIC.md.
 *
 * Graceful-degrade policy — if Last.fm returns [LastFmResult.NoKey]
 * for a lookup that has no cached JSON yet, we return [emptyList] and
 * the caller falls through to its own fallback tier. We never clear
 * a cached row because the key was later removed, so features keep
 * working for artists we've already seen.
 */
object SimilarArtistService {

    private val log = LoggerFactory.getLogger(SimilarArtistService::class.java)
    private val mapper = ObjectMapper()
    /** Default refresh horizon — tune via [refreshIfOlderThan] on a call-by-call basis. */
    val DEFAULT_REFRESH_HORIZON: Duration = Duration.ofDays(30)

    /** Delegate; overridable in tests. */
    @Volatile var lastFm: LastFmService = LastFmHttpService()

    /**
     * Get similar artists for [artist]. Reads the cached column when
     * fresh enough; otherwise hits Last.fm, writes back, returns fresh
     * data. On any fetch problem, falls back to whatever cache exists
     * (possibly empty).
     */
    fun getSimilar(
        artist: Artist,
        refreshIfOlderThan: Duration = DEFAULT_REFRESH_HORIZON,
        clock: Clock = SystemClock
    ): List<LastFmSimilarArtist> {
        val mbid = artist.musicbrainz_artist_id ?: return emptyList()
        val cachedAt = artist.similar_fetched_at
        val cutoff = LocalDateTime.now().minus(refreshIfOlderThan)
        val cachedJson = artist.lastfm_similar_json
        val fresh = cachedJson != null && cachedAt != null && cachedAt.isAfter(cutoff)
        if (fresh) return decode(cachedJson)

        val result = lastFm.fetchSimilarArtists(mbid)
        return when (result) {
            is LastFmResult.Success -> {
                artist.lastfm_similar_json = result.rawJson
                artist.similar_fetched_at = LocalDateTime.now()
                artist.updated_at = LocalDateTime.now()
                try {
                    artist.save()
                } catch (e: Exception) {
                    log.warn("Failed to cache similar artists for {}: {}", mbid, e.message)
                }
                result.similar
            }
            is LastFmResult.NotFound -> {
                // MB has this artist but Last.fm doesn't. Cache the empty
                // result so we don't keep hitting the endpoint every hour.
                artist.lastfm_similar_json = EMPTY_JSON
                artist.similar_fetched_at = LocalDateTime.now()
                try { artist.save() } catch (_: Exception) {}
                emptyList()
            }
            is LastFmResult.NoKey,
            is LastFmResult.Error -> decode(cachedJson)
        }
    }

    /**
     * Drives `features.has_music_radio`: true when either the key is
     * configured *or* at least one artist has cached similarity data
     * we can still serve.
     */
    fun hasRadio(): Boolean {
        val configured = AppConfig.findAll()
            .firstOrNull { it.config_key == LastFmHttpService.CONFIG_KEY }
            ?.config_val
            ?.isNotBlank() == true
        if (configured) return true
        return Artist.findAll().any { !it.lastfm_similar_json.isNullOrBlank() }
    }

    private fun decode(json: String?): List<LastFmSimilarArtist> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val parsed = (lastFm as? LastFmHttpService)?.parseSimilar(json)
                ?: return emptyList()
            when (parsed) {
                is LastFmResult.Success -> parsed.similar
                else -> emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to decode cached similar-artist JSON: {}", e.message)
            emptyList()
        }
    }

    private const val EMPTY_JSON = "{\"similarartists\":{\"artist\":[]}}"
}
