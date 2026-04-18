package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * One song on one album. Parallel to [Episode] for TV — a sub-unit of a
 * [Title] whose playable file is referenced through [file_path] (for digital
 * rips) or through a [Transcode] row with `track_id` set. See docs/MUSIC.md.
 */
@Table("track")
data class Track(
    override var id: Long? = null,
    var title_id: Long = 0,
    var track_number: Int = 0,
    var disc_number: Int = 1,
    var name: String = "",
    var duration_seconds: Int? = null,
    var musicbrainz_recording_id: String? = null,
    var file_path: String? = null,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<Track, Long>(Track::class.java)
}
