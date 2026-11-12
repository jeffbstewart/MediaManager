package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.TmdbCollection
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * On-demand collection poster cache. Fetches from TMDB CDN on first request,
 * serves from local disk cache thereafter.
 *
 * Cached under data/collection-poster-cache/{tmdbCollectionId}.jpg
 */
object CollectionPosterCacheService {

    private val log = LoggerFactory.getLogger(CollectionPosterCacheService::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** In-memory set of collection IDs that have been cached (avoids repeated disk checks). */
    private val cachedIds = ConcurrentHashMap<Int, Boolean>()

    fun cacheAndServe(collection: TmdbCollection): Path? {
        val posterPath = collection.poster_path ?: return null
        val collId = collection.tmdb_collection_id
        val cacheKey = collId.toString()

        // Fast path: cache hit — either in the in-memory set or confirmed on disk.
        if (cachedIds.containsKey(collId)) {
            val existing = InternetImageStore.getImage(InternetImageStore.Provider.TMDB_COLLECTION, cacheKey)
            if (existing != null) return existing
        }

        val existing = InternetImageStore.getImage(InternetImageStore.Provider.TMDB_COLLECTION, cacheKey)
        if (existing != null) {
            cachedIds[collId] = true
            return existing
        }

        // Fetch from TMDB CDN
        val url = "https://image.tmdb.org/t/p/w500$posterPath"
        val bytes = fetchBytes(url) ?: return null

        val destPath = InternetImageStore.commit(
            provider = InternetImageStore.Provider.TMDB_COLLECTION,
            cacheKey = cacheKey,
            bytes = bytes,
            contentType = "image/jpeg",
            upstreamUrl = url,
            subjectType = "tmdb_collection",
            subjectId = collId.toLong()
        )
        cachedIds[collId] = true
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
                log.warn("Collection poster fetch returned HTTP {} for {}", response.statusCode(), url)
                return null
            }

            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (!contentType.startsWith("image/")) {
                log.warn("Collection poster fetch returned unexpected Content-Type '{}' for {}", contentType, url)
                return null
            }

            val body = response.body()
            if (body.isEmpty()) {
                log.warn("Collection poster fetch returned empty body for {}", url)
                return null
            }
            body
        } catch (e: Exception) {
            log.error("Failed to fetch collection poster from {}: {}", url, e.message)
            null
        }
    }
}
