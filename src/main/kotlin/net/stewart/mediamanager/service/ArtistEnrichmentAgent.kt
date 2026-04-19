package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Artist
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Background daemon that fills in artist biographies, begin/end dates, and
 * headshots from MusicBrainz + Wikipedia. Mirrors [AuthorEnrichmentAgent].
 *
 * Two-pass structure:
 * 1. **MusicBrainz** — fetch `/ws/2/artist/{mbid}?inc=url-rels&fmt=json`
 *    for begin/end dates and the Wikidata relation URL. 1.1 s gap per
 *    request (same as [MusicBrainzHttpService]).
 * 2. **Wikipedia** — for artists whose bio is short or whose headshot is
 *    missing, resolve the Wikidata ID to an English Wikipedia title via
 *    Wikidata's `Special:EntityData`, then fetch the Wikipedia REST page
 *    summary for a richer extract and thumbnail. Pattern copied from
 *    [AuthorEnrichmentAgent] — same fallback semantics.
 */
class ArtistEnrichmentAgent(
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(ArtistEnrichmentAgent::class.java)
    internal val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    companion object {
        // Cycle between batches. Long idle poll when the queue is empty,
        // short active gap when there's more work queued up. MB's
        // 1-req/sec rate limit is enforced inside a batch via MB_GAP;
        // the inter-batch gap here only has to be long enough to keep
        // the CPU + log noise down.
        private val ACTIVE_CYCLE = 15.seconds
        private val IDLE_CYCLE = 5.minutes
        private val MB_GAP = 1100.milliseconds               // 1.1s per MB's ask
        private val WIKI_GAP = 1.seconds                     // Wikipedia is more permissive
        private val STARTUP_DELAY = 45.seconds
        private const val BATCH_SIZE = 20
        private const val MB_BASE = "https://musicbrainz.org/ws/2"
        private const val WIKIDATA_BASE = "https://www.wikidata.org"
        private const val WIKIPEDIA_BASE = "https://en.wikipedia.org"
        private const val USER_AGENT =
            "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
        private const val SHORT_BIO_THRESHOLD = 200
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("ArtistEnrichmentAgent started (active {}s / idle {}m, batch {})",
                ACTIVE_CYCLE.inWholeSeconds, IDLE_CYCLE.inWholeMinutes, BATCH_SIZE)
            try { clock.sleep(STARTUP_DELAY) } catch (_: InterruptedException) { return@Thread }
            while (running.get()) {
                val processed = try {
                    enrichBatch()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("ArtistEnrichmentAgent error: {}", e.message, e)
                    0
                }
                val wait = if (processed > 0) ACTIVE_CYCLE else IDLE_CYCLE
                try { clock.sleep(wait) } catch (_: InterruptedException) { break }
            }
            log.info("ArtistEnrichmentAgent stopped")
        }, "artist-enrichment").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /** Returns the number of artists actually processed in this batch. */
    internal fun enrichBatch(): Int {
        val allNeeding = Artist.findAll().filter { needsEnrichment(it) }
        val queueBefore = allNeeding.size
        val candidates = allNeeding.take(BATCH_SIZE)
        if (candidates.isEmpty()) {
            log.debug("No artists need enrichment")
            return 0
        }
        log.info("Enriching {} artist(s); queue size before batch: {}",
            candidates.size, queueBefore)

        var progressed = 0
        var stuck = 0

        for ((i, artist) in candidates.withIndex()) {
            if (!running.get()) break
            if (i > 0) {
                try { clock.sleep(MB_GAP) } catch (_: InterruptedException) { break }
            }
            val before = gapsFor(artist)
            try {
                enrichOne(artist)
            } catch (e: Exception) {
                log.warn("Enrichment failed for artist id={} mbid={}: {}",
                    artist.id, artist.musicbrainz_artist_id, e.message)
            }
            val after = gapsFor(artist)
            val filled = before - after
            if (filled.isNotEmpty()) {
                progressed++
                val stillMissing = if (after.isEmpty()) "done" else "still missing ${after.joinToString(",")}"
                log.info("Artist {} '{}': filled {} ({})",
                    artist.id, artist.name, filled.joinToString(","), stillMissing)
            } else {
                stuck++
                log.info("Artist {} '{}': no progress, still missing {}",
                    artist.id, artist.name, after.joinToString(","))
            }
        }

        val queueAfter = Artist.findAll().count { needsEnrichment(it) }
        log.info("Batch done: progressed={} stuck={} queue={} (delta={})",
            progressed, stuck, queueAfter, queueAfter - queueBefore)
        return candidates.size
    }

    /**
     * Named gaps in a single artist's enrichment state. Drives the
     * progress / stuck reporting in [enrichBatch]: same set before and
     * after a pass means we fetched but nothing upstream filled the
     * hole — the artist is stuck, not still-to-do.
     */
    private fun gapsFor(a: Artist): Set<String> {
        val gaps = mutableSetOf<String>()
        if (!a.musicbrainz_artist_id.isNullOrBlank()) {
            if (a.wikidata_id.isNullOrBlank()) gaps += "wikidata_id"
            if (a.begin_date == null) gaps += "begin_date"
        }
        if (!a.wikidata_id.isNullOrBlank()) {
            if (a.biography.isNullOrBlank()) gaps += "biography"
            else if ((a.biography?.length ?: 0) < SHORT_BIO_THRESHOLD) gaps += "biography_short"
            if (a.headshot_path.isNullOrBlank()) gaps += "headshot"
        }
        return gaps
    }

    internal fun needsEnrichment(a: Artist): Boolean {
        val mbHasWork = !a.musicbrainz_artist_id.isNullOrBlank()
        val missingFromMb = mbHasWork && (a.wikidata_id.isNullOrBlank() || a.begin_date == null)
        val canUseWikipedia = !a.wikidata_id.isNullOrBlank() && (
            a.biography.isNullOrBlank() ||
                (a.biography?.length ?: 0) < SHORT_BIO_THRESHOLD ||
                a.headshot_path.isNullOrBlank()
        )
        return missingFromMb || canUseWikipedia
    }

    internal fun enrichOne(artist: Artist) {
        enrichFromMusicBrainz(artist)
        if (!artist.wikidata_id.isNullOrBlank() && needsWikipediaData(artist)) {
            try { clock.sleep(WIKI_GAP) } catch (_: InterruptedException) { return }
            enrichFromWikipedia(artist)
        }
    }

    private fun enrichFromMusicBrainz(artist: Artist) {
        val mbid = artist.musicbrainz_artist_id ?: return
        val body = fetch("$MB_BASE/artist/$mbid?inc=url-rels&fmt=json") ?: return
        val node = mapper.readTree(body)

        // life-span.begin / end are ISO-shaped: YYYY, YYYY-MM, or YYYY-MM-DD.
        val begin = parseMbDate(node.path("life-span").textOrNull("begin"))
        val end = parseMbDate(node.path("life-span").textOrNull("ended")
            ?.let { if (it == "true") node.path("life-span").textOrNull("end") else null }
            ?: node.path("life-span").textOrNull("end"))

        // Wikidata relation lives under relations[] with type=="wikidata".
        // The target is a URL like "https://www.wikidata.org/wiki/Q1234"; we
        // strip to the Q-number.
        val wikidataId = node.path("relations").firstOrNull { rel ->
            rel.path("type").asText() == "wikidata"
        }?.path("url")?.textOrNull("resource")
            ?.substringAfterLast("/wiki/")
            ?.takeIf { it.startsWith("Q") && it.all { c -> c == 'Q' || c.isDigit() } }

        // MB's `disambiguation` is a short sentence like "American rock band".
        // We keep it around only when we have no better bio.
        val disambiguation = node.textOrNull("disambiguation")

        var dirty = false
        if (begin != null && artist.begin_date == null) { artist.begin_date = begin; dirty = true }
        if (end != null && artist.end_date == null) { artist.end_date = end; dirty = true }
        if (!wikidataId.isNullOrBlank() && artist.wikidata_id.isNullOrBlank()) {
            artist.wikidata_id = wikidataId; dirty = true
        }
        if (!disambiguation.isNullOrBlank() && artist.biography.isNullOrBlank()) {
            artist.biography = disambiguation; dirty = true
        }

        if (dirty) {
            artist.updated_at = LocalDateTime.now()
            artist.save()
            log.info("Enriched artist id={} '{}' from MusicBrainz", artist.id, artist.name)
        }
    }

    private fun needsWikipediaData(a: Artist): Boolean =
        a.biography.isNullOrBlank() ||
            (a.biography?.length ?: 0) < SHORT_BIO_THRESHOLD ||
            a.headshot_path.isNullOrBlank()

    private fun enrichFromWikipedia(artist: Artist) {
        val wikidataId = artist.wikidata_id ?: return

        val entityBody = fetch("$WIKIDATA_BASE/wiki/Special:EntityData/$wikidataId.json") ?: return
        val entity = mapper.readTree(entityBody)
        val enwikiTitle = entity.path("entities").path(wikidataId)
            .path("sitelinks").path("enwiki").path("title")
            .asText().takeIf { it.isNotBlank() } ?: return

        try { clock.sleep(WIKI_GAP) } catch (_: InterruptedException) { return }

        val encoded = URLEncoder.encode(enwikiTitle.replace(' ', '_'), Charsets.UTF_8)
        val summaryBody = fetch("$WIKIPEDIA_BASE/api/rest_v1/page/summary/$encoded") ?: return
        val summary = mapper.readTree(summaryBody)

        val extract = summary.path("extract").asText().takeIf { it.isNotBlank() }
        val thumbnail = summary.path("thumbnail").path("source").asText().takeIf { it.isNotBlank() }

        var dirty = false
        // Replace the biography only when Wikipedia's is substantive and
        // longer than what we already have (MB's `disambiguation` is often
        // just one sentence; Wikipedia's summary is a full paragraph).
        if (!extract.isNullOrBlank() && extract.length >= 100 &&
            (artist.biography.isNullOrBlank() || (artist.biography?.length ?: 0) < extract.length)
        ) {
            artist.biography = extract
            dirty = true
        }
        if (!thumbnail.isNullOrBlank() && artist.headshot_path.isNullOrBlank()) {
            artist.headshot_path = thumbnail
            dirty = true
        }

        if (dirty) {
            artist.updated_at = LocalDateTime.now()
            artist.save()
            log.info("Enriched artist id={} '{}' from Wikipedia ({})",
                artist.id, artist.name, enwikiTitle)
        }
    }

    private fun fetch(url: String): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response: HttpResponse<String> = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> response.body()
            404 -> null
            429, 503 -> { log.warn("Enrichment source rate limited ({}); skipping {}", response.statusCode(), url); null }
            else -> { log.warn("Enrichment source returned HTTP {} for {}", response.statusCode(), url); null }
        }
    }
}

