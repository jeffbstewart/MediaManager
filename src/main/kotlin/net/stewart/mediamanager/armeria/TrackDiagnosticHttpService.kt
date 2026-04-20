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
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.service.AudioTagReader
import net.stewart.mediamanager.service.AutoTagApplicator
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
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

    private val log = LoggerFactory.getLogger(TrackDiagnosticHttpService::class.java)
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

    /**
     * Dump the raw ffprobe output for a specific file so we can see
     * every tag ffprobe exposes, not just the ones AudioTagReader
     * knows to look for. Handy when File Explorer / Picard show an
     * MBID we aren't picking up — we need to know whether ffprobe
     * is blind to it or whether we're keyed on the wrong name.
     *
     * Pass ?path=... (URL-encoded absolute path). Admin-only.
     */
    @Get("/api/v2/admin/diag/audio-file")
    fun diagnoseFile(
        ctx: ServiceRequestContext,
        @Param("path") path: String
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val file = File(path)
        if (!file.isFile) return notFound("Not a file: $path")

        // Run ffprobe directly to get the raw JSON output, without the
        // AudioTagReader.parse() filtering. Also run it with
        // show_format=private so some rarer metadata surfaces.
        val proc = ProcessBuilder(
            "ffprobe",
            "-v", "error",
            "-print_format", "json",
            "-show_format",
            "-show_streams",
            "-show_entries", "format_tags:stream_tags",
            file.absolutePath
        ).redirectErrorStream(false).start()
        val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return badRequest("ffprobe timed out")
        }
        val exitValue = proc.exitValue()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()

        // Also run AudioTagReader on the same file so we can see what
        // the parsed reader extracts — lets you compare "what ffprobe
        // saw" vs "what we kept." If the MBID is in ffprobe's output
        // but missing from the parsed struct, the key name is off.
        val parsed = runCatching { AudioTagReader.read(file) }.getOrNull()

        val response = mapOf(
            "path" to file.absolutePath,
            "size_bytes" to file.length(),
            "ffprobe_exit_code" to exitValue,
            "ffprobe_stdout_raw" to stdout,
            "ffprobe_stderr" to stderr,
            "audio_tag_reader_parsed" to parsed?.let { t ->
                mapOf(
                    "title" to t.title,
                    "album" to t.album,
                    "album_artist" to t.albumArtist,
                    "track_artist" to t.trackArtist,
                    "track_number" to t.trackNumber,
                    "disc_number" to t.discNumber,
                    "year" to t.year,
                    "musicbrainz_recording_id" to t.musicBrainzRecordingId,
                    "musicbrainz_release_id" to t.musicBrainzReleaseId,
                    "musicbrainz_release_group_id" to t.musicBrainzReleaseGroupId,
                    "musicbrainz_artist_id" to t.musicBrainzArtistId,
                    "upc" to t.upc,
                    "isrc" to t.isrc,
                    "genres" to t.genres,
                    "styles" to t.styles,
                    "bpm" to t.bpm,
                    "time_signature" to t.timeSignature
                )
            }
        )

        log.info("diagnoseFile path={} exit={} stdout_len={} parsed={}",
            file.absolutePath, exitValue, stdout.length,
            parsed?.let { "title=\"${it.title}\" recording_mbid=${it.musicBrainzRecordingId}" })

        val bytes = gson.toJson(response).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    /**
     * Rescan the album's directories for audio files and link any
     * strong matches to *unlinked* tracks. Never touches already-linked
     * tracks — this is the narrow recovery tool for "scanner ran before
     * the files were in place" / "one track didn't match" cases, not
     * the heavy "I picked the wrong MB release entirely" case (that's
     * a separate drop-and-restage flow).
     *
     * MBID match is the acceptance bar; we fall back to (disc, track)
     * number match ONLY when the track has no recording_mbid of its
     * own (so a mis-numbered compilation file can't silently overwrite
     * a better track row).
     */
    @Post("/api/v2/admin/albums/{titleId}/rescan")
    fun rescanAlbum(
        ctx: ServiceRequestContext,
        @Param("titleId") titleId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        // A fallback full-library walk does an ffprobe per audio file;
        // on a NAS with thousands of files that doesn't fit in the
        // default 10s request timeout. Give the handler a comfortable
        // ceiling — this is an admin-initiated one-shot action.
        ctx.setRequestTimeout(java.time.Duration.ofMinutes(5))

        val title = Title.findById(titleId) ?: return notFound("Title $titleId not found")
        val tracks = Track.findAll()
            .filter { it.title_id == titleId }
            .sortedWith(compareBy({ it.disc_number }, { it.track_number }))
        if (tracks.isEmpty()) {
            return badRequest("Title $titleId has no tracks — nothing to rescan.")
        }

        // Determine the search root. If any sibling track already has a
        // file_path, use the common ancestor of those dirs — covers the
        // normal "one track missed" case without scanning the whole
        // library. If NONE are linked, fall back to music_root and
        // walk everything (user asked for it; admin-initiated only).
        val linkedDirs = tracks.mapNotNull { it.file_path?.let { fp -> File(fp).parentFile } }
            .toSet()
        val siblingRoot = linkedDirs.commonAncestorOrNull()
        val musicRootDir = musicRoot()?.let { File(it) }?.takeIf { it.isDirectory }

        // Prefer the sibling-derived root for speed, but keep music_root
        // as a fallback we can rerun from if the focused walk finds
        // nothing. This handles cases where the sibling-derived root
        // ends up too narrow (e.g. all linked siblings are in one disc
        // subdir that doesn't contain the unlinked track) or plain wrong.
        val primaryRoot = siblingRoot ?: musicRootDir
        val fallbackRoot = if (siblingRoot != null && musicRootDir != null && siblingRoot != musicRootDir)
            musicRootDir else null

        if (primaryRoot == null || !primaryRoot.isDirectory) {
            return badRequest("No linked siblings and music_root_path not configured.")
        }

        val unlinkedTracks = tracks.filter { it.file_path.isNullOrBlank() }
        if (unlinkedTracks.isEmpty()) {
            log.info("rescanAlbum title={} \"{}\": all {} tracks already linked, nothing to do",
                titleId, title.name, tracks.size)
            return jsonOk(mapOf(
                "linked" to 0,
                "skipped_already_linked" to tracks.size,
                "no_match" to 0,
                "candidates_considered" to 0,
                "message" to "All tracks already linked — nothing to do."
            ))
        }

        val alreadyLinkedPaths = tracks.mapNotNull { it.file_path }.toSet()
        val filesConsidered = mutableListOf<Pair<Path, AudioTagReader.AudioTags>>()
        val audioExts = setOf("flac", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav")
        var filesWalked = 0
        var filesAlreadyLinked = 0
        var filesWrongAlbumTag = 0
        var filesPathRejected = 0
        var filesAcceptedByArtistPosition = 0
        var rootsWalked = mutableListOf<String>()
        // Dedup across walks — the fallback music_root walk overlaps
        // the sibling-root walk (sibling root is usually a subtree of
        // music_root), and we don't want to double-count the same
        // files in the summary.
        val seenPaths = mutableSetOf<String>()
        // Keep a handful of rejected album-tag strings so the response
        // can show the admin exactly why nothing matched (e.g. "files
        // say 'Whitney Houston: The Greatest Hits', title says 'The
        // Ultimate Collection'").
        val rejectedAlbumSamples = linkedSetOf<String>()

        val titleNorm = normalize(title.name)
        val primaryArtistNames: List<String> = run {
            val artistLinkIds = net.stewart.mediamanager.entity.TitleArtist.findAll()
                .filter { it.title_id == titleId }
                .map { it.artist_id }
                .toSet()
            if (artistLinkIds.isEmpty()) emptyList()
            else net.stewart.mediamanager.entity.Artist.findAll()
                .filter { it.id in artistLinkIds }
                .map { normalize(it.name) }
                .filter { it.isNotBlank() }
        }
        // MBID bypasses for the album-tag check. Two signals, either
        // sufficient:
        //   - Per-track recording MBID matches any track on this album.
        //     Written by Picard to MUSICBRAINZ_TRACKID (Vorbis) /
        //     TXXX:MusicBrainz Track Id (ID3).
        //   - File's release MBID matches the title's release MBID.
        //     Written by Picard to MUSICBRAINZ_ALBUMID. Useful when the
        //     file has no per-track MBID but does have the album one.
        val albumRecordingMbids: Set<String> = tracks
            .mapNotNull { it.musicbrainz_recording_id?.takeIf { m -> m.isNotBlank() } }
            .toSet()
        val albumReleaseMbid: String? = title.musicbrainz_release_id
            ?.takeIf { it.isNotBlank() }
        val albumReleaseGroupMbid: String? = title.musicbrainz_release_group_id
            ?.takeIf { it.isNotBlank() }

        // (disc, track) slots this album contains. Used by the
        // artist-plus-position bypass below as a cheap structural match.
        val albumSlots: Set<Pair<Int, Int>> = tracks
            .map { it.disc_number to it.track_number }
            .toSet()
        val primaryArtistNamesSet: Set<String> = primaryArtistNames.toSet()

        log.info(
            "rescanAlbum title={} \"{}\": {} tracks total, {} unlinked. " +
            "Release MBID={} ReleaseGroup={} Recording MBIDs on album={}",
            titleId, title.name, tracks.size, unlinkedTracks.size,
            albumReleaseMbid ?: "(none)",
            albumReleaseGroupMbid ?: "(none)",
            albumRecordingMbids.size
        )

        // Walk strategy: start from `primaryRoot`; if that finds zero
        // audio files, try `fallbackRoot` (typically music_root). This
        // recovers from cases where the sibling-derived common ancestor
        // ended up too narrow — the classic multi-disc mis-pick:
        // linked siblings sit entirely under one disc directory so the
        // ancestor never walked up far enough to see the other disc's
        // files. Covered here by retrying from the full library root.
        fun walkOne(root: File, depth: Int, pathPrefilter: Boolean) {
            rootsWalked += root.absolutePath
            Files.walk(root.toPath(), depth).use { stream ->
                stream.forEach { p ->
                    if (!p.isRegularFile()) return@forEach
                    if (p.extension.lowercase() !in audioExts) return@forEach
                    if (!seenPaths.add(p.toString())) return@forEach
                    filesWalked++
                    if (p.toString() in alreadyLinkedPaths) {
                        filesAlreadyLinked++
                        return@forEach
                    }
                    // Path-based prefilter on the full-library walk so
                    // we don't ffprobe every file under music_root.
                    // Require the normalized path to contain either the
                    // album title OR the primary artist / album artist
                    // so we still catch files in artist-named folders
                    // whose directory doesn't carry the album name.
                    if (pathPrefilter) {
                        val pathNorm = normalize(p.toString())
                        val hit = (titleNorm.isNotBlank() && pathNorm.contains(titleNorm)) ||
                            primaryArtistNames.any { a ->
                                a.isNotBlank() && pathNorm.contains(a)
                            }
                        if (!hit) {
                            filesPathRejected++
                            return@forEach
                        }
                    }
                    val tags = runCatching { AudioTagReader.read(p.toFile()) }.getOrNull()
                        ?: AudioTagReader.AudioTags.EMPTY
                    // MBID match bypasses the album-tag check — the
                    // file is authoritatively on this album even if
                    // its album string doesn't match our title name.
                    val recordingBypass = tags.musicBrainzRecordingId != null &&
                        tags.musicBrainzRecordingId in albumRecordingMbids
                    val releaseBypass = albumReleaseMbid != null &&
                        tags.musicBrainzReleaseId == albumReleaseMbid
                    val releaseGroupBypass = albumReleaseGroupMbid != null &&
                        tags.musicBrainzReleaseGroupId == albumReleaseGroupMbid
                    val mbidOk = recordingBypass || releaseBypass || releaseGroupBypass
                    if (!mbidOk && !albumTagLooksRight(tags, title)) {
                        filesWrongAlbumTag++
                        if (rejectedAlbumSamples.size < 5) {
                            tags.album?.takeIf { it.isNotBlank() }
                                ?.let { rejectedAlbumSamples.add(it) }
                            // Log first few rejections verbatim so the
                            // admin can see exactly what the tagger wrote
                            // vs what the catalog holds. Only the first 5
                            // per run — we don't want to flood binnacle.
                            log.info(
                                "rescanAlbum title={}: REJECTED path={} " +
                                "FILE[album=\"{}\" recording_mbid={} release_mbid={}] " +
                                "vs CATALOG[title=\"{}\" release_mbid={}]",
                                titleId, p,
                                tags.album ?: "(null)",
                                tags.musicBrainzRecordingId ?: "(null)",
                                tags.musicBrainzReleaseId ?: "(null)",
                                title.name,
                                albumReleaseMbid ?: "(null)"
                            )
                        }
                        return@forEach
                    }
                    filesConsidered += p to tags
                }
            }
        }

        // Sibling-derived walks stay shallow since we know we're inside
        // the album directory tree. The full-library fallback walks
        // deeper because some setups nest several levels under
        // music_root (e.g. /music/lossless/artist/album/disc/).
        // Sibling-root walks skip the path prefilter — we're already
        // inside the album tree so every audio file is a candidate.
        // The fallback library walk uses the prefilter to avoid
        // ffprobing every file under music_root.
        log.info("rescanAlbum title={}: walking primary root {} (prefilter={})",
            titleId, primaryRoot, siblingRoot == null)
        walkOne(primaryRoot, depth = if (siblingRoot != null) 4 else 8, pathPrefilter = siblingRoot == null)
        log.info(
            "rescanAlbum title={}: primary walk done. " +
            "walked={}, kept={}, already_linked={}, wrong_album={}, path_skipped={}",
            titleId, filesWalked, filesConsidered.size,
            filesAlreadyLinked, filesWrongAlbumTag, filesPathRejected
        )
        if (filesConsidered.isEmpty() && fallbackRoot != null) {
            log.info("rescanAlbum title={}: no candidates from primary root, " +
                "falling back to music_root {}", titleId, fallbackRoot)
            walkOne(fallbackRoot, depth = 8, pathPrefilter = true)
            log.info(
                "rescanAlbum title={}: fallback walk done. " +
                "walked={}, kept={}, already_linked={}, wrong_album={}, path_skipped={}",
                titleId, filesWalked, filesConsidered.size,
                filesAlreadyLinked, filesWrongAlbumTag, filesPathRejected
            )
        }

        val now = LocalDateTime.now()
        var linkedCount = 0
        val failures = mutableListOf<Map<String, Any?>>()
        val perTrackBest = mutableMapOf<Long, Pair<Path, AudioTagReader.AudioTags>>()

        // For each unlinked track, pick the best file by MBID match,
        // then by (disc, track) number, then by title-name similarity.
        for (track in unlinkedTracks) {
            val best = filesConsidered
                .mapNotNull { (p, tags) ->
                    val score = scoreMatch(track, tags, p.name)
                    if (score > 0) Triple(p, tags, score) else null
                }
                .maxByOrNull { it.third }
                ?: continue
            perTrackBest[track.id!!] = best.first to best.second
        }

        // Two different tracks could pick the same file if, say, MBIDs
        // are absent and disc/track numbering collides. Resolve by
        // score — the better-scoring track wins the file, the other
        // stays unlinked and reports no match.
        val fileToTrack = mutableMapOf<Path, Long>()
        val trackScores = mutableMapOf<Long, Int>()
        for (track in unlinkedTracks) {
            val entry = perTrackBest[track.id!!] ?: continue
            val (path, tags) = entry
            val score = scoreMatch(track, tags, path.name)
            val incumbent = fileToTrack[path]
            if (incumbent == null || score > (trackScores[incumbent] ?: 0)) {
                if (incumbent != null) trackScores.remove(incumbent)
                fileToTrack[path] = track.id!!
                trackScores[track.id!!] = score
            }
        }

        for ((path, trackId) in fileToTrack) {
            val track = tracks.first { it.id == trackId }
            val tags = perTrackBest[trackId]!!.second
            val titleYear = title.release_year
            track.file_path = path.toString()
            track.bpm = tags.bpm
            track.time_signature = tags.timeSignature
            track.updated_at = now
            track.save()
            AutoTagApplicator.applyToTrack(AutoTagApplicator.TrackAutoTagInput(
                trackId = trackId,
                genres = tags.genres,
                styles = tags.styles,
                bpm = tags.bpm,
                timeSignature = tags.timeSignature,
                year = titleYear
            ))
            linkedCount++
            log.info("rescanAlbum title={}: linked track {} disc={} track={} \"{}\" -> {}",
                titleId, trackId, track.disc_number, track.track_number, track.name, path)
        }
        if (linkedCount > 0) AutoTagApplicator.applyToAlbum(titleId)

        val stillUnlinked = unlinkedTracks.filter { it.file_path.isNullOrBlank() }
        for (t in stillUnlinked) {
            failures += mapOf(
                "track_id" to t.id,
                "disc_number" to t.disc_number,
                "track_number" to t.track_number,
                "name" to t.name
            )
        }

        log.info(
            "rescanAlbum title={} \"{}\" FINAL: linked={}, still_unlinked={}, " +
            "candidates_considered={}, files_walked={}, wrong_album={}, " +
            "path_skipped={}, already_linked={}, rejected_album_samples={}",
            titleId, title.name, linkedCount, stillUnlinked.size,
            filesConsidered.size, filesWalked, filesWrongAlbumTag,
            filesPathRejected, filesAlreadyLinked,
            rejectedAlbumSamples.toList()
        )

        return jsonOk(mapOf(
            "linked" to linkedCount,
            "skipped_already_linked" to (tracks.size - unlinkedTracks.size),
            "no_match" to stillUnlinked.size,
            "candidates_considered" to filesConsidered.size,
            "files_walked" to filesWalked,
            "files_already_linked_elsewhere" to filesAlreadyLinked,
            "files_wrong_album_tag" to filesWrongAlbumTag,
            "files_path_rejected" to filesPathRejected,
            "rejected_album_tag_samples" to rejectedAlbumSamples.toList(),
            "roots_walked" to rootsWalked,
            "music_root_configured" to (musicRootDir?.absolutePath ?: "(not set)"),
            "unlinked_after_rescan" to failures
        ))
    }

    /**
     * Scoring used by rescanAlbum to pick the best file per track.
     * Higher is better; 0 means "not a candidate."
     */
    private fun scoreMatch(
        track: Track,
        tags: AudioTagReader.AudioTags,
        filename: String
    ): Int {
        var score = 0
        // MBID is the strongest signal — use it alone when both sides
        // carry one and they match.
        if (!track.musicbrainz_recording_id.isNullOrBlank() &&
            tags.musicBrainzRecordingId == track.musicbrainz_recording_id) {
            return 100
        }
        // Otherwise require disc + track number agreement. This is the
        // conservative floor — without MBID we need positional proof.
        if (tags.discNumber == track.disc_number &&
            tags.trackNumber == track.track_number) {
            score += 50
            // Track name / filename similarity gets us over the line
            // when two discs have overlapping numbering (compilations).
            val normName = normalize(track.name)
            if (normName.isNotBlank()) {
                if (tags.title != null && normalize(tags.title).contains(normName)) score += 20
                if (normalize(filename).contains(normName)) score += 10
            }
        }
        return score
    }

    /**
     * True when the file's album / album_artist tag reasonably matches
     * the title row. Protects the full-library fallback walk from
     * pulling in unrelated files.
     */
    private fun albumTagLooksRight(
        tags: AudioTagReader.AudioTags,
        title: Title
    ): Boolean {
        val album = tags.album ?: return false
        val titleNorm = normalize(title.name)
        if (titleNorm.isBlank()) return false
        val albumNorm = normalize(album)
        // Either direction: "The Greatest Hits" vs "The Greatest Hits, Disc 1".
        return albumNorm.contains(titleNorm) || titleNorm.contains(albumNorm)
    }

    private fun jsonOk(payload: Map<String, Any?>): HttpResponse {
        val bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
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
