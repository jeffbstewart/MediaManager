package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * On-demand poster cache. First request fetches from TMDB CDN and saves to disk;
 * subsequent requests serve from local storage under data/poster-cache/.
 *
 * Directory is sharded two levels deep by UUID prefix to avoid huge flat directories:
 *   data/poster-cache/{ab}/{cd}/{uuid}.jpg
 */
object PosterCacheService {

    private val log = LoggerFactory.getLogger(PosterCacheService::class.java)

    private fun countCache(result: String) {
        MetricsRegistry.registry.counter("mm_poster_cache_total", "result", result).increment()
    }
    private val cacheRoot: Path = Path.of("data", "poster-cache")
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Maps a cache UUID to its sharded file path. Returns the path if the file exists, null otherwise. */
    fun resolve(cacheId: String): Path? {
        val path = shardedPath(cacheId)
        return if (Files.exists(path)) path else null
    }

    /**
     * Returns a cached poster file path, fetching from TMDB if necessary.
     *
     * - If poster_cache_id is set and the file exists on disk, returns it immediately.
     * - If poster_cache_id is set but file is missing (deleted from disk), reuses the UUID
     *   and re-fetches from TMDB.
     * - If no poster_cache_id at all, generates a new UUID, fetches, and updates the title.
     */
    fun cacheAndServe(title: Title, size: PosterSize): Path? {
        // If we've already cached a poster for this title (from any origin —
        // TMDB, embedded album art, Open Library …), serve the cached file
        // regardless of what `poster_path` points at.
        val existingCacheId = title.poster_cache_id
        if (existingCacheId != null) {
            val cached = resolve(existingCacheId)
            if (cached != null) {
                countCache("hit")
                return cached
            }
        }

        val posterPath = title.poster_path ?: return null

        // Auto-fetch from TMDB only for TMDB-shaped paths. Book ("isbn/...")
        // and album ("caa/...") sentinels are served by the caller via their
        // respective proxies when this returns null.
        if (!posterPath.startsWith("/")) return null

        val cacheId = existingCacheId ?: UUID.randomUUID().toString()
        val destPath = shardedPath(cacheId)

        // Fetch from TMDB CDN
        val tmdbUrl = "https://image.tmdb.org/t/p/${size.pathSegment}$posterPath"
        val fetched = fetchAndWrite(tmdbUrl, destPath)
        if (!fetched) {
            countCache("fetch_failed")
            return null
        }
        countCache("miss")

        // Persist the cache ID on the title if it was newly generated
        if (existingCacheId == null) {
            try {
                title.poster_cache_id = cacheId
                title.save()
            } catch (e: Exception) {
                log.warn("Failed to save poster_cache_id on title {}: {}", title.id, e.message)
            }
        }

        return destPath
    }

    /**
     * Write raw JPEG [bytes] into the cache for [title] and set its
     * `poster_cache_id`. Used for images we source locally (e.g. embedded
     * cover art extracted from a FLAC's PICTURE block) where there's no
     * upstream URL to fetch. Reuses any existing cache UUID so this
     * overwrites the previously stored poster for that title.
     */
    fun storeJpegBytes(title: Title, bytes: ByteArray): Path? {
        if (bytes.isEmpty()) return null
        val cacheId = title.poster_cache_id ?: UUID.randomUUID().toString()
        val destPath = shardedPath(cacheId)
        return try {
            Files.createDirectories(destPath.parent)
            Files.write(destPath, bytes)
            if (title.poster_cache_id == null) {
                title.poster_cache_id = cacheId
                title.save()
            }
            countCache("embedded")
            destPath
        } catch (e: Exception) {
            log.warn("Failed to write embedded poster for title {}: {}", title.id, e.message)
            null
        }
    }

    private fun fetchAndWrite(url: String, dest: Path): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                log.warn("TMDB poster fetch returned HTTP {} for {}", response.statusCode(), url)
                return false
            }

            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (!contentType.startsWith("image/")) {
                log.warn("TMDB poster fetch returned unexpected Content-Type '{}' for {}", contentType, url)
                return false
            }

            val body = response.body()
            if (body.isEmpty()) {
                log.warn("TMDB poster fetch returned empty body for {}", url)
                return false
            }

            Files.createDirectories(dest.parent)
            Files.write(dest, body)
            log.debug("Cached poster: {}", dest)
            true
        } catch (e: Exception) {
            log.error("Failed to fetch/write poster from {}: {}", url, e.message)
            false
        }
    }

    private fun shardedPath(uuid: String): Path {
        val clean = uuid.replace("-", "")
        val shard1 = clean.substring(0, 2)
        val shard2 = clean.substring(2, 4)
        return cacheRoot.resolve(shard1).resolve(shard2).resolve("$uuid.jpg")
    }
}
