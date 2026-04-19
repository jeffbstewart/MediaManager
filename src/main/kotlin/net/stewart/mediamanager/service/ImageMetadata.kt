package net.stewart.mediamanager.service

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Self-describing sidecar metadata for cached image files.
 *
 * Every image under `data/first-party-images/` and `data/internet-images/`
 * ships with a `{image}.meta.json` next to it carrying the fields below.
 * The goal is disaster recovery: if the SQLite DB is lost, the sidecars
 * alone let us reconstruct `image → subject` mappings without re-fetching
 * (for internet caches) or re-photographing (for first-party).
 *
 * See docs/IMAGE_CACHE_MIGRATION.md for the migration phases that populate
 * these sidecars on every new image write and, via the backfill schema
 * updaters, on every existing file.
 *
 * Wire-level serialization is flat JSON — nullable fields are omitted
 * via the Gson default. One unified shape across stores keeps the reader
 * simple; the `store` discriminator tells a consumer which subset of
 * fields is meaningful for a given sidecar.
 */
data class ImageMetadata(
    /** Schema version. Bump when fields change in a non-additive way. */
    val version: Int = 1,

    /** "first-party" or "internet". Namespace for the fields that follow. */
    val store: String,

    // --- First-party fields (store = "first-party") ---

    /** "ownership-photos" | "local-images". Always present for first-party. */
    val category: String? = null,
    /** Photo UUID (ownership-photos and local-images share this). */
    val photo_id: String? = null,
    /** UPC / normalized product identifier for ownership photos. */
    val storage_key: String? = null,
    /** Linked media_item.id when resolved; null for UPC-orphan rows. */
    val media_item_id: Long? = null,
    /** Slugified title at capture time. Diagnostic only. */
    val slug_hint: String? = null,
    /** Per-storage_key sequence number when a single item has multiple photos. */
    val sequence: Int? = null,
    /** Original filename on the uploader's device, when known. */
    val original_filename: String? = null,
    /** ISO-8601 UTC capture / upload time. */
    val captured_at: String? = null,

    // --- Local-image-specific (first-party, category = "local-images") ---

    /** "title" | "personal_video" | "user_avatar" — what this image decorates. */
    val subject_type: String? = null,
    /** Numeric id paired with subject_type. */
    val subject_id: Long? = null,
    /** app_user.id of the uploader. */
    val uploaded_by_user_id: Long? = null,
    /** ISO-8601 UTC upload time. */
    val uploaded_at: String? = null,

    // --- Internet fields (store = "internet") ---

    /** "tmdb-poster" | "tmdb-backdrop" | "caa-release-group" | "wikimedia-artist" | ... */
    val provider: String? = null,
    /** Stable upstream identifier: UUID, MBID, OL work id, hashed URL, etc. */
    val cache_key: String? = null,
    /** Fully qualified upstream URL that produced the bytes. Null = unreconstructable. */
    val upstream_url: String? = null,
    /** ISO-8601 UTC fetch time. */
    val fetched_at: String? = null,
    /** Opaque cache validator used by the gRPC ImageService etag machinery. */
    val etag: String? = null,

    // --- Universal ---

    /** MIME type of the accompanying image bytes. */
    val content_type: String
) {
    companion object {
        private val GSON: Gson = GsonBuilder()
            .serializeNulls() // prefer explicit nulls for fields we deliberately
            .disableHtmlEscaping()           // write as null rather than drop them
            .setPrettyPrinting()
            .create()

        /**
         * Serializes metadata and returns the JSON string. Omits null fields
         * that the caller didn't set so the on-disk shape stays compact — we
         * override serializeNulls only where we want it.
         */
        fun toJson(meta: ImageMetadata): String {
            // Re-serialize via a no-nulls Gson so fields left at their default
            // null disappear from disk. Explicit "unknown" semantics can be
            // expressed by passing an empty string when needed.
            val compact = Gson()
            return compact.toJson(meta)
        }

        fun fromJson(json: String): ImageMetadata = GSON.fromJson(json, ImageMetadata::class.java)

        /** Factory helper for ownership photos. */
        fun ownershipPhoto(
            photoId: String,
            storageKey: String?,
            mediaItemId: Long?,
            slugHint: String?,
            sequence: Int?,
            capturedAt: LocalDateTime?,
            contentType: String,
            originalFilename: String? = null
        ): ImageMetadata = ImageMetadata(
            store = "first-party",
            category = "ownership-photos",
            photo_id = photoId,
            storage_key = storageKey,
            media_item_id = mediaItemId,
            slug_hint = slugHint,
            sequence = sequence,
            original_filename = originalFilename,
            captured_at = capturedAt?.toInstant(ZoneOffset.UTC)?.toString(),
            content_type = contentType
        )

        /** Factory helper for user-uploaded or FFmpeg-extracted local images. */
        fun localImage(
            uuid: String,
            subjectType: String?,
            subjectId: Long?,
            uploadedByUserId: Long?,
            uploadedAt: LocalDateTime?,
            contentType: String
        ): ImageMetadata = ImageMetadata(
            store = "first-party",
            category = "local-images",
            photo_id = uuid,
            subject_type = subjectType,
            subject_id = subjectId,
            uploaded_by_user_id = uploadedByUserId,
            uploaded_at = uploadedAt?.toInstant(ZoneOffset.UTC)?.toString(),
            content_type = contentType
        )

        /** Factory helper for internet-cached images. */
        fun internet(
            provider: String,
            cacheKey: String,
            upstreamUrl: String?,
            subjectType: String?,
            subjectId: Long?,
            contentType: String,
            etag: String? = null,
            fetchedAt: LocalDateTime? = LocalDateTime.now()
        ): ImageMetadata = ImageMetadata(
            store = "internet",
            provider = provider,
            cache_key = cacheKey,
            upstream_url = upstreamUrl,
            subject_type = subjectType,
            subject_id = subjectId,
            etag = etag,
            fetched_at = fetchedAt?.toInstant(ZoneOffset.UTC)?.toString(),
            content_type = contentType
        )
    }
}

