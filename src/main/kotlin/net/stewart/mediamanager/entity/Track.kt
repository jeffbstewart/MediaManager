package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * One song on one album. Parallel to [Episode] for TV — a sub-unit of a
 * [Title] whose playable file is referenced through [file_path] (for digital
 * rips) or through a [Transcode] row with `track_id` set. See docs/MUSIC.md.
 */
@Table("track")
data class Track(
    override var id: Long? = null,
    var title_id: Long = 0,
    var track_number: Int = 0,
    var disc_number: Int = 1,
    var name: String = "",
    var duration_seconds: Int? = null,
    var musicbrainz_recording_id: String? = null,
    var file_path: String? = null,
    /** Raw BPM from ID3/Vorbis `BPM` / `TBPM`, ML analysis, or manual override — see [bpm_source]. */
    var bpm: Int? = null,
    /** Provenance of [bpm]: "TAG" / "ESSENTIA" / "MANUAL". Drives re-analysis queue. */
    var bpm_source: String = "TAG",
    /** Set only when [bpm_source]="ESSENTIA"; bpm_histogram_first_peak_weight (0..1). */
    var bpm_confidence: Double? = null,
    /**
     * Epoch seconds of the file's mtime at the moment analysis failed.
     * Used by EssentiaAgent's sweep to auto-requeue rows whose underlying
     * file has been modified since the failure was recorded. Null unless
     * [bpm_source]="ESSENTIA_FAILED".
     */
    var bpm_analysis_failed_mtime: Long? = null,
    /** Raw time signature, e.g. "3/4" / "4/4". From tag / madmom / manual override — see [time_signature_source]. */
    var time_signature: String? = null,
    /** Provenance of [time_signature]: "TAG" / "MADMOM" / "MANUAL" / "MADMOM_FAILED". */
    var time_signature_source: String = "TAG",
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<Track, Long>(Track::class.java)
}
