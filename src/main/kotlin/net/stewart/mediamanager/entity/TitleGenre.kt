package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

@Table("title_genre")
data class TitleGenre(
    override var id: Long? = null,
    var title_id: Long = 0,
    var genre_id: Long = 0
) : KEntity<Long> {
    companion object : Dao<TitleGenre, Long>(TitleGenre::class.java)
}
