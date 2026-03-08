package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("playback_progress")
data class PlaybackProgress(
    override var id: Long? = null,
    var user_id: Long = 0,
    var transcode_id: Long = 0,
    var position_seconds: Double = 0.0,
    var duration_seconds: Double? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<PlaybackProgress, Long>(PlaybackProgress::class.java)
}
