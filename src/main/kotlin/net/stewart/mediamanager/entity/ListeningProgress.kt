package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * Per-user, per-track listening progress. Parallel to [ReadingProgress]
 * for books. Keyed on (user_id, track_id); the position advances as the
 * user listens, and duration_seconds is denormalized from the track at
 * report time so the home-feed Continue Listening row can render a
 * progress bar without an extra join.
 */
@Table("listening_progress")
data class ListeningProgress(
    override var id: Long? = null,
    var user_id: Long = 0,
    var track_id: Long = 0,
    var position_seconds: Int = 0,
    var duration_seconds: Int? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<ListeningProgress, Long>(ListeningProgress::class.java)
}
