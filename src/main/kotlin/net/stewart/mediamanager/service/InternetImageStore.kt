package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Unified content-addressable store for internet-sourced cached images.
 *
 * Layout:
 *   data/internet-images/{provider}/{ab}/{cd}/{cache_key}.{ext}
 *   data/internet-images/{provider}/{ab}/{cd}/{cache_key}.meta.json
 *
 * `{ab}/{cd}` are the first four hex characters of `sha256(cache_key)` so
 * fan-out stays 256×256 regardless of whether the provider keys by UUID,
 * MBID, sequential int, or URL hash. See docs/IMAGE_CACHE_MIGRATION.md.
 *
 * Providers live under independent subtrees so phase-2 migration of one
 * cache can ship without touching the others. See the `Provider` pseudo-
 * enum below for the canonical labels.
 *
 * Safe for concurrent writes: `commit()` stages to a `.tmp` file and
 * atomic-renames into place. The sidecar goes in after the image so a
 * crash can't leave metadata pointing at missing bytes; a missing sidecar
 * is fixed on the next backfill pass.
 */
object InternetImageStore {

    private val log = LoggerFactory.getLogger(InternetImageStore::class.java)

    val root: Path = Path.of("data", "internet-images")

    /**
     * Canonical provider labels. These match the `provider` field in
     * [ImageMetadata] sidecars so a walker can locate files by provider
     * without consulting the DB.
     */
    object Provider {
        const val TMDB_POSTER = "tmdb-poster"
        const val TMDB_BACKDROP = "tmdb-backdrop"
        const val TMDB_HEADSHOT = "tmdb-headshot"
        const val TMDB_COLLECTION = "tmdb-collection"
        const val WIKIMEDIA_ARTIST = "wikimedia-artist"
        const val WIKIMEDIA_AUTHOR = "wikimedia-author"
        const val EMBEDDED_COVER = "embedded-cover"
        const val PROXY_TMDB = "proxy-tmdb"
        const val PROXY_OL = "proxy-ol"
        const val PROXY_CAA = "proxy-caa"
    }

    /**
     * Compute the on-disk path for a given (provider, cacheKey, extension).
     * Does not check whether the file exists — use [getImage] for that.
     */
    fun pathFor(provider: String, cacheKey: String, extension: String = "jpg"): Path {
        val hash = sha256Hex(cacheKey)
        val shard1 = hash.substring(0, 2)
        val shard2 = hash.substring(2, 4)
        return root.resolve(provider).resolve(shard1).resolve(shard2).resolve("$cacheKey.$extension")
    }

    /** Returns the cached file path if it exists, or null. */
    fun getImage(provider: String, cacheKey: String, extension: String = "jpg"): Path? {
        val path = pathFor(provider, cacheKey, extension)
        return if (Files.exists(path)) path else null
    }

    /**
     * Atomically writes image bytes to the computed path and drops a
     * sidecar next to it. Returns the final path.
     *
     * Callers fetch bytes via their provider-specific HTTP client
     * (User-Agent, timeouts, retry policy) and hand the result here to
     * land under a consistent layout with consistent metadata.
     */
    fun commit(
        provider: String,
        cacheKey: String,
        bytes: ByteArray,
        contentType: String,
        upstreamUrl: String?,
        subjectType: String?,
        subjectId: Long?,
        extension: String = "jpg",
        etag: String? = null
    ): Path {
        val destPath = pathFor(provider, cacheKey, extension)
        Files.createDirectories(destPath.parent)

        val tmp = Files.createTempFile(destPath.parent, ".write-", ".tmp")
        try {
            Files.write(tmp, bytes)
            Files.move(
                tmp, destPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }

        MetadataWriter.writeSidecar(
            destPath,
            ImageMetadata.internet(
                provider = provider,
                cacheKey = cacheKey,
                upstreamUrl = upstreamUrl,
                subjectType = subjectType,
                subjectId = subjectId,
                contentType = contentType,
                etag = etag
            )
        )
        return destPath
    }

    /** Delete a stored image and its sidecar. Silent when not present. */
    fun delete(provider: String, cacheKey: String, extension: String = "jpg") {
        val path = pathFor(provider, cacheKey, extension)
        try {
            Files.deleteIfExists(path)
        } catch (e: Exception) {
            log.warn("InternetImageStore delete failed for {}: {}", path, e.message)
        }
        MetadataWriter.deleteSidecar(path)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val hi = (b.toInt() shr 4) and 0xF
            val lo = b.toInt() and 0xF
            sb.append("0123456789abcdef"[hi])
            sb.append("0123456789abcdef"[lo])
        }
        return sb.toString()
    }
}
