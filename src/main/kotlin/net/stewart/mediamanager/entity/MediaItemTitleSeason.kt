package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

@Table("media_item_title_season")
data class MediaItemTitleSeason(
    override var id: Long? = null,
    var media_item_title_id: Long = 0,
    var title_season_id: Long = 0
) : KEntity<Long> {
    companion object : Dao<MediaItemTitleSeason, Long>(MediaItemTitleSeason::class.java)
}
