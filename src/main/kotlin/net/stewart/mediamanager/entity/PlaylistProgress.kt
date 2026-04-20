package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * Per-user resume cursor on a playlist. One row per (user, playlist).
 * Bumped whenever the user reports progress while a playlist is in
 * the queue; cleared automatically when the playlist or the chosen
 * playlist_track row is deleted (FK cascades).
 *
 * See V087 + docs/MUSIC.md (Playlists Phase 2).
 */
@Table("playlist_progress")
data class PlaylistProgress(
    override var id: Long? = null,
    var user_id: Long = 0,
    var playlist_id: Long = 0,
    var playlist_track_id: Long = 0,
    var position_seconds: Int = 0,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<PlaylistProgress, Long>(PlaylistProgress::class.java)
}
