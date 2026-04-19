package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollection
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Phase 1b backfill — writes `.meta.json` sidecars next to every cached
 * internet image (poster, backdrop, headshot, collection poster, artist /
 * author headshots, and the shared TMDB / OpenLibrary / Cover Art Archive
 * proxy cache). See docs/IMAGE_CACHE_MIGRATION.md.
 *
 * Unlike the first-party backfill, a missing sidecar here is cheap — the
 * image can always be refetched from upstream. But recording provenance
 * now means the phase-2 reshard can move bytes to their new home without
 * needing the DB to know which TMDB path each one originally came from.
 *
 * Idempotent: files that already have a sidecar are left alone. Bump
 * [version] to force a rewrite.
 */
class BackfillInternetImageSidecars : SchemaUpdater {
    override val name: String = "backfill_internet_image_sidecars"
    override val version: Int = 1

    private val log = LoggerFactory.getLogger(BackfillInternetImageSidecars::class.java)

    override fun run() {
        val posters = backfillPosters()
        val backdrops = backfillBackdrops()
        val castHeadshots = backfillCastHeadshots()
        val collectionPosters = backfillCollectionPosters()
        val artistHeadshots = backfillArtistHeadshots()
        val authorHeadshots = backfillAuthorHeadshots()
        val proxyEntries = backfillProxyCache()

        log.info(
            "Phase 1b backfill complete: posters={} backdrops={} cast={} coll={} artist={} author={} proxy={}",
            posters, backdrops, castHeadshots, collectionPosters, artistHeadshots, authorHeadshots, proxyEntries
        )
    }

    // -------------------------------------------------------------------
    // Per-cache backfills — reconstruct what we can from DB joins
    // -------------------------------------------------------------------

    private fun backfillPosters(): Int {
        var written = 0
        for (title in Title.findAll()) {
            val cacheId = title.poster_cache_id ?: continue
            val path = shardedByUuid(Paths.get("data", "poster-cache"), cacheId, "jpg") ?: continue
            if (!shouldWrite(path)) continue

            // We don't know whether the cached bytes came from TMDB or from
            // an embedded picture block (music/ingestion path). `poster_path`
            // tells us: a leading slash means TMDB, anything else means a
            // sentinel like "caa/..." or "isbn/...". Reconstruct best-guess
            // upstream URL accordingly.
            val posterPath = title.poster_path
            val upstreamUrl: String?
            val provider: String
            if (posterPath != null && posterPath.startsWith("/")) {
                upstreamUrl = "https://image.tmdb.org/t/p/w500$posterPath"
                provider = "tmdb-poster"
            } else {
                upstreamUrl = null
                provider = "embedded-cover"
            }
            if (MetadataWriter.writeSidecar(path, ImageMetadata.internet(
                provider = provider,
                cacheKey = cacheId,
                upstreamUrl = upstreamUrl,
                subjectType = "title",
                subjectId = title.id,
                contentType = "image/jpeg"
            ))) written++
        }
        return written
    }

    private fun backfillBackdrops(): Int {
        var written = 0
        for (title in Title.findAll()) {
            val cacheId = title.backdrop_cache_id ?: continue
            val path = shardedByUuid(Paths.get("data", "backdrop-cache"), cacheId, "jpg") ?: continue
            if (!shouldWrite(path)) continue

            val backdropPath = title.backdrop_path
            val upstreamUrl = if (backdropPath != null) "https://image.tmdb.org/t/p/w1280$backdropPath" else null
            if (MetadataWriter.writeSidecar(path, ImageMetadata.internet(
                provider = "tmdb-backdrop",
                cacheKey = cacheId,
                upstreamUrl = upstreamUrl,
                subjectType = "title",
                subjectId = title.id,
                contentType = "image/jpeg"
            ))) written++
        }
        return written
    }

    private fun backfillCastHeadshots(): Int {
        var written = 0
        for (cast in CastMember.findAll()) {
            val cacheId = cast.headshot_cache_id ?: continue
            val path = shardedByUuid(Paths.get("data", "headshot-cache"), cacheId, "jpg") ?: continue
            if (!shouldWrite(path)) continue

            val profilePath = cast.profile_path
            val upstreamUrl = if (profilePath != null) "https://image.tmdb.org/t/p/w185$profilePath" else null
            if (MetadataWriter.writeSidecar(path, ImageMetadata.internet(
                provider = "tmdb-headshot",
                cacheKey = cacheId,
                upstreamUrl = upstreamUrl,
                subjectType = "cast_member",
                subjectId = cast.id,
                contentType = "image/jpeg"
            ))) written++
        }
        return written
    }

