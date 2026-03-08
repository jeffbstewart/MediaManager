package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao

data class Episode(
    override var id: Long? = null,
    var title_id: Long = 0,
    var season_number: Int = 0,
    var episode_number: Int = 0,
    var name: String? = null,
    var tmdb_id: Int? = null
) : KEntity<Long> {
    companion object : Dao<Episode, Long>(Episode::class.java)
}
