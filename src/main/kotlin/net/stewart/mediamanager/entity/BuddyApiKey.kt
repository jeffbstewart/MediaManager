package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("buddy_api_key")
data class BuddyApiKey(
    override var id: Long? = null,
    var name: String = "",
    var key_hash: String = "",
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<BuddyApiKey, Long>(BuddyApiKey::class.java)
}
