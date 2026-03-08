package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.CastMember
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
 * On-demand headshot cache. First request fetches from TMDB CDN and saves to disk;
 * subsequent requests serve from local storage under data/headshot-cache/.
 *
 * Directory is sharded two levels deep by UUID prefix to avoid huge flat directories:
 *   data/headshot-cache/{ab}/{cd}/{uuid}.jpg
 *
 * Modeled after PosterCacheService.
 */
object HeadshotCacheService {

    private val log = LoggerFactory.getLogger(HeadshotCacheService::class.java)
    private val cacheRoot: Path = Path.of("data", "headshot-cache")
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
     * Returns a cached headshot file path, fetching from TMDB if necessary.
     *
     * - If headshot_cache_id is set and the file exists on disk, returns it immediately.
     * - If headshot_cache_id is set but file is missing, reuses the UUID and re-fetches.
     * - If no headshot_cache_id, generates a new UUID, fetches, and updates the cast member.
     * - Returns null if the cast member has no profile_path (no headshot available on TMDB).
     */
    fun cacheAndServe(castMember: CastMember): Path? {
        val profilePath = castMember.profile_path ?: return null

        val existingCacheId = castMember.headshot_cache_id
        if (existingCacheId != null) {
            val cached = resolve(existingCacheId)
            if (cached != null) return cached
        }

        val cacheId = existingCacheId ?: UUID.randomUUID().toString()
        val destPath = shardedPath(cacheId)

        // Fetch from TMDB CDN (w185 size for headshots)
        val tmdbUrl = "https://image.tmdb.org/t/p/w185$profilePath"
        val fetched = fetchAndWrite(tmdbUrl, destPath)
        if (!fetched) return null

        // Persist the cache ID on the cast member if it was newly generated
        if (existingCacheId == null) {
            try {
                castMember.headshot_cache_id = cacheId
                castMember.save()
            } catch (e: Exception) {
                log.warn("Failed to save headshot_cache_id on cast member {}: {}", castMember.id, e.message)
            }
        }

        return destPath
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
                log.warn("TMDB headshot fetch returned HTTP {} for {}", response.statusCode(), url)
                return false
            }

            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (!contentType.startsWith("image/")) {
                log.warn("TMDB headshot fetch returned unexpected Content-Type '{}' for {}", contentType, url)
                return false
            }

            val body = response.body()
            if (body.isEmpty()) {
                log.warn("TMDB headshot fetch returned empty body for {}", url)
                return false
            }

            Files.createDirectories(dest.parent)
            Files.write(dest, body)
            log.debug("Cached headshot: {}", dest)
            true
        } catch (e: Exception) {
            log.error("Failed to fetch/write headshot from {}: {}", url, e.message)
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
