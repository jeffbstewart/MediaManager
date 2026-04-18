package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Author
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Background daemon that fills in author biographies and metadata from
 * Open Library. See docs/BOOKS.md.
 *
 * Target rows: [Author]s with `biography IS NULL` and a set
 * `open_library_author_id`. On each cycle the agent picks up to
 * [BATCH_SIZE] such authors, fetches
 * `https://openlibrary.org/authors/{olid}.json` for each with a 1 rps
 * pause between requests, and fills biography, wikidata_id, and
 * birth / death dates when present.
 *
 * Wikipedia enrichment (richer bios, higher-quality headshots via the
 * `wikidata_id` resolved here) is a separate, later job — not in M2.
 */
class AuthorEnrichmentAgent(
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(AuthorEnrichmentAgent::class.java)
    internal val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    companion object {
        private val CYCLE_INTERVAL = 1.hours
        private val API_GAP = 1.seconds
        private val STARTUP_DELAY = 30.seconds
        private const val BATCH_SIZE = 20
        private const val BASE = "https://openlibrary.org"
        private const val WIKIDATA_BASE = "https://www.wikidata.org"
        private const val WIKIPEDIA_BASE = "https://en.wikipedia.org"
        private const val USER_AGENT = "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"

        /** Bios under this length trigger Wikipedia fallback to pick up a richer extract. */
        private const val SHORT_BIO_THRESHOLD = 200
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("AuthorEnrichmentAgent started (cycle every {}m, batch {})",
                CYCLE_INTERVAL.inWholeMinutes, BATCH_SIZE)
            try { clock.sleep(STARTUP_DELAY) } catch (_: InterruptedException) { return@Thread }
            while (running.get()) {
                try {
                    enrichBatch()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("AuthorEnrichmentAgent error: {}", e.message, e)
                }
                try { clock.sleep(CYCLE_INTERVAL) } catch (_: InterruptedException) { break }
            }
            log.info("AuthorEnrichmentAgent stopped")
        }, "author-enrichment").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    internal fun enrichBatch() {
        val candidates = Author.findAll()
            .asSequence()
            .filter { needsEnrichment(it) }
            .take(BATCH_SIZE)
            .toList()

        if (candidates.isEmpty()) {
            log.debug("No authors need enrichment")
            return
        }
        log.info("Enriching {} author(s)", candidates.size)

        for ((i, author) in candidates.withIndex()) {
            if (!running.get()) break
            if (i > 0) {
                try { clock.sleep(API_GAP) } catch (_: InterruptedException) { break }
            }
            try {
                enrichOne(author)
            } catch (e: Exception) {
                log.warn("Enrichment failed for author id={} olid={}: {}",
                    author.id, author.open_library_author_id, e.message)
            }
        }
    }

    /**
     * Pick up authors missing any of: bio (from OL), wikidata_id (from OL),
     * or when a wikidata_id is set, missing a good-length bio or a headshot
     * (which Wikipedia's richer data can fill).
     */
    internal fun needsEnrichment(a: Author): Boolean {
        val olHasWork = !a.open_library_author_id.isNullOrBlank()
        val missingFromOl = olHasWork && (
            a.biography.isNullOrBlank() ||
                a.wikidata_id.isNullOrBlank() ||
                a.birth_date == null
        )
        val canUseWikipedia = !a.wikidata_id.isNullOrBlank() && (
            a.biography.isNullOrBlank() ||
                (a.biography?.length ?: 0) < SHORT_BIO_THRESHOLD ||
                a.headshot_path.isNullOrBlank()
        )
        return missingFromOl || canUseWikipedia
    }

    internal fun enrichOne(author: Author) {
        enrichFromOpenLibrary(author)
        if (!author.wikidata_id.isNullOrBlank() && needsWikipediaData(author)) {
            // Space the Wikidata/Wikipedia calls away from the previous OL call.
            try { clock.sleep(API_GAP) } catch (_: InterruptedException) { return }
            enrichFromWikipedia(author)
        }
    }

    private fun enrichFromOpenLibrary(author: Author) {
        val olid = author.open_library_author_id ?: return
        val body = fetch("$BASE/authors/$olid.json") ?: return
        val node = mapper.readTree(body)

        val bio = extractBio(node)
        val wikidataId = node.path("remote_ids").path("wikidata").asText().takeIf { it.isNotBlank() }
        val birth = parseIsoLocalDate(node.path("birth_date").asText())
        val death = parseIsoLocalDate(node.path("death_date").asText())

        var dirty = false
        if (!bio.isNullOrBlank() && author.biography.isNullOrBlank()) {
            author.biography = bio; dirty = true
        }
        if (!wikidataId.isNullOrBlank() && author.wikidata_id.isNullOrBlank()) {
            author.wikidata_id = wikidataId; dirty = true
        }
        if (birth != null && author.birth_date == null) { author.birth_date = birth; dirty = true }
        if (death != null && author.death_date == null) { author.death_date = death; dirty = true }

        if (dirty) {
            author.updated_at = LocalDateTime.now()
            author.save()
            log.info("Enriched author id={} '{}' from Open Library", author.id, author.name)
        }
    }

    private fun needsWikipediaData(a: Author): Boolean =
        a.biography.isNullOrBlank() ||
            (a.biography?.length ?: 0) < SHORT_BIO_THRESHOLD ||
            a.headshot_path.isNullOrBlank()

    /**
     * Resolve the wikidata_id to an English Wikipedia page, then fetch that
     * page's REST summary for an extract and thumbnail. Fills biography if
     * the current one is short/empty; sets headshot_path if none set.
     */
    private fun enrichFromWikipedia(author: Author) {
        val wikidataId = author.wikidata_id ?: return

        val entityBody = fetch("$WIKIDATA_BASE/wiki/Special:EntityData/$wikidataId.json") ?: return
        val entity = mapper.readTree(entityBody)
        val enwikiTitle = entity.path("entities").path(wikidataId).path("sitelinks").path("enwiki").path("title")
            .asText().takeIf { it.isNotBlank() } ?: return

        try { clock.sleep(API_GAP) } catch (_: InterruptedException) { return }

        val encoded = URLEncoder.encode(enwikiTitle.replace(' ', '_'), Charsets.UTF_8)
        val summaryBody = fetch("$WIKIPEDIA_BASE/api/rest_v1/page/summary/$encoded") ?: return
        val summary = mapper.readTree(summaryBody)

        val extract = summary.path("extract").asText().takeIf { it.isNotBlank() }
        val thumbnail = summary.path("thumbnail").path("source").asText().takeIf { it.isNotBlank() }

        var dirty = false
        // Replace bio only when the current one is short or missing, and
        // Wikipedia's is substantive. Avoid overwriting a long OL bio
        // with a shorter Wikipedia extract.
        if (!extract.isNullOrBlank() && extract.length >= 100 &&
            (author.biography.isNullOrBlank() || (author.biography?.length ?: 0) < extract.length)
        ) {
            author.biography = extract
            dirty = true
        }
        if (!thumbnail.isNullOrBlank() && author.headshot_path.isNullOrBlank()) {
            author.headshot_path = thumbnail
            dirty = true
        }

        if (dirty) {
            author.updated_at = LocalDateTime.now()
            author.save()
            log.info("Enriched author id={} '{}' from Wikipedia ({})",
                author.id, author.name, enwikiTitle)
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
            429 -> { log.warn("Open Library rate limited; skipping this cycle"); null }
            else -> { log.warn("Open Library returned HTTP {} for {}", response.statusCode(), url); null }
        }
    }

    /**
     * `bio` on a work/author is sometimes a plain string, sometimes
     * a `{"type":"/type/text","value":"..."}` object. Handle both.
     */
    internal fun extractBio(node: com.fasterxml.jackson.databind.JsonNode): String? {
        val bio = node.get("bio") ?: return null
        return when {
            bio.isNull -> null
            bio.isTextual -> bio.asText().ifBlank { null }
            bio.isObject -> bio.path("value").asText().ifBlank { null }
            else -> null
        }
    }

    internal fun parseIsoLocalDate(raw: String): LocalDate? {
        if (raw.isBlank()) return null
        return runCatching { LocalDate.parse(raw) }.getOrNull()
    }
}
