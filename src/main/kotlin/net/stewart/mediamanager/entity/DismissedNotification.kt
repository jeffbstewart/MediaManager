package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("dismissed_notification")
data class DismissedNotification(
    override var id: Long? = null,
    var user_id: Long = 0,
    var notification_key: String = "",
    var dismissed_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<DismissedNotification, Long>(DismissedNotification::class.java)
}
