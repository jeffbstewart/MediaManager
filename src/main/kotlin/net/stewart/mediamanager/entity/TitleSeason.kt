package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

@Table("title_season")
data class TitleSeason(
    override var id: Long? = null,
    var title_id: Long = 0,
    var season_number: Int = 0,
    var name: String? = null,
    var episode_count: Int? = null,
    var air_date: String? = null,
    var acquisition_status: String = AcquisitionStatus.UNKNOWN.name
) : KEntity<Long> {
    companion object : Dao<TitleSeason, Long>(TitleSeason::class.java)
}
