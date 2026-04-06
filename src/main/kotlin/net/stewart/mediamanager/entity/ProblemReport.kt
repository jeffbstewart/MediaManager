package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("problem_report")
data class ProblemReport(
    override var id: Long? = null,
    var user_id: Long = 0,
    var title_id: Long? = null,
    var title_name: String? = null,
    var season_number: Int? = null,
    var episode_number: Int? = null,
    var description: String = "",
    var status: String = ReportStatus.OPEN.name,
    var admin_notes: String? = null,
    var resolved_by: Long? = null,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<ProblemReport, Long>(ProblemReport::class.java)
}
