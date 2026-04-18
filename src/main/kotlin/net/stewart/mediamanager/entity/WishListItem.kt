package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("wish_list_item")
data class WishListItem(
    override var id: Long? = null,
    var user_id: Long = 0,
    var wish_type: String = WishType.MEDIA.name,
    var status: String = WishStatus.ACTIVE.name,

    // MEDIA wish fields
    var tmdb_id: Int? = null,
    var tmdb_title: String? = null,
    var tmdb_media_type: String? = null,
    var tmdb_poster_path: String? = null,
    var tmdb_release_year: Int? = null,
    var tmdb_popularity: Double? = null,

    // TRANSCODE wish fields
    var title_id: Long? = null,

    // MEDIA wish: optional season number (e.g., "I want season 5 of this show")
    var season_number: Int? = null,

    // BOOK wish fields — see docs/BOOKS.md M3.
    var open_library_work_id: String? = null,
    var book_title: String? = null,
    var book_author: String? = null,
    var book_cover_isbn: String? = null,
    var book_series_id: Long? = null,
    var book_series_number: java.math.BigDecimal? = null,

    // ALBUM wish fields — see docs/MUSIC.md M3.
    var musicbrainz_release_group_id: String? = null,
    var album_title: String? = null,
    var album_primary_artist: String? = null,
    var album_year: Int? = null,
    /** MB release MBID for Cover Art Archive fetch (separate from the release-group dedup key). */
    var album_cover_release_id: String? = null,
    var album_is_compilation: Boolean = false,

    var notes: String? = null,
    var created_at: LocalDateTime? = null,
    var fulfilled_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<WishListItem, Long>(WishListItem::class.java)

    /** Returns a type-safe TMDB key combining tmdb_id and tmdb_media_type, or null if missing. */
    fun tmdbKey(): TmdbId? = TmdbId.of(tmdb_id, tmdb_media_type)
}
