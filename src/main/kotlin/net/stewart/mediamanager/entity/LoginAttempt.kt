package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("login_attempt")
data class LoginAttempt(
    override var id: Long? = null,
    var username: String = "",
    var ip_address: String = "",
    var attempted_at: LocalDateTime = LocalDateTime.now(),
    var success: Boolean = false
) : KEntity<Long> {
    companion object : Dao<LoginAttempt, Long>(LoginAttempt::class.java)
}
