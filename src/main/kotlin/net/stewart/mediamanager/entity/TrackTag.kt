package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * Many-to-many link between [Track] and [Tag]. Mirrors [TitleTag] on
 * the title side — same Tag rows are referenced from both surfaces.
 *
 * See V088 + docs/MUSIC.md (Tags phase B).
 */
@Table("track_tag")
data class TrackTag(
    override var id: Long? = null,
    var track_id: Long = 0,
    var tag_id: Long = 0,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<TrackTag, Long>(TrackTag::class.java)
}
