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
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Returns the cached file path for a cacheId if present on disk, else null. */
    fun resolve(cacheId: String): Path? =
        InternetImageStore.getImage(InternetImageStore.Provider.TMDB_HEADSHOT, cacheId)

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

        val tmdbUrl = "https://image.tmdb.org/t/p/w185$profilePath"
        val bytes = fetchBytes(tmdbUrl) ?: return null

        val destPath = InternetImageStore.commit(
            provider = InternetImageStore.Provider.TMDB_HEADSHOT,
            cacheKey = cacheId,
            bytes = bytes,
            contentType = "image/jpeg",
            upstreamUrl = tmdbUrl,
            subjectType = "cast_member",
            subjectId = castMember.id
        )

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

    private fun fetchBytes(url: String): ByteArray? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                log.warn("TMDB headshot fetch returned HTTP {} for {}", response.statusCode(), url)
                return null
            }

            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (!contentType.startsWith("image/")) {
                log.warn("TMDB headshot fetch returned unexpected Content-Type '{}' for {}", contentType, url)
                return null
            }

            val body = response.body()
            if (body.isEmpty()) {
                log.warn("TMDB headshot fetch returned empty body for {}", url)
                return null
            }
            body
        } catch (e: Exception) {
            log.error("Failed to fetch headshot from {}: {}", url, e.message)
            null
        }
    }
}
