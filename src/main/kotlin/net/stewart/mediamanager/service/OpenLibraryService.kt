package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for [openlibrary.org](https://openlibrary.org) — free, no API key,
 * used to resolve ISBNs to work + edition + author metadata for book ingestion.
 * See docs/BOOKS.md.
 *
 * Intentionally narrow in scope: only the lookups M1 needs. Richer browse
 * features (author "other works", series enrichment) arrive in M2.
 */

/** One author parsed out of an Open Library work/edition. */
data class OpenLibraryAuthor(
    /** Open Library author ID, e.g. "OL34184A". */
    val openLibraryAuthorId: String,
    /** Display name as OL stores it ("Isaac Asimov"). */
    val name: String
)

/** Result of looking up an ISBN. */
data class OpenLibraryBookLookup(
    /** Open Library Work ID ("OL46125W"). The dedup key across editions. */
    val openLibraryWorkId: String,
    /** Open Library Edition ID ("OL7440033M"). Distinct per binding/printing. */
    val openLibraryEditionId: String,
    /** Canonical work title (without edition-specific suffixes like "(paperback)"). */
    val workTitle: String,
    /** Optional — the edition's ISBN-13 (falls back to ISBN-10 when 13 absent). */
    val isbn: String?,
    /** Edition format string as OL reports it ("Paperback", "Hardcover", "ebook", …). */
    val rawPhysicalFormat: String?,
    /** Our mapped [net.stewart.mediamanager.entity.MediaFormat] name, best-effort. */
    val mediaFormat: String?,
    /** Number of pages (edition-specific — differs by printing). */
    val pageCount: Int?,
    /** The edition's publish year ("2004"). */
    val editionYear: Int?,
    /** First publication year of the *work* ("1951"). Distinct from editionYear. */
    val firstPublicationYear: Int?,
    /** Work description, falling back to the edition's. */
    val description: String?,
    /** Cover image URL if OL has one. */
    val coverUrl: String?,
    /** Authors on the work, ordered as OL returns them (0 = primary). */
    val authors: List<OpenLibraryAuthor>,
    /** "<Name> #<Number>" series entries from the work, if any. Parsed into pairs. */
    val series: List<SeriesRef>,
    /** Raw JSON body for the /isbn endpoint — kept so scans are auditable. */
    val rawJson: String
) {
    data class SeriesRef(val name: String, val number: java.math.BigDecimal?)
}

sealed class OpenLibraryResult {
    data class Success(val book: OpenLibraryBookLookup) : OpenLibraryResult()
    data object NotFound : OpenLibraryResult()
    data class Error(val message: String, val rateLimited: Boolean = false) : OpenLibraryResult()
}

/** A single work from an author's bibliography (Open Library `/authors/{olid}/works.json`). */
data class AuthorWorkRef(
    val openLibraryWorkId: String,
    val title: String,
    val firstPublishYear: Int?,
    /** First associated ISBN — built from the OL cover ID if an ISBN isn't known, null otherwise. */
    val coverUrl: String?,
    /** Raw series string if OL has it ("Foundation #1"). Parsed by [parseSeriesLine] later. */
    val seriesRaw: String?
)

interface OpenLibraryService {
    fun lookupByIsbn(isbn: String): OpenLibraryResult

    /**
     * Returns up to [limit] works from an author's bibliography. Results come from
     * Open Library's `/authors/{olid}/works.json` endpoint, which is paginated but
     * the first page is sufficient for M3 UX. Returns an empty list on error —
     * callers treat "no bibliography" and "fetch failed" identically for UI.
     */
    fun listAuthorWorks(openLibraryAuthorId: String, limit: Int = 200): List<AuthorWorkRef>

    companion object {
        /**
         * Same-origin URL pointing at our Open Library image proxy for this
         * ISBN. The proxy (see ImageProxyHttpService + ImageProxyService)
         * enforces the SSRF guards and caches the bytes to disk, so clients
         * never talk to covers.openlibrary.org directly.
         */
        fun coverUrlByIsbn(isbn: String, size: CoverSize = CoverSize.L): String =
            "/proxy/ol/isbn/$isbn/${size.name}"

        /** Proxy URL for an OL numeric cover ID (what /authors/.../works.json returns). */
        fun coverUrlByCoverId(coverId: Long, size: CoverSize = CoverSize.M): String =
            "/proxy/ol/cover/$coverId/${size.name}"
    }

    enum class CoverSize { S, M, L }
}

class OpenLibraryHttpService : OpenLibraryService {

    private val log = LoggerFactory.getLogger(OpenLibraryHttpService::class.java)
    private val mapper = ObjectMapper()
    // OL's /isbn/{isbn}.json endpoint replies with a 302 to /books/OL...M.json;
    // NORMAL follows same-protocol redirects but blocks HTTPS→HTTP downgrades.
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun lookupByIsbn(isbn: String): OpenLibraryResult {
        if (!isbn.matches(Regex("^\\d{10}(\\d{3})?$"))) {
            return OpenLibraryResult.Error("ISBN format invalid: $isbn")
        }

        val url = "$BASE/isbn/$isbn.json"
        log.info("Open Library lookup for ISBN: {}", isbn)

        val editionBody = try {
            fetch(url) ?: return OpenLibraryResult.NotFound
        } catch (e: RateLimitedException) {
            return OpenLibraryResult.Error("rate limited", rateLimited = true)
        } catch (e: Exception) {
            log.error("ISBN fetch failed for {}: {}", isbn, e.message)
            return OpenLibraryResult.Error("fetch failed: ${e.message}")
        }

        return try {
            parse(isbn, editionBody, workFetcher = { key -> runCatching { fetch("$BASE$key.json") }.getOrNull() },
                authorFetcher = { id -> runCatching { fetchAuthorName(id) }.getOrNull() })
        } catch (e: Exception) {
            log.error("ISBN parse failed for {}: {}", isbn, e.message, e)
            OpenLibraryResult.Error("parse failed: ${e.message}")
        }
    }

    /**
     * Pure parse over already-fetched JSON. The two function parameters let
     * tests inject stub fetchers so the full parse path runs without touching
     * the network.
     */
    internal fun parse(
        isbn: String,
        editionBody: String,
        workFetcher: (String) -> String?,
        authorFetcher: (String) -> String?
    ): OpenLibraryResult {
        val edition = mapper.readTree(editionBody)

        val editionId = edition.olKey("key")?.removePrefix("/books/")
            ?: return OpenLibraryResult.Error("edition missing key")
        val workKey = edition.path("works").firstOrNull()?.olKey("key")
            ?: return OpenLibraryResult.Error("edition has no work reference")

        // Pull the work for description / first-publication / series. Best-effort
        // — if the work fetch fails we still return the edition-level data.
        val workJson = workFetcher(workKey)
        val work = workJson?.let { mapper.readTree(it) }

        val workId = workKey.removePrefix("/works/")
        val workTitle = work?.textOrNull("title") ?: edition.textOrNull("title") ?: "Untitled"
        val rawPhysical = edition.textOrNull("physical_format")
        val pageCount = edition.get("number_of_pages")?.takeIf { it.isInt }?.asInt()
        val editionYear = edition.textOrNull("publish_date")?.let { extractYear(it) }
        val firstPubYear = work?.textOrNull("first_publish_date")?.let { extractYear(it) }
        val description = workOrEditionDescription(work, edition)

        val authorIds = (work?.path("authors") ?: edition.path("authors"))
            .mapNotNull { node ->
                node.path("author").olKey("key")
                    ?: node.olKey("key")
            }
            .map { it.removePrefix("/authors/") }

        val authors = authorIds.map { id ->
            OpenLibraryAuthor(id, authorFetcher(id) ?: "Unknown Author")
        }

        val series = parseSeries(work?.get("series"))

        return OpenLibraryResult.Success(OpenLibraryBookLookup(
            openLibraryWorkId = workId,
            openLibraryEditionId = editionId,
            workTitle = workTitle,
            isbn = isbn,
            rawPhysicalFormat = rawPhysical,
            mediaFormat = mapPhysicalFormat(rawPhysical),
            pageCount = pageCount,
            editionYear = editionYear,
            firstPublicationYear = firstPubYear,
            description = description,
            coverUrl = OpenLibraryService.coverUrlByIsbn(isbn),
            authors = authors,
            series = series,
            rawJson = editionBody
        ))
    }

    private fun fetchAuthorName(authorId: String): String? {
        val body = fetch("$BASE/authors/$authorId.json") ?: return null
        return mapper.readTree(body).textOrNull("name")
    }

    /** Very small in-process cache for author bibliographies. ~1 hour TTL. */
    private data class WorksCacheEntry(val works: List<AuthorWorkRef>, val fetchedAt: Long)
    private val worksCache = java.util.concurrent.ConcurrentHashMap<String, WorksCacheEntry>()
    private val worksCacheTtlMs: Long = 60 * 60 * 1000L

    override fun listAuthorWorks(openLibraryAuthorId: String, limit: Int): List<AuthorWorkRef> {
        val cached = worksCache[openLibraryAuthorId]
        if (cached != null && (System.currentTimeMillis() - cached.fetchedAt) < worksCacheTtlMs) {
            return cached.works.take(limit)
        }

        val body = try {
            fetch("$BASE/authors/$openLibraryAuthorId/works.json?limit=$limit")
        } catch (e: Exception) {
            log.warn("listAuthorWorks failed for {}: {}", openLibraryAuthorId, e.message)
            null
        } ?: return emptyList()

        val works = try {
            parseAuthorWorks(body)
        } catch (e: Exception) {
            log.warn("listAuthorWorks parse failed for {}: {}", openLibraryAuthorId, e.message)
            emptyList()
        }

        worksCache[openLibraryAuthorId] = WorksCacheEntry(works, System.currentTimeMillis())
        return works.take(limit)
    }

    internal fun parseAuthorWorks(body: String): List<AuthorWorkRef> {
        val root = mapper.readTree(body)
        val entries = root.path("entries")
        if (!entries.isArray) return emptyList()
        val out = mutableListOf<AuthorWorkRef>()
        for (entry in entries) {
            val key = entry.olKey("key")?.removePrefix("/works/") ?: continue
            val title = entry.textOrNull("title") ?: continue
            val year = entry.textOrNull("first_publish_date")?.let { extractYear(it) }

            val covers = entry.path("covers")
            val coverUrl = if (covers.isArray && !covers.isEmpty) {
                covers.firstOrNull { it.isNumber && it.asLong() > 0 }?.asLong()
                    ?.let { OpenLibraryService.coverUrlByCoverId(it) }
            } else null

            val seriesNode = entry.path("series")
            val seriesRaw = if (seriesNode.isArray && !seriesNode.isEmpty) {
                seriesNode.firstOrNull()?.asText()?.takeIf { it.isNotBlank() }
            } else null

            out += AuthorWorkRef(
                openLibraryWorkId = key,
                title = title,
                firstPublishYear = year,
                coverUrl = coverUrl,
                seriesRaw = seriesRaw
            )
        }
        return out
    }

    /** Sends an HTTP GET; returns null on 404, throws [RateLimitedException] on 429. */
    private fun fetch(url: String): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response: HttpResponse<String> =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> response.body()
            404 -> null
            429 -> throw RateLimitedException()
            else -> throw RuntimeException("HTTP ${response.statusCode()} from $url")
        }
    }

    companion object {
        private const val BASE = "https://openlibrary.org"
        private const val USER_AGENT = "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
    }

    private class RateLimitedException : RuntimeException("rate limited")
}

