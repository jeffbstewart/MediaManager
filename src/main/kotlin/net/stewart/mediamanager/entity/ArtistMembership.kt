package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Band-lineup relationship: "Jim Morrison was a member of The Doors,
 * 1965–1971, playing vocals." The [group_artist_id] must have
 * `artist_type = GROUP` (or ORCHESTRA / CHOIR); [member_artist_id] is
 * `PERSON`. Populated at M6 from MusicBrainz artist-rels, empty at M1.
 */
@Table("artist_membership")
data class ArtistMembership(
    override var id: Long? = null,
    var group_artist_id: Long = 0,
    var member_artist_id: Long = 0,
    var begin_date: LocalDate? = null,
    var end_date: LocalDate? = null,
    /** Freeform list pulled from MB (`"vocals, guitar"`). */
    var primary_instruments: String? = null,
    /** MB sometimes records "touring member", "session only", etc. */
    var notes: String? = null,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<ArtistMembership, Long>(ArtistMembership::class.java)
}
