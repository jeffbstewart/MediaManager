package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("title_tag")
data class TitleTag(
    override var id: Long? = null,
    var title_id: Long = 0,
    var tag_id: Long = 0,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<TitleTag, Long>(TitleTag::class.java)
}
