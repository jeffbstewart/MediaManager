package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

@Table("skip_segment")
data class SkipSegment(
    override var id: Long? = null,
    var transcode_id: Long = 0,
    var segment_type: String = "",
    var start_seconds: Double = 0.0,
    var end_seconds: Double = 0.0,
    var detection_method: String? = null
) : KEntity<Long> {
    companion object : Dao<SkipSegment, Long>(SkipSegment::class.java)
}
