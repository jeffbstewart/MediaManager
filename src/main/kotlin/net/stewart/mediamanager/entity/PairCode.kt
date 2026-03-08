package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("pair_code")
data class PairCode(
    override var id: Long? = null,
    var code: String = "",
    var device_name: String = "",
    var server_url: String = "",
    var user_id: Long? = null,
    var token_hash: String? = null,
    var status: String = PairStatus.PENDING.name,
    var created_at: LocalDateTime? = null,
    var expires_at: LocalDateTime = LocalDateTime.now()
) : KEntity<Long> {
    companion object : Dao<PairCode, Long>(PairCode::class.java)
}

enum class PairStatus { PENDING, PAIRED, EXPIRED }
