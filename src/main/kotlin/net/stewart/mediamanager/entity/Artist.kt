package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A music artist — person, band, orchestra, or choir. Browseable like authors
 * and actors, but sourced from MusicBrainz and unrelated to [CastMember] or
 * [Author] in its schema or UX. See docs/MUSIC.md.
 */
@Table("artist")
data class Artist(
    override var id: Long? = null,
    var name: String = "",
    var sort_name: String = "",
    /** See [ArtistType]. Default GROUP covers the common case. */
    var artist_type: String = ArtistType.GROUP.name,
    var biography: String? = null,
    var headshot_path: String? = null,
    var musicbrainz_artist_id: String? = null,
    var wikidata_id: String? = null,
    /** Band formation or person birth. */
    var begin_date: LocalDate? = null,
    /** Band breakup or person death. */
    var end_date: LocalDate? = null,
    /** Last.fm similar-artist cache (serialized JSON). Populated by the future radio milestone. */
    var lastfm_similar_json: String? = null,
    var similar_fetched_at: LocalDateTime? = null,
    /** Last ArtistEnrichmentAgent attempt (any outcome). Drives retry cooldown. */
    var enrichment_last_attempt_at: LocalDateTime? = null,
    /** Consecutive no-progress attempts. Resets to 0 on any progress. */
    var enrichment_no_progress_streak: Int = 0,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<Artist, Long>(Artist::class.java)
}
