package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * One track at one position within a [Playlist]. The same track may
 * appear at multiple positions, so the stable handle for removal /
 * reorder is [id] (not [track_id]).
 *
 * See V086 — unique index is on (playlist_id, position).
 */
@Table("playlist_track")
data class PlaylistTrack(
    override var id: Long? = null,
    var playlist_id: Long = 0,
    var track_id: Long = 0,
    var position: Int = 0,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<PlaylistTrack, Long>(PlaylistTrack::class.java)
}
