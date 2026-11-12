package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("passkey_credential")
data class PasskeyCredential(
    override var id: Long? = null,
    var user_id: Long = 0,
    var credential_id: String = "",
    var public_key: ByteArray = ByteArray(0),
    var sign_count: Long = 0,
    var transports: String? = null,
    var display_name: String = "Passkey",
    var created_at: LocalDateTime? = null,
    var last_used_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<PasskeyCredential, Long>(PasskeyCredential::class.java)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PasskeyCredential) return false
        return id == other.id && credential_id == other.credential_id
    }

    override fun hashCode(): Int = credential_id.hashCode()
}
