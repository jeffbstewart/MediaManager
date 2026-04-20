package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * A user-curated, durable list of tracks. Slice 1: owner controls
 * mutations (rename / reorder / add / remove / set hero / delete);
 * any user can play the playlist or duplicate it into one of their own.
 *
 * Hero image: when [hero_track_id] is set, clients render the parent
 * title's poster as the playlist cover. When null, fall back to the
 * first track's title poster.
 *
 * See docs/MUSIC.md (Playlists) and V086.
 */
@Table("playlist")
data class Playlist(
    override var id: Long? = null,
    var name: String = "",
    var description: String? = null,
    var owner_user_id: Long = 0,
    var hero_track_id: Long? = null,
    /**
     * Phase 2 — when true, hide this playlist from everyone except the
     * owner in /listAll responses. Owner can still see + edit it.
     */
    var is_private: Boolean = false,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<Playlist, Long>(Playlist::class.java)
}
