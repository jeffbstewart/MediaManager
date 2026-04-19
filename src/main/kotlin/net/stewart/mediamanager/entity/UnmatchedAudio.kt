package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/** Staging status for an audio file the scanner couldn't auto-link. */
enum class UnmatchedAudioStatus { UNMATCHED, LINKED, IGNORED }

/**
 * An audio file (.flac / .mp3 / .m4a / .ogg / .wav) discovered under the
 * configured music_root_path that the scanner couldn't auto-link to a
 * [Track]. Admin resolves each row via the Unmatched Audio admin view.
 * See docs/MUSIC.md (M4).
 */
@Table("unmatched_audio")
data class UnmatchedAudio(
    override var id: Long? = null,
    var file_path: String = "",
    var file_name: String = "",
    var file_size_bytes: Long? = null,
    var media_format: String = MediaFormat.AUDIO_FLAC.name,
    var parsed_title: String? = null,
    var parsed_album: String? = null,
    var parsed_album_artist: String? = null,
    var parsed_track_artist: String? = null,
    var parsed_track_number: Int? = null,
    var parsed_disc_number: Int? = null,
    var parsed_duration_seconds: Int? = null,
    /** MUSICBRAINZ_ALBUMID from tags. */
    var parsed_mb_release_id: String? = null,
    /** MUSICBRAINZ_RELEASEGROUPID from tags. */
    var parsed_mb_release_group_id: String? = null,
    /** MUSICBRAINZ_TRACKID (= recording MBID) from tags. */
    var parsed_mb_recording_id: String? = null,
    /** EAN-13 / UPC from the `UPC` / `BARCODE` tag — authoritative MB match key when present. */
    var parsed_upc: String? = null,
    /** Per-track ISRC from the `ISRC` / `TSRC` tag. */
    var parsed_isrc: String? = null,
    /** Label catalog number, e.g. "CDP 593178". */
    var parsed_catalog_number: String? = null,
    /** Label / publisher name from the `LABEL` / `TPUB` tag. */
    var parsed_label: String? = null,
    var match_status: String = UnmatchedAudioStatus.UNMATCHED.name,
    var linked_track_id: Long? = null,
    var discovered_at: LocalDateTime? = null,
    var linked_at: LocalDateTime? = null,
    /** Last reprocess attempt (any outcome). Drives retry cooldown. */
    var resolve_last_attempt_at: LocalDateTime? = null,
    /** Consecutive no-progress reprocess attempts. Reset to 0 on progress. */
    var resolve_no_progress_streak: Int = 0
) : KEntity<Long> {
    companion object : Dao<UnmatchedAudio, Long>(UnmatchedAudio::class.java)
}
