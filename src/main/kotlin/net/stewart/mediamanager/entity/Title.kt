package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import java.time.LocalDate
import java.time.LocalDateTime

/** TMDB image CDN sizes used for poster URL construction. */
enum class PosterSize(val pathSegment: String) {
    THUMBNAIL("w185"),
    FULL("w500")
}

data class Title(
    override var id: Long? = null,
    var name: String = "",                                        // Display name (cleaned or from TMDB)
    var media_type: String = MediaType.MOVIE.name,
    var tmdb_id: Int? = null,                                      // TMDB ID for poster/detail lookups
    var release_year: Int? = null,
    var description: String? = null,
    var poster_path: String? = null,                               // TMDB poster path (e.g. "/abc.jpg")
    var backdrop_path: String? = null,                             // TMDB backdrop path (e.g. "/xyz.jpg")
    var sort_name: String? = null,                                 // For alphabetical sort (leading articles stripped)
    var raw_upc_title: String? = null,                             // Original UPCitemdb title before cleaning
    var hidden: Boolean = false,                                    // Hidden from default catalog view
    var enrichment_status: String = EnrichmentStatus.PENDING.name, // See EnrichmentStatus enum
    var retry_after: LocalDateTime? = null,                        // Next eligible retry time for FAILED titles
    var popularity: Double? = null,                                 // TMDB popularity score for prioritization
    var poster_cache_id: String? = null,                           // UUID for cached poster file on disk
    var backdrop_cache_id: String? = null,                         // UUID for cached backdrop file on disk
    var content_rating: String? = null,                            // MPAA or TV rating (e.g. "PG-13", "TV-MA")
    var tmdb_collection_id: Int? = null,                           // TMDB collection ID (belongs_to_collection)
    var tmdb_collection_name: String? = null,                      // TMDB collection name
    var event_date: LocalDate? = null,                             // Date filmed (personal videos)
    var event_group_id: Long? = null,                              // FK to event_group (personal videos)
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<Title, Long>(Title::class.java)

    /** Returns a poster URL routed through the local cache servlet, or null if no poster. */
    fun posterUrl(size: PosterSize): String? {
        // Personal videos use local hero images instead of TMDB posters
        if (media_type == MediaType.PERSONAL.name && poster_cache_id != null) {
            return "/local-images/$poster_cache_id"
        }
        poster_path ?: return null
        return "/posters/${size.pathSegment}/${id}"
    }

    /** Returns a backdrop URL routed through the local cache servlet, or null if no backdrop. */
    fun backdropUrl(): String? {
        backdrop_path ?: return null
        return "/backdrops/${id}"
    }

    /** Parses the stored content_rating string into a ContentRating enum, or null if unrated. */
    fun contentRatingEnum(): ContentRating? = ContentRating.fromTmdbCertification(content_rating)

    /** Returns a type-safe TMDB key combining tmdb_id and media_type, or null if un-enriched. */
    fun tmdbKey(): TmdbId? = TmdbId.of(tmdb_id, media_type)
}
