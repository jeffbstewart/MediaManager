package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.Artist
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * On-demand artist headshot cache. Mirrors [AuthorHeadshotCacheService] —
 * source URL lives in [Artist.headshot_path], populated by the future
 * [ArtistEnrichmentAgent] from Wikipedia's page-summary API. Served as
 * `/artist-headshots/{id}` so no Wikimedia URL is ever handed to the
 * browser (CSP img-src 'self' stays strict).
 *
 * Keyed by artist id — the DB row is stable, so no separate UUID column.
 */
object ArtistHeadshotCacheService {

    private val log = LoggerFactory.getLogger(ArtistHeadshotCacheService::class.java)

    private val cacheRoot: Path = Path.of("data", "artist-headshot-cache")

    /** Hosts we'll source artist headshots from. Expanded only by code change. */
    private val ALLOWED_HOSTS = setOf("upload.wikimedia.org")

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Returns a cached headshot file path, fetching from the upstream URL in
     * [Artist.headshot_path] if necessary. Returns null when the artist has
     * no headshot URL or the upstream fetch fails.
     */
    fun cacheAndServe(artist: Artist): Path? {
        val artistId = artist.id ?: return null
        val source = artist.headshot_path?.takeIf { it.isNotBlank() } ?: return null

        val dest = shardedPath(artistId)
        if (Files.exists(dest)) return dest

        val host = runCatching { URI.create(source).host }.getOrNull()
        if (host == null || host !in ALLOWED_HOSTS) {
            log.warn("Artist headshot URL host not allowlisted: artist={} url={}", artistId, source)
            return null
        }
        val reject = ImageProxyService.resolveAndScreenHost(host)
        if (reject != null) {
            log.warn("Artist headshot host screen rejected: artist={} reason={}", artistId, reject)
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
                log.warn("Artist headshot fetch HTTP {} for {}", response.statusCode(), url)
                return false
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (!contentType.startsWith("image/")) {
                log.warn("Artist headshot fetch unexpected Content-Type '{}' for {}", contentType, url)
                return false
            }
            val body = response.body()
            if (body.isEmpty()) return false

            Files.createDirectories(dest.parent)
            Files.write(dest, body)
            true
        } catch (e: Exception) {
            log.warn("Artist headshot fetch failed {}: {}", url, e.message)
            false
        }
    }

    private fun shardedPath(artistId: Long): Path {
        val id = artistId.toString().padStart(6, '0')
        val shard1 = id.takeLast(2)
        val shard2 = id.takeLast(4).take(2)
        return cacheRoot.resolve(shard1).resolve(shard2).resolve("$artistId.jpg")
    }

    private const val USER_AGENT =
        "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
}
