package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime

/**
 * V089 backfill: every track we already ingested has `bpm`,
 * `time_signature`, `genre`, and `style` as null/absent because the
 * scanner only started extracting these in the V089 pass. For tracks
 * whose `file_path` still resolves on disk, re-read the tags and
 * persist the new fields + auto-tag them via [AutoTagApplicator].
 *
 * Idempotent in the sense that re-running just re-reads and re-writes
 * the same values; the applicator is already idempotent. Bump the
 * `version` below to force a rerun after a behavioural change
 * (e.g. a wider synonyms map).
 */
class BackfillTrackId3TagsUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(BackfillTrackId3TagsUpdater::class.java)

    override val name = "backfill_track_id3_tags"
    override val version = 1

    override fun run() {
        val tracks = Track.findAll().filter { !it.file_path.isNullOrBlank() }
        if (tracks.isEmpty()) {
            log.info("No tracks with file_path on disk; nothing to backfill.")
            return
        }

        val titleYearById = Title.findAll().associate { it.id to it.release_year }

        val now = LocalDateTime.now()
        var read = 0
        var persisted = 0
        var missing = 0
        val touchedAlbums = mutableSetOf<Long>()

        for (track in tracks) {
            val path = track.file_path!!
            val file = File(path)
            if (!file.isFile) {
                missing++
                continue
            }

            val tags = AudioTagReader.read(file)
            read++

            val changed = track.bpm != tags.bpm || track.time_signature != tags.timeSignature
            if (changed) {
                track.bpm = tags.bpm
                track.time_signature = tags.timeSignature
                track.updated_at = now
                track.save()
                persisted++
            }

            AutoTagApplicator.applyToTrack(AutoTagApplicator.TrackAutoTagInput(
                trackId = track.id!!,
                genres = tags.genres,
                styles = tags.styles,
                bpm = tags.bpm,
                timeSignature = tags.timeSignature,
                year = titleYearById[track.title_id]
            ))
            touchedAlbums += track.title_id
        }

        // Promote album-level majorities after every track's pass
        // completes so the vote is taken against the final state.
        for (albumId in touchedAlbums) {
            AutoTagApplicator.applyToAlbum(albumId)
        }

        log.info(
            "Backfilled ID3 auto-tags: read={}, bpm_or_timesig_changed={}, file_missing={}, albums_touched={}",
            read, persisted, missing, touchedAlbums.size
        )
    }
}
