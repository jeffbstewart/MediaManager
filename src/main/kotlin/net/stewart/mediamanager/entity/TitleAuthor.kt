package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

/**
 * Links a book title to its authors (many-to-many; anthologies have several).
 * [author_order] is display order within the title; 0 = primary author.
 */
@Table("title_author")
data class TitleAuthor(
    override var id: Long? = null,
    var title_id: Long = 0,
    var author_id: Long = 0,
    var author_order: Int = 0
) : KEntity<Long> {
    companion object : Dao<TitleAuthor, Long>(TitleAuthor::class.java)
}
