package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

@Table("tv_season")
data class TvSeason(
    override var id: Long? = null,
    var title_id: Long = 0,
    var season_number: Int = 0,
    var name: String? = null,
    var episode_count: Int? = null,
    var air_date: String? = null,
    var owned: Boolean = false
) : KEntity<Long> {
    companion object : Dao<TvSeason, Long>(TvSeason::class.java)
}
