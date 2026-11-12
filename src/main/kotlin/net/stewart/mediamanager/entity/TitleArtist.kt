package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

/**
 * Links an album [Title] to its album-level artists (many-to-many; duets and
 * split releases carry two or three). Mirrors [TitleAuthor] for books.
 *
 * `artist_order` is the display order within the title; 0 = primary credit.
 */
@Table("title_artist")
data class TitleArtist(
    override var id: Long? = null,
    var title_id: Long = 0,
    var artist_id: Long = 0,
    var artist_order: Int = 0
) : KEntity<Long> {
    companion object : Dao<TitleArtist, Long>(TitleArtist::class.java)
}
