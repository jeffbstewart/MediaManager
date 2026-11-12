package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

/**
 * Links a book title to its authors / translators / illustrators.
 * [author_order] is display order within the title (0 = primary).
 *
 * [role] disambiguates the link. Only `AUTHOR` rows count toward
 * the author-grid queries; translators and illustrators are
 * surfaced separately on the book-detail page. Narrators and
 * editors are not ingested at all — see
 * [net.stewart.mediamanager.service.BookIngestionService].
 */
@Table("title_author")
data class TitleAuthor(
    override var id: Long? = null,
    var title_id: Long = 0,
    var author_id: Long = 0,
    var author_order: Int = 0,
    var role: String = AuthorRole.AUTHOR.name,
) : KEntity<Long> {
    companion object : Dao<TitleAuthor, Long>(TitleAuthor::class.java)
}

/** Roles tracked on [TitleAuthor.role]. */
enum class AuthorRole {
    /** Wrote the book. Drives the author-grid; primary credit. */
    AUTHOR,
    /** Translated the book. Surfaced on book-detail credits. */
    TRANSLATOR,
    /** Illustrated the book. Surfaced on book-detail credits. */
    ILLUSTRATOR,
}