    private fun backfillCollectionPosters(): Int {
        var written = 0
        for (coll in TmdbCollection.findAll()) {
            val collId = coll.tmdb_collection_id
            val path = Paths.get("data", "collection-poster-cache", "$collId.jpg")
            if (!shouldWrite(path)) continue

            val posterPath = coll.poster_path
            val upstreamUrl = if (posterPath != null) "https://image.tmdb.org/t/p/w500$posterPath" else null
            if (MetadataWriter.writeSidecar(path, ImageMetadata.internet(
                provider = "tmdb-collection",
                cacheKey = collId.toString(),
                upstreamUrl = upstreamUrl,
                subjectType = "tmdb_collection",
                subjectId = collId.toLong(),
                contentType = "image/jpeg"
            ))) written++
        }
        return written
    }

    private fun backfillArtistHeadshots(): Int {
        var written = 0
        for (artist in Artist.findAll()) {
            val artistId = artist.id ?: continue
            val path = artistOrAuthorHeadshotPath("artist-headshot-cache", artistId) ?: continue
            if (!shouldWrite(path)) continue

            if (MetadataWriter.writeSidecar(path, ImageMetadata.internet(
                provider = "wikimedia-artist",
                cacheKey = artistId.toString(),
                upstreamUrl = artist.headshot_path,
                subjectType = "artist",
                subjectId = artistId,
                contentType = "image/jpeg"
            ))) written++
        }
        return written
    }

    private fun backfillAuthorHeadshots(): Int {
        var written = 0
        for (author in Author.findAll()) {
            val authorId = author.id ?: continue
            val path = artistOrAuthorHeadshotPath("author-headshot-cache", authorId) ?: continue
            if (!shouldWrite(path)) continue

            if (MetadataWriter.writeSidecar(path, ImageMetadata.internet(
                provider = "wikimedia-author",
                cacheKey = authorId.toString(),
                upstreamUrl = author.headshot_path,
                subjectType = "author",
                subjectId = authorId,
                contentType = "image/jpeg"
            ))) written++
        }
        return written
    }

    private fun backfillProxyCache(): Int {
        val root = Paths.get("data", "image-proxy-cache")
        if (!Files.isDirectory(root)) return 0

        var written = 0
        // Walk provider buckets ("tmdb", "ol", "caa") then the ab/cd shards.
        // We can't reconstruct the upstream URL from a SHA-256-hashed
        // filename, so upstream_url stays null. cache_key (the hash) +
        // provider-bucket label survive — enough to preserve this image in
        // the phase-2 reshard without losing provenance.
        for (bucketDir in Files.newDirectoryStream(root).use { it.toList() }) {
            if (!Files.isDirectory(bucketDir)) continue
            val bucket = bucketDir.fileName.toString()
            val provider = "proxy-$bucket"

            Files.walk(bucketDir).use { stream ->
                for (file in stream) {
                    if (!Files.isRegularFile(file)) continue
                    val name = file.fileName.toString()
                    if (name.endsWith(MetadataWriter.SIDECAR_SUFFIX)) continue
                    if (Files.exists(MetadataWriter.sidecarFor(file))) continue

                    val base = name.substringBeforeLast('.')
                    val ext = name.substringAfterLast('.', missingDelimiterValue = "jpg")
                    if (MetadataWriter.writeSidecar(file, ImageMetadata.internet(
                        provider = provider,
                        cacheKey = base,
                        upstreamUrl = null,
                        subjectType = null,
                        subjectId = null,
                        contentType = guessContentType(ext)
                    ))) written++
                }
            }
        }
        return written
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private fun shouldWrite(imagePath: Path): Boolean {
        if (!Files.exists(imagePath)) return false
        if (Files.exists(MetadataWriter.sidecarFor(imagePath))) return false
        return true
    }

    // Matches PosterCacheService / BackdropCacheService / HeadshotCacheService
    // sharding: {cacheRoot}/{ab}/{cd}/{uuid}.{ext} where ab/cd are the first
    // 4 hex chars of the UUID with hyphens stripped.
    private fun shardedByUuid(cacheRoot: Path, uuid: String, ext: String): Path? {
        val clean = uuid.replace("-", "")
        if (clean.length < 4) return null
        val shard1 = clean.substring(0, 2)
        val shard2 = clean.substring(2, 4)
        return cacheRoot.resolve(shard1).resolve(shard2).resolve("$uuid.$ext")
    }

    // Matches Artist/Author headshot layout: last 2 / middle 2 of zero-padded id.
    private fun artistOrAuthorHeadshotPath(cacheDirName: String, entityId: Long): Path? {
        val id = entityId.toString().padStart(6, '0')
        val shard1 = id.takeLast(2)
        val shard2 = id.takeLast(4).take(2)
        return Paths.get("data", cacheDirName, shard1, shard2, "$entityId.jpg")
    }

    private fun guessContentType(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }
}
