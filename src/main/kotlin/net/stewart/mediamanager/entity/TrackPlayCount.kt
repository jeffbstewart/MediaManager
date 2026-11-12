package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * Per-user, per-track completion counter. Drives the "Most Played"
 * smart playlist. Bumped on every track completion event; not on
 * every progress tick (the noise / write-amp wouldn't be worth it).
 *
 * See V087 + docs/MUSIC.md (Playlists Phase 2).
 */
@Table("track_play_count")
data class TrackPlayCount(
    override var id: Long? = null,
    var user_id: Long = 0,
    var track_id: Long = 0,
    var play_count: Int = 0,
    var last_played: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<TrackPlayCount, Long>(TrackPlayCount::class.java)
}
