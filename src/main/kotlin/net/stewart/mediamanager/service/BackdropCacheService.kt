package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * On-demand backdrop cache. First request fetches from TMDB CDN and saves to disk;
 * subsequent requests serve from local storage under data/internet-images/.
 *
 * Uses w1280 size (largest useful backdrop size — `original` is often 4000px+, wasteful).
 *
 * Caching a backdrop also computes its hero tint ([BackdropTint]) while the
 * bytes are in hand, and records it on the title row and in the image's
 * sidecar. Backdrops cached before tints existed are caught up by
 * [BackdropTintBackfill].
 *
 * Modeled after PosterCacheService.
 */
object BackdropCacheService {

    private val log = LoggerFactory.getLogger(BackdropCacheService::class.java)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Returns the cached file path for a cacheId if present on disk, else null. */
    fun resolve(cacheId: String): Path? =
        InternetImageStore.getImage(InternetImageStore.Provider.TMDB_BACKDROP, cacheId)

    /**
     * Returns a cached backdrop file path, fetching from TMDB if necessary.
     *
     * - If backdrop_cache_id is set and the file exists on disk, returns it immediately.
     * - If backdrop_cache_id is set but file is missing, reuses the UUID and re-fetches.
     * - If no backdrop_cache_id, generates a new UUID, fetches, and updates the title.
     * - Returns null if the title has no backdrop_path (no backdrop available on TMDB).
     *
     * A fetch also computes the backdrop's tint and stores it on the row.
     */
    fun cacheAndServe(title: Title): Path? {
        val backdropPath = title.backdrop_path ?: return null

        val existingCacheId = title.backdrop_cache_id
        if (existingCacheId != null) {
            val cached = resolve(existingCacheId)
            if (cached != null) return cached
        }

        val cacheId = existingCacheId ?: UUID.randomUUID().toString()

        val tmdbUrl = "https://image.tmdb.org/t/p/w1280$backdropPath"
        val bytes = fetchBytes(tmdbUrl) ?: return null
        val tint = BackdropTint.fromBytes(bytes) ?: BackdropTint.FALLBACK

        val destPath = InternetImageStore.commit(
            provider = InternetImageStore.Provider.TMDB_BACKDROP,
            cacheKey = cacheId,
            bytes = bytes,
            contentType = "image/jpeg",
            upstreamUrl = tmdbUrl,
            subjectType = "title",
            subjectId = title.id,
            tint = tint
        )

        // A targeted update rather than title.save(): the caller's Title may
        // be a cached or partially stale copy, and only these two columns
        // changed. Failure here is non-fatal; the image still serves and the
        // backfill will set the tint later.
        try {
            JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createUpdate(
                    "UPDATE title SET backdrop_cache_id = :cacheId, backdrop_tint = :tint WHERE id = :id")
                    .bind("cacheId", cacheId)
                    .bind("tint", tint)
                    .bind("id", title.id)
                    .execute()
            }
            title.backdrop_cache_id = cacheId
            title.backdrop_tint = tint
        } catch (e: Exception) {
            log.warn("Failed to save backdrop cache id and tint on title {}: {}", title.id, e.message)
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
                log.warn("TMDB backdrop fetch returned HTTP {} for {}", response.statusCode(), url)
                return null
            }

            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (!contentType.startsWith("image/")) {
                log.warn("TMDB backdrop fetch returned unexpected Content-Type '{}' for {}", contentType, url)
                return null
            }

            val body = response.body()
            if (body.isEmpty()) {
                log.warn("TMDB backdrop fetch returned empty body for {}", url)
                return null
            }
            body
        } catch (e: Exception) {
            log.error("Failed to fetch backdrop from {}: {}", url, e.message)
            null
        }
    }
}
