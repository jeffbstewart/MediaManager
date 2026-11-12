package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Table("event_group")
data class EventGroup(
    override var id: Long? = null,
    var name: String = "",
    var event_date: LocalDate? = null,
    var description: String? = null,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<EventGroup, Long>(EventGroup::class.java)
}