// ---- Parsing helpers, shared with tests ----

internal fun JsonNode.olKey(field: String): String? {
    val node = this.get(field) ?: return null
    return if (node.isTextual) node.asText() else null
}

internal fun JsonNode.textOrNull(field: String): String? {
    val node = this.get(field) ?: return null
    if (node.isNull) return null
    if (node.isTextual) return node.asText().ifBlank { null }
    // Some OL fields are objects like { "type": "/type/text", "value": "…" }
    val inner = node.get("value") ?: return null
    return if (inner.isTextual) inner.asText().ifBlank { null } else null
}

private fun workOrEditionDescription(work: JsonNode?, edition: JsonNode): String? {
    return work?.textOrNull("description") ?: edition.textOrNull("description")
}

internal fun extractYear(raw: String): Int? {
    val match = Regex("(\\d{4})").find(raw) ?: return null
    val year = match.groupValues[1].toInt()
    return if (year in 1400..2100) year else null
}

/**
 * Maps OL's `physical_format` string onto our [net.stewart.mediamanager.entity.MediaFormat]
 * enum name. Returns null if unknown — the scan path treats null as "needs user choice".
 */
internal fun mapPhysicalFormat(raw: String?): String? {
    val s = raw?.trim()?.lowercase() ?: return null
    return when {
        "mass market" in s || "mass-market" in s -> "MASS_MARKET_PAPERBACK"
        "hardcover" in s || "hardback" in s || "hardbound" in s -> "HARDBACK"
        "paperback" in s || "softcover" in s || "trade paper" in s -> "TRADE_PAPERBACK"
        "ebook" in s || "epub" in s -> "EBOOK_EPUB"
        "pdf" in s -> "EBOOK_PDF"
        "audio cd" in s || "audiocassette" in s || "audio cassette" in s -> "AUDIOBOOK_CD"
        "audiobook" in s || "audio book" in s -> "AUDIOBOOK_DIGITAL"
        else -> null
    }
}

