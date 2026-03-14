package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("live_tv_channel")
data class LiveTvChannel(
    override var id: Long? = null,
    var tuner_id: Long = 0,
    var guide_number: String = "",
    var guide_name: String = "",
    var stream_url: String = "",
    var network_affiliation: String? = null,
    var reception_quality: Int = 3,
    var tags: String = "",
    var enabled: Boolean = true,
    var display_order: Int = 0,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<LiveTvChannel, Long>(LiveTvChannel::class.java)
}
