package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("device_token")
data class DeviceToken(
    override var id: Long? = null,
    var token_hash: String = "",
    var user_id: Long = 0,
    var device_name: String = "",
    var created_at: LocalDateTime? = null,
    var last_used_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<DeviceToken, Long>(DeviceToken::class.java)
}
