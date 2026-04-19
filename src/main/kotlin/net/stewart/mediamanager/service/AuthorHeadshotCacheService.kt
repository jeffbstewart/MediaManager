package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.Author
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * On-demand author headshot cache. The source URL lives in
 * [Author.headshot_path] and is set by [AuthorEnrichmentAgent] from
 * Wikipedia's page-summary API — i.e. an `upload.wikimedia.org` URL.
 *
 * Serves from `data/author-headshot-cache/` after a first fetch so we
 * never hand a third-party CDN URL to the browser (both for CSP and to
 * avoid leaking user IPs). Host is allowlisted before fetching; the
 * resolved address is screened for private ranges via
 * [ImageProxyService.resolveAndScreenHost].
 *
 * Keyed by author id — the DB row is stable for the lifetime of the
 * author record, so we don't need a separate UUID column.
 */
object AuthorHeadshotCacheService {

    private val log = LoggerFactory.getLogger(AuthorHeadshotCacheService::class.java)


    /** Hosts we'll source author headshots from. Expanded only by code change. */
    private val ALLOWED_HOSTS = setOf("upload.wikimedia.org")

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Returns a cached headshot file path, fetching from the upstream URL
     * stored in [Author.headshot_path] if necessary. Returns null when the
     * author has no headshot URL or when the upstream fetch fails.
     */
    fun cacheAndServe(author: Author): Path? {
        val authorId = author.id ?: return null
        val source = author.headshot_path?.takeIf { it.isNotBlank() } ?: return null

        val cacheKey = authorId.toString()
        val existing = InternetImageStore.getImage(
            InternetImageStore.Provider.WIKIMEDIA_AUTHOR, cacheKey
        )
        if (existing != null) return existing

        val host = runCatching { URI.create(source).host }.getOrNull()
        if (host == null || host !in ALLOWED_HOSTS) {
            log.warn("Author headshot URL host not allowlisted: author={} url={}", authorId, source)
            return null
        }
        val reject = ImageProxyService.resolveAndScreenHost(host)
        if (reject != null) {
            log.warn("Author headshot host screen rejected: author={} reason={}", authorId, reject)
            return null
        }

        val bytes = fetchBytes(source) ?: return null
        return InternetImageStore.commit(
            provider = InternetImageStore.Provider.WIKIMEDIA_AUTHOR,
            cacheKey = cacheKey,
            bytes = bytes,
            contentType = "image/jpeg",
            upstreamUrl = source,
            subjectType = "author",
            subjectId = authorId
        )
    }

    private fun fetchBytes(url: String): ByteArray? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/*")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                log.warn("Author headshot fetch HTTP {} for {}", response.statusCode(), url)
                return null
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (!contentType.startsWith("image/")) {
                log.warn("Author headshot fetch unexpected Content-Type '{}' for {}", contentType, url)
                return null
            }
            val body = response.body()
            if (body.isEmpty()) return null
            body
        } catch (e: Exception) {
            log.warn("Author headshot fetch failed {}: {}", url, e.message)
            null
        }
    }

    private const val USER_AGENT =
        "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
}
