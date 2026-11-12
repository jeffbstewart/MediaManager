package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("live_tv_tuner")
data class LiveTvTuner(
    override var id: Long? = null,
    var name: String = "",
    var device_id: String = "",
    var ip_address: String = "",
    var model_number: String = "",
    var tuner_count: Int = 2,
    var firmware_version: String = "",
    var enabled: Boolean = true,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<LiveTvTuner, Long>(LiveTvTuner::class.java)
}
