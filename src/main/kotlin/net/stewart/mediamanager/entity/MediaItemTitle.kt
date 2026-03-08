package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

@Table("media_item_title")
data class MediaItemTitle(
    override var id: Long? = null,
    var media_item_id: Long = 0,
    var title_id: Long = 0,
    var disc_number: Int = 1,
    var seasons: String? = null
) : KEntity<Long> {
    companion object : Dao<MediaItemTitle, Long>(MediaItemTitle::class.java)
}
