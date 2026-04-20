package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.service.AudioTagReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Admin diagnostic for investigating why a specific [Track] didn't get
 * linked to a file on the NAS. Returns:
 *
 * - The track and its parent title.
 * - Every sibling track on the same album, so you can see at a glance
 *   whether the whole disc is unlinked or just this one row.
 * - Any lingering `unmatched_audio` rows that reference a file whose
 *   parsed metadata hints at this track.
 * - Candidate files on disk: walk the album's directory tree (derived
 *   from a sibling track with a known `file_path`) and score each
 *   audio file by how well its tags + filename match the track's
 *   name / disc / track number. A good match here is a strong
 *   signal the file exists but the link didn't fire — fix with the
 *   forthcoming force-link admin action or by re-running the scanner.
 */
@Blocking
class TrackDiagnosticHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/diag/track/{trackId}")
    fun diagnoseTrack(
        ctx: ServiceRequestContext,
        @Param("trackId") trackId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val track = Track.findById(trackId) ?: return notFound("Track $trackId not found")
        val title = Title.findById(track.title_id)
            ?: return notFound("Title ${track.title_id} not found")

        val siblings = Track.findAll()
            .filter { it.title_id == track.title_id }
            .sortedWith(compareBy({ it.disc_number }, { it.track_number }))

        val linkedSiblings = siblings.filter { !it.file_path.isNullOrBlank() }
        val albumDirs = linkedSiblings.mapNotNull { s ->
            s.file_path?.let { File(it).parentFile?.absolutePath }
        }.toSet()

        // Walk from the highest common ancestor of the album's known
        // directories so both Disc 1 and Disc 2 folders get included.
        val searchRoot = albumDirs.map { File(it) }.commonAncestorOrNull()
            ?: musicRoot()?.let { File(it) }

        val candidateFiles = searchRoot
            ?.takeIf { it.isDirectory }
            ?.let { scoreCandidateFiles(track, it) }
            .orEmpty()

        // Look for any unmatched_audio rows whose parsed metadata lines
        // up with this track — common when the scanner staged a file
        // it couldn't auto-link and it's still sitting in triage.
        val matchingUnmatched = UnmatchedAudio.findAll().filter { u ->
            val nameHit = u.parsed_title?.equals(track.name, ignoreCase = true) == true
            val numHit = u.parsed_disc_number == track.disc_number &&
                u.parsed_track_number == track.track_number &&
                u.parsed_album?.equals(title.name, ignoreCase = true) == true
            nameHit || numHit
        }

        val response = mapOf(
            "track" to mapOf(
                "id" to track.id,
                "title_id" to track.title_id,
                "disc_number" to track.disc_number,
                "track_number" to track.track_number,
                "name" to track.name,
                "duration_seconds" to track.duration_seconds,
                "musicbrainz_recording_id" to track.musicbrainz_recording_id,
                "file_path" to track.file_path,
                "bpm" to track.bpm,
                "time_signature" to track.time_signature
            ),
            "title" to mapOf(
                "id" to title.id,
                "name" to title.name,
                "media_type" to title.media_type,
                "musicbrainz_release_id" to title.musicbrainz_release_id,
                "musicbrainz_release_group_id" to title.musicbrainz_release_group_id
            ),
            "siblings" to siblings.map { s ->
                mapOf(
                    "id" to s.id,
                    "disc_number" to s.disc_number,
                    "track_number" to s.track_number,
                    "name" to s.name,
                    "linked" to !s.file_path.isNullOrBlank(),
                    "file_path" to s.file_path
                )
            },
            "album_directories" to albumDirs,
            "search_root" to searchRoot?.absolutePath,
            "candidate_files" to candidateFiles.map { c ->
                mapOf(
                    "path" to c.path,
                    "score" to c.score,
                    "reasons" to c.reasons,
                    "tag_disc_number" to c.tagDisc,
                    "tag_track_number" to c.tagTrack,
                    "tag_title" to c.tagTitle,
                    "tag_album" to c.tagAlbum
                )
            },
            "matching_unmatched_audio" to matchingUnmatched.map { u ->
                mapOf(
                    "id" to u.id,
                    "file_path" to u.file_path,
                    "match_status" to u.match_status,
                    "parsed_title" to u.parsed_title,
                    "parsed_album" to u.parsed_album,
                    "parsed_disc_number" to u.parsed_disc_number,
                    "parsed_track_number" to u.parsed_track_number
                )
            },
            "diagnosis" to diagnosisFor(track, siblings, candidateFiles, matchingUnmatched)
        )

        val bytes = gson.toJson(response).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    // ------------------------- filesystem scan -----------------------

    /**
     * Walk [root] for audio files and score each against [track]. The
     * top candidates by score come back; a perfect name + disc + track
     * match is the strongest signal, partial matches still surface for
     * eyeball inspection.
     */
    private fun scoreCandidateFiles(track: Track, root: File): List<Candidate> {
        val audioExts = setOf("flac", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav")
        val normalizedTrackName = normalize(track.name)
        val candidates = mutableListOf<Candidate>()

        // Cap the walk at a sensible depth — an album root typically has
        // 2–3 levels (album/discN/track.flac). Don't want to spider an
        // entire music library just because a sibling's file_path is
        // malformed.
        Files.walk(root.toPath(), 3).use { stream ->
            stream.forEach { p ->
                if (!p.isRegularFile()) return@forEach
                if (p.extension.lowercase() !in audioExts) return@forEach
                val score = scoreFile(p, track, normalizedTrackName, candidates)
                if (score != null) candidates += score
            }
        }

        // Return the top 12 by score, most-likely matches first.
        return candidates.sortedByDescending { it.score }.take(12)
    }

    private fun scoreFile(
        path: Path,
        track: Track,
        normalizedTrackName: String,
        @Suppress("UNUSED_PARAMETER") existing: List<Candidate>
    ): Candidate? {
        val filename = path.name
        val normFilename = normalize(filename)
        val reasons = mutableListOf<String>()
        var score = 0

        // Cheap: filename contains the track name.
        if (normalizedTrackName.isNotBlank() && normFilename.contains(normalizedTrackName)) {
            score += 50
            reasons += "filename contains track name"
        }

        // Expensive: read tags so disc/track number + title comparison
        // is authoritative. Don't bail on parse failure — some junk
        // files in the tree just won't have useful tags.
        val tags = runCatching { AudioTagReader.read(path.toFile()) }.getOrNull()
            ?: AudioTagReader.AudioTags.EMPTY

        if (tags.discNumber == track.disc_number && tags.trackNumber == track.track_number) {
            score += 40
            reasons += "disc+track number match"
        } else if (tags.trackNumber == track.track_number && tags.discNumber == null) {
            score += 15
            reasons += "track number match (disc unknown)"
        }
        if (tags.title != null && normalize(tags.title).contains(normalizedTrackName)) {
            score += 25
            reasons += "file TITLE tag matches"
        }
        // Recording MBID match is the strongest signal we can get —
        // if the file carries it and it matches the track, it's almost
        // certainly the right file.
        if (!track.musicbrainz_recording_id.isNullOrBlank() &&
            tags.musicBrainzRecordingId == track.musicbrainz_recording_id) {
            score += 80
            reasons += "recording MBID match"
        }

        if (score == 0) return null
        return Candidate(
            path = path.toString(),
            score = score,
            reasons = reasons,
            tagDisc = tags.discNumber,
            tagTrack = tags.trackNumber,
            tagTitle = tags.title,
            tagAlbum = tags.album
        )
    }

    /** Lowercase, strip punctuation, collapse whitespace. Handy for loose comparisons. */
    private fun normalize(s: String): String {
        if (s.isBlank()) return ""
        return s.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    // --------------------------- diagnosis ---------------------------

    private fun diagnosisFor(
        track: Track,
        siblings: List<Track>,
        candidates: List<Candidate>,
        unmatched: List<UnmatchedAudio>
    ): String {
        return when {
            !track.file_path.isNullOrBlank() ->
                "Track already has file_path; it's linked — check if the file exists on disk."
            siblings.all { it.file_path.isNullOrBlank() } ->
                "No track on this album is linked to a file. Either nothing scanned yet, or the scanner couldn't match any files to this release. Check music_root_path + scanner logs."
            unmatched.any { it.match_status == "UNMATCHED" } ->
                "A file tagged with this track is sitting in unmatched_audio — re-run the unmatched-audio link flow for this album."
            candidates.any { it.score >= 90 } ->
                "A strong filesystem candidate exists (MBID match) — the file is on disk but never got linked. Likely cause: scanner ran before the file was in place, or a mismatched disc/track in MB's tracklist. Use force-link to attach the top candidate."
            candidates.any { it.score >= 40 } ->
                "Plausible filesystem candidates exist but none are slam-dunks. Check the candidate list for a likely match, then force-link."
            else ->
                "No matching files found on disk under the album's directory tree. The file is genuinely missing — upload it, then re-run the scanner."
        }
    }

    // ----------------------------- helpers ---------------------------

    private data class Candidate(
        val path: String,
        val score: Int,
        val reasons: List<String>,
        val tagDisc: Int?,
        val tagTrack: Int?,
        val tagTitle: String?,
        val tagAlbum: String?
    )

    /** Highest directory that's a parent of every input (or null for empty). */
    private fun Collection<File>.commonAncestorOrNull(): File? {
        if (isEmpty()) return null
        if (size == 1) return first().parentFile ?: first()
        val parts = map { it.absolutePath.split(File.separatorChar) }
        val shortest = parts.minOf { it.size }
        var commonDepth = 0
        outer@ for (i in 0 until shortest) {
            val v = parts[0][i]
            for (p in parts) if (p[i] != v) break@outer
            commonDepth++
        }
        if (commonDepth == 0) return null
        val common = parts[0].take(commonDepth).joinToString(File.separator)
        return File(common).takeIf { it.isDirectory }
    }

    private fun musicRoot(): String? =
        AppConfig.findAll()
            .firstOrNull { it.config_key == "music_root_path" }
            ?.config_val
            ?.takeIf { it.isNotBlank() }

    private fun unauthorized(): HttpResponse =
        HttpResponse.of(HttpStatus.UNAUTHORIZED)

    private fun notFound(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.NOT_FOUND)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
