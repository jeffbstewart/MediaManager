package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * Log entry for each enrichment attempt on a Title.
 *
 * Used by TmdbEnrichmentAgent to track consecutive failures for exponential backoff.
 * Each attempt records whether it succeeded and any error message from the TMDB API.
 */
@Table("enrichment_attempt")
data class EnrichmentAttempt(
    override var id: Long? = null,
    var title_id: Long = 0,
    var attempted_at: LocalDateTime? = null,
    var succeeded: Boolean = false,
    var error_message: String? = null
) : KEntity<Long> {
    companion object : Dao<EnrichmentAttempt, Long>(EnrichmentAttempt::class.java)
}
