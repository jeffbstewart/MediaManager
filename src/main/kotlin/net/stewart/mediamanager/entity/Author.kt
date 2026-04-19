package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A book author. Browseable like actors on the movie side, but sourced from
 * Open Library instead of TMDB and unrelated to [CastMember] semantics.
 * See docs/BOOKS.md.
 */
@Table("author")
data class Author(
    override var id: Long? = null,
    var name: String = "",
    var sort_name: String = "",
    var biography: String? = null,
    var headshot_path: String? = null,
    var open_library_author_id: String? = null,
    var wikidata_id: String? = null,
    var birth_date: LocalDate? = null,
    var death_date: LocalDate? = null,
    /** Last AuthorEnrichmentAgent attempt (any outcome). Drives retry cooldown. */
    var enrichment_last_attempt_at: LocalDateTime? = null,
    /** Consecutive no-progress attempts. Resets to 0 on any progress. */
    var enrichment_no_progress_streak: Int = 0,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<Author, Long>(Author::class.java)
}
