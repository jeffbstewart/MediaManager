package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Client for [MusicBrainz](https://musicbrainz.org/) — the open, API-key-free
 * metadata source for music. See docs/MUSIC.md.
 *
 * MB asks for one request per second with a descriptive `User-Agent`. The
 * [RateLimiter] inside this class enforces the gap via a [ReentrantLock] so
 * no call path bursts through. Every service instance shares the same limiter
 * because MB rate-limits at the IP level, not per-connection.
 */

/** One credited artist on a release. */
data class MusicBrainzArtistCredit(
    /** MB artist ID (UUID). */
    val musicBrainzArtistId: String,
    /** Display name as MB carries it. May differ from `sort_name`. */
    val name: String,
    /** MB artist type (`Person`, `Group`, `Orchestra`, `Choir`, `Other`). Nullable for legacy records. */
    val type: String?,
    /** Sort form ("Beatles, The"). Fallback to `name` when MB doesn't supply one. */
    val sortName: String
)

/** One track on a MB release. */
data class MusicBrainzTrack(
    val musicBrainzRecordingId: String,
    val trackNumber: Int,
    val discNumber: Int,
    val name: String,
    val durationSeconds: Int?,
    /** When the track's performer differs from the album-level credit (compilation shape). Empty otherwise. */
    val trackArtistCredits: List<MusicBrainzArtistCredit>
)

/** The full result of looking up a release by barcode or MBID. */
data class MusicBrainzReleaseLookup(
    /** Specific pressing MBID. */
    val musicBrainzReleaseId: String,
    /** Release-group MBID — the "work across pressings" key we dedup on. */
    val musicBrainzReleaseGroupId: String,
    /** Album title. */
    val title: String,
    /** Album-level artist credit(s) in display order. */
    val albumArtistCredits: List<MusicBrainzArtistCredit>,
    /** Release year if MB has one. */
    val releaseYear: Int?,
    /** Label name (if MB carries it; often null for very old releases). */
    val label: String?,
    /** Barcode from the release, if populated on the MB record. */
    val barcode: String?,
    /** Tracks in disc/track order. */
    val tracks: List<MusicBrainzTrack>,
    /** Total duration in seconds, sum of track durations (skipping nulls). */
    val totalDurationSeconds: Int?,
    /** Raw JSON body for audit storage (pairs with `media_item.upc_lookup_json`). */
    val rawJson: String
)

sealed class MusicBrainzResult {
    data class Success(val release: MusicBrainzReleaseLookup) : MusicBrainzResult()
    data object NotFound : MusicBrainzResult()
    data class Error(val message: String, val rateLimited: Boolean = false) : MusicBrainzResult()
}

/**
 * One release-group from an artist's discography (`/ws/2/release-group`).
 * Used to populate ArtistScreen's "Other Works" panel. No tracklist yet —
 * the user clicks through to wishlist, so we don't need per-track detail
 * on the artist page.
 */
data class ArtistReleaseGroupRef(
    val musicBrainzReleaseGroupId: String,
    val title: String,
    /** "Album", "EP", "Single", ... from MB's `primary-type`. */
    val primaryType: String?,
    /**
     * Secondary types like "Compilation" / "Live" / "Soundtrack". Drives the
     * compilation-aware display rule on Wishlist rendering (docs/MUSIC.md Q9).
     */
    val secondaryTypes: List<String>,
    val firstReleaseYear: Int?,
    /**
     * One representative release MBID for Cover Art Archive lookups. MB's
     * release-group endpoint doesn't return the releases themselves, so
     * this is null at the listing step — the wishlist-add flow resolves it
     * lazily when the user clicks the heart.
     */
    val representativeReleaseId: String?
) {
    val isCompilation: Boolean get() = secondaryTypes.any { it.equals("Compilation", ignoreCase = true) }
}

interface MusicBrainzService {
    /**
     * Looks up a release by its EAN-13 / UPC barcode. Returns the first
     * match when MB has more than one (shared barcodes across pressings are
     * rare but possible).
     */
    fun lookupByBarcode(barcode: String): MusicBrainzResult

    /** Looks up a specific release by MBID. Used when the caller already has an ID. */
    fun lookupByReleaseMbid(releaseMbid: String): MusicBrainzResult

    /**
     * Returns up to [limit] release-groups from an artist's discography,
     * filtered to type=Album (primary type) so EPs / singles / compilation
     * appearances don't dominate the Other Works grid. Secondary types
     * still carry through so the caller can distinguish compilations.
     * Empty list on error — callers treat "no discography" and "fetch
     * failed" the same way for UI.
     */
    fun listArtistReleaseGroups(artistMbid: String, limit: Int = 100): List<ArtistReleaseGroupRef>

    companion object {
        /** Path the image proxy exposes for a release's cover art. */
        fun coverUrlByReleaseMbid(releaseMbid: String, size: CoverSize = CoverSize.M): String =
            "/proxy/caa/release/$releaseMbid/${size.pathSegment}"
    }

    enum class CoverSize(val pathSegment: String) {
        S("front-250"), M("front-500"), L("front-1200")
    }
}

class MusicBrainzHttpService : MusicBrainzService {

    private val log = LoggerFactory.getLogger(MusicBrainzHttpService::class.java)
    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val rateLimiter = RateLimiter(MIN_GAP_MILLIS)

    override fun lookupByBarcode(barcode: String): MusicBrainzResult {
        val cleaned = barcode.trim()
        if (!cleaned.matches(BARCODE_RE)) {
            return MusicBrainzResult.Error("Barcode format invalid: $barcode")
        }

        val url = "$BASE/release/?query=barcode:$cleaned&fmt=json"
        val body = try {
            fetch(url) ?: return MusicBrainzResult.NotFound
        } catch (e: RateLimitedException) {
            return MusicBrainzResult.Error("rate limited", rateLimited = true)
        } catch (e: Exception) {
            log.warn("MusicBrainz barcode fetch failed for {}: {}", cleaned, e.message)
            return MusicBrainzResult.Error("fetch failed: ${e.message}")
        }

        val releaseMbid = try {
            pickFirstReleaseMbid(body)
        } catch (e: Exception) {
            log.warn("MusicBrainz barcode parse failed for {}: {}", cleaned, e.message)
            return MusicBrainzResult.Error("parse failed: ${e.message}")
        } ?: return MusicBrainzResult.NotFound

        return lookupByReleaseMbid(releaseMbid)
    }

    override fun lookupByReleaseMbid(releaseMbid: String): MusicBrainzResult {
        if (!releaseMbid.matches(MBID_RE)) {
            return MusicBrainzResult.Error("MBID format invalid: $releaseMbid")
        }
        // `inc=recordings+artists+labels+release-groups+artist-credits` pulls
        // the tracklist, the album-level and per-track artist credits, the
        // label, and the release-group pointer in a single round trip.
        val url = "$BASE/release/$releaseMbid?inc=recordings+artists+labels+release-groups+artist-credits&fmt=json"
        val body = try {
            fetch(url) ?: return MusicBrainzResult.NotFound
        } catch (e: RateLimitedException) {
            return MusicBrainzResult.Error("rate limited", rateLimited = true)
        } catch (e: Exception) {
            log.warn("MusicBrainz release fetch failed for {}: {}", releaseMbid, e.message)
            return MusicBrainzResult.Error("fetch failed: ${e.message}")
        }

        return try {
            parseRelease(body)
        } catch (e: Exception) {
            log.warn("MusicBrainz release parse failed for {}: {}", releaseMbid, e.message)
            MusicBrainzResult.Error("parse failed: ${e.message}")
        }
    }

    override fun listArtistReleaseGroups(artistMbid: String, limit: Int): List<ArtistReleaseGroupRef> {
        if (!MBID_RE.matches(artistMbid)) return emptyList()
        val url = "$BASE/release-group?artist=$artistMbid&type=album&limit=$limit&fmt=json"
        val body = try {
            fetch(url) ?: return emptyList()
        } catch (e: RateLimitedException) {
            log.warn("MusicBrainz rate limited listing release-groups for artist {}", artistMbid)
            return emptyList()
        } catch (e: Exception) {
            log.warn("MusicBrainz list-release-groups failed for {}: {}", artistMbid, e.message)
            return emptyList()
        }

        return try {
            parseArtistReleaseGroups(body)
        } catch (e: Exception) {
            log.warn("MusicBrainz release-group parse failed for {}: {}", artistMbid, e.message)
            emptyList()
        }
    }

    internal fun parseArtistReleaseGroups(body: String): List<ArtistReleaseGroupRef> {
        val root = mapper.readTree(body)
        val groups = root.path("release-groups")
        if (!groups.isArray) return emptyList()
        val out = mutableListOf<ArtistReleaseGroupRef>()
        for (g in groups) {
            val id = g.textOrNull("id") ?: continue
            val title = g.textOrNull("title") ?: continue
            val primary = g.textOrNull("primary-type")
            val secondaryNode = g.path("secondary-types")
            val secondary = if (secondaryNode.isArray) {
                secondaryNode.mapNotNull { n -> n.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() } }
            } else emptyList()
            val year = g.textOrNull("first-release-date")?.let { extractYear(it) }
            out += ArtistReleaseGroupRef(
                musicBrainzReleaseGroupId = id,
                title = title,
                primaryType = primary,
                secondaryTypes = secondary,
                firstReleaseYear = year,
                representativeReleaseId = null
            )
        }
        return out
    }

    internal fun pickFirstReleaseMbid(body: String): String? {
        val root = mapper.readTree(body)
        val releases = root.path("releases")
        if (!releases.isArray || releases.isEmpty) return null
        // When multiple releases share a barcode we pick the first; the user
        // can correct via manual re-link later if the wrong pressing sticks.
        return releases[0].path("id").takeIf { it.isTextual }?.asText()
    }

    internal fun parseRelease(body: String): MusicBrainzResult {
        val root = mapper.readTree(body)
        val releaseId = root.textOrNull("id")
            ?: return MusicBrainzResult.Error("release missing id")
        val releaseGroupId = root.path("release-group").textOrNull("id")
            ?: return MusicBrainzResult.Error("release missing release-group id")

        val title = root.textOrNull("title") ?: "Untitled"
        val albumArtists = parseArtistCredits(root.path("artist-credit"))
        val releaseYear = root.textOrNull("date")?.let { extractYear(it) }
        val label = root.path("label-info").firstOrNull()?.path("label")?.textOrNull("name")
        val barcode = root.textOrNull("barcode")

        val tracks = mutableListOf<MusicBrainzTrack>()
        val media = root.path("media")
        if (media.isArray) {
            for ((discIndex, medium) in media.withIndex()) {
                val discNumber = medium.get("position")?.takeIf { it.isInt }?.asInt() ?: (discIndex + 1)
                val tracksJson = medium.path("tracks")
                if (!tracksJson.isArray) continue
                for (t in tracksJson) {
                    val recording = t.path("recording")
                    val recordingId = recording.textOrNull("id") ?: continue
                    val trackNum = t.get("position")?.takeIf { it.isInt }?.asInt()
                        ?: t.get("number")?.takeIf { it.isTextual }?.asText()?.toIntOrNull()
                        ?: continue
                    val name = t.textOrNull("title") ?: recording.textOrNull("title") ?: "Untitled Track"
                    val durationMs = t.get("length")?.takeIf { it.isInt || it.isLong }?.asLong()
                        ?: recording.get("length")?.takeIf { it.isInt || it.isLong }?.asLong()
                    val durationSec = durationMs?.let { (it / 1000).toInt() }

                    val titleCreditFingerprint = creditFingerprint(albumArtists)
                    val trackCredits = parseArtistCredits(t.path("artist-credit"))
                    val trackArtistCredits = if (creditFingerprint(trackCredits) == titleCreditFingerprint) {
                        // Same as album-level — don't carry a redundant per-track credit.
                        emptyList()
                    } else trackCredits

                    tracks += MusicBrainzTrack(
                        musicBrainzRecordingId = recordingId,
                        trackNumber = trackNum,
                        discNumber = discNumber,
                        name = name,
                        durationSeconds = durationSec,
                        trackArtistCredits = trackArtistCredits
                    )
                }
            }
        }

        val totalDuration = tracks.mapNotNull { it.durationSeconds }.sum().takeIf { it > 0 }

        return MusicBrainzResult.Success(MusicBrainzReleaseLookup(
            musicBrainzReleaseId = releaseId,
            musicBrainzReleaseGroupId = releaseGroupId,
            title = title,
            albumArtistCredits = albumArtists,
            releaseYear = releaseYear,
            label = label,
            barcode = barcode,
            tracks = tracks,
            totalDurationSeconds = totalDuration,
            rawJson = body
        ))
    }

    private fun parseArtistCredits(node: JsonNode): List<MusicBrainzArtistCredit> {
        if (!node.isArray) return emptyList()
        val out = mutableListOf<MusicBrainzArtistCredit>()
        for (entry in node) {
            val artist = entry.path("artist")
            val mbid = artist.textOrNull("id") ?: continue
            val name = entry.textOrNull("name") ?: artist.textOrNull("name") ?: continue
            val sortName = artist.textOrNull("sort-name") ?: name
            val type = artist.textOrNull("type")
            out += MusicBrainzArtistCredit(
                musicBrainzArtistId = mbid,
                name = name,
                type = type,
                sortName = sortName
            )
        }
        return out
    }

    /** Stable representation of an ordered credit list for equality checks. */
    private fun creditFingerprint(credits: List<MusicBrainzArtistCredit>): String =
        credits.joinToString("|") { it.musicBrainzArtistId }

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

    /**
     * Single-slot gate that guarantees at least [minGapMillis] between
     * consecutive fetches, regardless of which thread calls. Blocking.
     */
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
        private const val BASE = "https://musicbrainz.org/ws/2"
        private const val USER_AGENT =
            "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
        /** MB asks for ≤1 req/sec; 1100ms gives a 10 % margin. */
        private const val MIN_GAP_MILLIS = 1100L
        private val MBID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val BARCODE_RE = Regex("^\\d{8,14}$")
    }

    private class RateLimitedException : RuntimeException("rate limited")
}

// ---- Helpers, shared with tests ----
//
// textOrNull and extractYear are already provided by OpenLibraryService.kt
// at the same package scope — reused here to avoid duplication.

/** Convert an MB release date (YYYY / YYYY-MM / YYYY-MM-DD) to LocalDate where possible. */
internal fun parseMbDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    return try {
        when {
            raw.matches(Regex("^\\d{4}$")) -> LocalDate.of(raw.toInt(), 1, 1)
            raw.matches(Regex("^\\d{4}-\\d{2}$")) -> LocalDate.parse("$raw-01")
            raw.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) -> LocalDate.parse(raw)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
