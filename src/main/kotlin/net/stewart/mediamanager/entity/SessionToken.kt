package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("session_token")
data class SessionToken(
    override var id: Long? = null,
    var user_id: Long = 0,
    var token_hash: String = "",
    var user_agent: String = "",
    var created_at: LocalDateTime? = null,
    var expires_at: LocalDateTime = LocalDateTime.now(),
    var last_used_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<SessionToken, Long>(SessionToken::class.java)
}
