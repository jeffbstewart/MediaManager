package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("user_title_flag")
data class UserTitleFlag(
    override var id: Long? = null,
    var user_id: Long = 0,
    var title_id: Long = 0,
    var flag: String = UserFlagType.STARRED.name,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<UserTitleFlag, Long>(UserTitleFlag::class.java)
}
