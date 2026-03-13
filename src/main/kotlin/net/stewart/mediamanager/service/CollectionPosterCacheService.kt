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

    private val cacheRoot: Path = Path.of("data", "collection-poster-cache")
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** In-memory map of collection IDs that have been cached (avoids repeated disk checks). */
    private val cachedIds = ConcurrentHashMap<Int, Boolean>()

    fun cacheAndServe(collection: TmdbCollection): Path? {
        val posterPath = collection.poster_path ?: return null
        val collId = collection.tmdb_collection_id

        val destPath = cacheRoot.resolve("$collId.jpg")

        // Fast path: already cached
        if (cachedIds.containsKey(collId) && Files.exists(destPath)) {
            return destPath
        }

        if (Files.exists(destPath)) {
            cachedIds[collId] = true
            return destPath
        }

        // Fetch from TMDB CDN
        val url = "https://image.tmdb.org/t/p/w500$posterPath"
        val fetched = fetchAndWrite(url, destPath)
        if (!fetched) return null

        cachedIds[collId] = true
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
                log.warn("Collection poster fetch returned HTTP {} for {}", response.statusCode(), url)
                return false
            }

            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (!contentType.startsWith("image/")) {
                log.warn("Collection poster fetch returned unexpected Content-Type '{}' for {}", contentType, url)
                return false
            }

            val body = response.body()
            if (body.isEmpty()) {
                log.warn("Collection poster fetch returned empty body for {}", url)
                return false
            }

            Files.createDirectories(dest.parent)
            Files.write(dest, body)
            log.debug("Cached collection poster: {}", dest)
            true
        } catch (e: Exception) {
            log.error("Failed to fetch/write collection poster from {}: {}", url, e.message)
            false
        }
    }
}
