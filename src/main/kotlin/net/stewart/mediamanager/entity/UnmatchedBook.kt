package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/** Staging status for an ebook file found on disk without an embedded, resolvable ISBN. */
enum class UnmatchedBookStatus { UNMATCHED, LINKED, IGNORED }

/**
 * An ebook file (.epub / .pdf) discovered on the NAS that couldn't be
 * auto-ingested — either because the file carried no ISBN, or because the
 * ISBN didn't resolve against Open Library. Admin resolves each row via
 * the Unmatched Books admin view. See docs/BOOKS.md.
 */
@Table("unmatched_book")
data class UnmatchedBook(
    override var id: Long? = null,
    var file_path: String = "",
    var file_name: String = "",
    var file_size_bytes: Long? = null,
    var media_format: String = MediaFormat.EBOOK_EPUB.name,
    var parsed_title: String? = null,
    var parsed_author: String? = null,
    var parsed_isbn: String? = null,
    var match_status: String = UnmatchedBookStatus.UNMATCHED.name,
    var linked_title_id: Long? = null,
    var discovered_at: LocalDateTime? = null,
    var linked_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<UnmatchedBook, Long>(UnmatchedBook::class.java)
}
