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

    private val cacheRoot: Path = Path.of("data", "author-headshot-cache")

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

        val dest = shardedPath(authorId)
        if (Files.exists(dest)) return dest

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

        return if (fetchAndWrite(source, dest)) dest else null
    }

    private fun fetchAndWrite(url: String, dest: Path): Boolean {
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
                return false
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (!contentType.startsWith("image/")) {
                log.warn("Author headshot fetch unexpected Content-Type '{}' for {}", contentType, url)
                return false
            }
            val body = response.body()
            if (body.isEmpty()) return false

            Files.createDirectories(dest.parent)
            Files.write(dest, body)
            true
        } catch (e: Exception) {
            log.warn("Author headshot fetch failed {}: {}", url, e.message)
            false
        }
    }

    private fun shardedPath(authorId: Long): Path {
        val id = authorId.toString().padStart(6, '0')
        val shard1 = id.takeLast(2)
        val shard2 = id.takeLast(4).take(2)
        // Content-Type from Wikimedia is consistently image/jpeg for thumb
        // URLs; even PNG sources are rasterized to JPEG by the thumbor-like
        // service. .jpg on disk keeps Content-Type resolution trivial.
        return cacheRoot.resolve(shard1).resolve(shard2).resolve("$authorId.jpg")
    }

    private const val USER_AGENT =
        "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
}
