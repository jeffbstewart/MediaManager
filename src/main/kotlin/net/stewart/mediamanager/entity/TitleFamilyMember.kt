package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("title_family_member")
data class TitleFamilyMember(
    override var id: Long? = null,
    var title_id: Long = 0,
    var family_member_id: Long = 0,
    var role_note: String? = null,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<TitleFamilyMember, Long>(TitleFamilyMember::class.java)
}
