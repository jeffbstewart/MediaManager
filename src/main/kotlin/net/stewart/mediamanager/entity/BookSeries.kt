package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * A book series (e.g. Foundation, The Wheel of Time). Titles join via
 * [Title.book_series_id] + [Title.series_number]. See docs/BOOKS.md.
 *
 * [poster_source] is AUTO on creation and flips to MANUAL when an admin
 * sets a custom poster, at which point subsequent scans stop overwriting.
 */
@Table("book_series")
data class BookSeries(
    override var id: Long? = null,
    var name: String = "",
    var description: String? = null,
    var poster_path: String? = null,
    var poster_source: String = PosterSource.AUTO.name,
    var author_id: Long? = null,
    var open_library_key: String? = null,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<BookSeries, Long>(BookSeries::class.java)
}
