package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

/**
 * Per-track artist credit. Only populated when the track's artist differs
 * from the album-level [TitleArtist] — keeps write volume low for
 * single-artist albums which inherit from title-level and never touch this
 * table. Populated for compilations ("Various Artists" at the title level,
 * the actual performer on each track here).
 */
@Table("track_artist")
data class TrackArtist(
    override var id: Long? = null,
    var track_id: Long = 0,
    var artist_id: Long = 0,
    var artist_order: Int = 0
) : KEntity<Long> {
    companion object : Dao<TrackArtist, Long>(TrackArtist::class.java)
}
