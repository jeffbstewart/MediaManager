package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Table("family_member")
data class FamilyMember(
    override var id: Long? = null,
    var name: String = "",
    var birth_date: LocalDate? = null,
    var headshot_id: String? = null,
    var notes: String? = null,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<FamilyMember, Long>(FamilyMember::class.java)

    /** Calculates age in years at a given date, or null if birth_date is not set. */
    fun ageAt(date: LocalDate): Int? {
        val bd = birth_date ?: return null
        return java.time.Period.between(bd, date).years
    }
}
