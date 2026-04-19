package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * One pre-computed "similar to your library" artist for a user.
 * Populated by [net.stewart.mediamanager.service.RecommendationAgent]
 * on its daily pass. Uniqueness key: (user_id, suggested_artist_mbid).
 *
 * See docs/MUSIC.md §M8.
 */
@Table("recommended_artist")
data class RecommendedArtist(
    override var id: Long? = null,
    var user_id: Long = 0,
    var suggested_artist_mbid: String = "",
    var suggested_artist_name: String = "",
    /** Aggregate Last.fm match score across the voter artists. */
    var score: Double = 0.0,
    /** JSON array of {mbid, name, album_count} for up to ~3 voter artists. */
    var voters_json: String? = null,
    /** Representative MB release-group — drives the "Start here" nudge on the card. */
    var representative_release_group_id: String? = null,
    /** Representative release-group title to display alongside the nudge. */
    var representative_release_title: String? = null,
    var created_at: LocalDateTime? = null,
    /** Non-null when the user has dismissed this suggestion. */
    var dismissed_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<RecommendedArtist, Long>(RecommendedArtist::class.java)
}
