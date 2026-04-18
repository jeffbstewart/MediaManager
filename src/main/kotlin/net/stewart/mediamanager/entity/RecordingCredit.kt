package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * Per-track personnel credit: "John Densmore played drums on *L.A. Woman*."
 * Populated at M6 from MusicBrainz recording-rels, empty at M1.
 *
 * See [CreditRole] for role values.
 */
@Table("recording_credit")
data class RecordingCredit(
    override var id: Long? = null,
    var track_id: Long = 0,
    var artist_id: Long = 0,
    var role: String = CreditRole.PERFORMER.name,
    /** Freeform from MB instrument-rels — "drums", "lead vocals", "Fender Rhodes". */
    var instrument: String? = null,
    var credit_order: Int = 0,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<RecordingCredit, Long>(RecordingCredit::class.java)
}