/**
 * Writes sidecar JSON next to cached image files.
 *
 * Ordering rule: call this AFTER the image bytes are atomically committed
 * to disk. If the sidecar write fails (disk full, permission, crash), the
 * image still serves — the backfill schema updater reconstructs the
 * missing sidecar on the next startup cycle.
 *
 * The sidecar path is `{imagePath}.meta.json`. Listings sort image + sidecar
 * next to each other by path prefix.
 */
object MetadataWriter {
    private val log = LoggerFactory.getLogger(MetadataWriter::class.java)

    /** Path suffix appended to an image path to locate its sidecar. */
    const val SIDECAR_SUFFIX: String = ".meta.json"

    /**
     * Atomically writes a sidecar next to [imagePath]. Returns true on
     * success, false on any failure (logged at WARN; never thrown).
     *
     * We never propagate errors: a missing sidecar is a degraded but
     * functional state, while a failed image write would have thrown long
     * before this call. The goal is "best effort" metadata without risking
     * the parent operation.
     */
    fun writeSidecar(imagePath: Path, meta: ImageMetadata): Boolean {
        val sidecarPath = sidecarFor(imagePath)
        return try {
            val json = ImageMetadata.toJson(meta)
            val tmp = sidecarPath.resolveSibling("${sidecarPath.fileName}.tmp")
            Files.createDirectories(sidecarPath.parent)
            Files.writeString(tmp, json, Charsets.UTF_8)
            Files.move(tmp, sidecarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: Exception) {
            log.warn("Sidecar write failed for {}: {}", imagePath, e.message)
            false
        }
    }

    /** Reads a sidecar next to [imagePath], or null if none exists or it's unparseable. */
    fun readSidecar(imagePath: Path): ImageMetadata? {
        val sidecarPath = sidecarFor(imagePath)
        if (!Files.exists(sidecarPath)) return null
        return try {
            ImageMetadata.fromJson(Files.readString(sidecarPath, Charsets.UTF_8))
        } catch (e: Exception) {
            log.warn("Sidecar read failed for {}: {}", imagePath, e.message)
            null
        }
    }

    /** Deletes the sidecar next to [imagePath] if present. Silent on absence. */
    fun deleteSidecar(imagePath: Path) {
        val sidecarPath = sidecarFor(imagePath)
        try {
            Files.deleteIfExists(sidecarPath)
        } catch (e: Exception) {
            log.warn("Sidecar delete failed for {}: {}", imagePath, e.message)
        }
    }

    /** Canonical `{imagePath}.meta.json` location for a given image. */
    fun sidecarFor(imagePath: Path): Path =
        imagePath.resolveSibling(imagePath.fileName.toString() + SIDECAR_SUFFIX)
}
