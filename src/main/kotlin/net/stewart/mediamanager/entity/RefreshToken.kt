package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("refresh_token")
data class RefreshToken(
    override var id: Long? = null,
    var user_id: Long = 0,
    var token_hash: String = "",
    var family_id: String = "",
    var device_name: String = "",
    var created_at: LocalDateTime? = null,
    var expires_at: LocalDateTime = LocalDateTime.now(),
    var revoked: Boolean = false,
    var replaced_by_hash: String? = null,
    var replaced_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<RefreshToken, Long>(RefreshToken::class.java)
}