/**
 * OL's `series` on a work is an array of free-text strings like "Foundation #1",
 * "The Foundation Series, #1", sometimes with no number. We parse out the name
 * and an optional number.
 */
internal fun parseSeries(node: JsonNode?): List<OpenLibraryBookLookup.SeriesRef> {
    if (node == null || !node.isArray) return emptyList()
    val seen = HashSet<String>()
    val out = mutableListOf<OpenLibraryBookLookup.SeriesRef>()
    for (entry in node) {
        val s = entry.asText()?.trim().orEmpty()
        if (s.isEmpty()) continue
        val (name, number) = parseSeriesLine(s)
        val key = name.lowercase()
        if (!seen.add(key)) continue
        out += OpenLibraryBookLookup.SeriesRef(name, number)
    }
    return out
}

internal fun parseSeriesLine(raw: String): Pair<String, java.math.BigDecimal?> {
    // Common shapes: "Foundation #1", "The Foundation Series, #1",
    // "Foundation Saga, 1", "Foundation (Book 1)".
    val hashMatch = Regex("(.*?)(?:,\\s*)?#\\s*([0-9]+(?:\\.[0-9]+)?)").find(raw)
    if (hashMatch != null) {
        val name = hashMatch.groupValues[1].trim().trimEnd(',').trim()
        val num = hashMatch.groupValues[2].toBigDecimalOrNull()
        if (name.isNotEmpty()) return name to num
    }
    val bookMatch = Regex("(.+?)\\s*\\(book\\s*([0-9]+(?:\\.[0-9]+)?)\\)", RegexOption.IGNORE_CASE).find(raw)
    if (bookMatch != null) {
        return bookMatch.groupValues[1].trim() to bookMatch.groupValues[2].toBigDecimalOrNull()
    }
    return raw to null
}
