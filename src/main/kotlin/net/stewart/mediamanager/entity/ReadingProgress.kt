package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * Per-user reading position within a digital edition (EPUB / PDF). See
 * docs/BOOKS.md (M5) — parallels [PlaybackProgress] on the video side.
 *
 * [cfi] is opaque text: an EPUB Canonical Fragment Identifier for
 * EBOOK_EPUB editions, or `"/page/N"` for EBOOK_PDF. The reader client
 * knows the shape; the server just round-trips it.
 */
@Table("reading_progress")
data class ReadingProgress(
    override var id: Long? = null,
    var user_id: Long = 0,
    var media_item_id: Long = 0,
    var cfi: String = "",
    var percent: Double = 0.0,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<ReadingProgress, Long>(ReadingProgress::class.java)
}
