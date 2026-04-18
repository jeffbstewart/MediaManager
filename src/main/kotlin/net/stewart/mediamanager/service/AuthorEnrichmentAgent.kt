package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Author
import org.slf4j.LoggerFactory
import java.net.URI
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
        private const val USER_AGENT = "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
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
            .filter { it.biography.isNullOrBlank() && !it.open_library_author_id.isNullOrBlank() }
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

    internal fun enrichOne(author: Author) {
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
            log.info("Enriched author id={} '{}'", author.id, author.name)
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
