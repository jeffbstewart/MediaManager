package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Walks an album's directory tree and links unlinked tracks to audio
 * files on disk by comparing ID3 / Vorbis tags against the catalog row.
 *
 * Lifted out of the original [net.stewart.mediamanager.armeria.TrackDiagnosticHttpService]
 * monolith so both the legacy REST endpoint and the gRPC
 * `AdminService.RescanAlbum` RPC consume the same scanner. The
 * scoring + walk + bypass rules are unchanged from the REST handler;
 * the only difference is the return type — a structured [Outcome]
 * instead of an HTTP response.
 *
 * Tag-match strategy (same as the legacy handler):
 *   1. MBID bypass — recording / release / release-group MBIDs.
 *   2. Artist + (disc, track) position bypass for un-tagged rips.
 *   3. Album-tag substring match as the conservative gate when no
 *      MBID and no artist+position evidence exists.
 *
 * Per-track pick uses [scoreMatch]; ties resolve by score with the
 * better-scoring track winning the file.
 */
object AlbumRescanService {

    private val log = LoggerFactory.getLogger(AlbumRescanService::class.java)

    /** Outcome of a rescan call. Failures carry just enough context for
     *  the caller (REST or gRPC) to translate to the appropriate
     *  status code + message. */
    sealed interface Outcome {
        data class Success(val result: Result) : Outcome
        data object TitleNotFound : Outcome
        data object NoTracks : Outcome
        data object NoSearchRoot : Outcome
    }

    data class Result(
        val linked: Int,
        val skippedAlreadyLinked: Int,
        val noMatch: Int,
        val candidatesConsidered: Int,
        val filesWalked: Int,
        val filesAlreadyLinkedElsewhere: Int,
        val filesWrongAlbumTag: Int,
        val filesPathRejected: Int,
        val filesAcceptedByArtistPosition: Int,
        val rejectedAlbumTagSamples: List<String>,
        val rootsWalked: List<String>,
        val musicRootConfigured: String,
        val unlinkedAfterRescan: List<UnlinkedTrack>,
        val message: String? = null,
    )

    data class UnlinkedTrack(
        val trackId: Long,
        val discNumber: Int,
        val trackNumber: Int,
        val name: String,
    )

    fun rescan(titleId: Long): Outcome {
        val title = Title.findById(titleId) ?: return Outcome.TitleNotFound
        val tracks = Track.findAll()
            .filter { it.title_id == titleId }
            .sortedWith(compareBy({ it.disc_number }, { it.track_number }))
        if (tracks.isEmpty()) return Outcome.NoTracks

        val linkedDirs = tracks.mapNotNull { it.file_path?.let { fp -> File(fp).parentFile } }
            .toSet()
        val siblingRoot = linkedDirs.commonAncestorOrNull()
        val musicRootDir = musicRoot()?.let { File(it) }?.takeIf { it.isDirectory }

        val primaryRoot = siblingRoot ?: musicRootDir
        val fallbackRoot = if (siblingRoot != null && musicRootDir != null && siblingRoot != musicRootDir)
            musicRootDir else null

        if (primaryRoot == null || !primaryRoot.isDirectory) {
            return Outcome.NoSearchRoot
        }

        val unlinkedTracks = tracks.filter { it.file_path.isNullOrBlank() }
        if (unlinkedTracks.isEmpty()) {
            log.info("rescanAlbum title={} \"{}\": all {} tracks already linked, nothing to do",
                titleId, title.name, tracks.size)
            return Outcome.Success(Result(
                linked = 0,
                skippedAlreadyLinked = tracks.size,
                noMatch = 0,
                candidatesConsidered = 0,
                filesWalked = 0,
                filesAlreadyLinkedElsewhere = 0,
                filesWrongAlbumTag = 0,
                filesPathRejected = 0,
                filesAcceptedByArtistPosition = 0,
                rejectedAlbumTagSamples = emptyList(),
                rootsWalked = emptyList(),
                musicRootConfigured = musicRootDir?.absolutePath ?: "(not set)",
                unlinkedAfterRescan = emptyList(),
                message = "All tracks already linked — nothing to do.",
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
        val rootsWalked = mutableListOf<String>()
        // Dedup paths across the primary + fallback walks (sibling root
        // is usually a subtree of music_root) so summary counters don't
        // double-count.
        val seenPaths = mutableSetOf<String>()
        // Limited capture of rejected album-tag strings so the response
        // can show the admin exactly why nothing matched.
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
        val albumRecordingMbids: Set<String> = tracks
            .mapNotNull { it.musicbrainz_recording_id?.takeIf { m -> m.isNotBlank() } }
            .toSet()
        val albumReleaseMbid: String? = title.musicbrainz_release_id
            ?.takeIf { it.isNotBlank() }
        val albumReleaseGroupMbid: String? = title.musicbrainz_release_group_id
            ?.takeIf { it.isNotBlank() }

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

                    val recordingBypass = tags.musicBrainzRecordingId != null &&
                        tags.musicBrainzRecordingId in albumRecordingMbids
                    val releaseBypass = albumReleaseMbid != null &&
                        tags.musicBrainzReleaseId == albumReleaseMbid
                    val releaseGroupBypass = albumReleaseGroupMbid != null &&
                        tags.musicBrainzReleaseGroupId == albumReleaseGroupMbid
                    val mbidOk = recordingBypass || releaseBypass || releaseGroupBypass

                    val fileArtistNorm = (tags.albumArtist ?: tags.trackArtist)
                        ?.let { normalize(it) }
                    val artistHit = fileArtistNorm != null &&
                        fileArtistNorm in primaryArtistNamesSet
                    val disc = tags.discNumber
                    val trackNum = tags.trackNumber
                    val positionHit = disc != null && trackNum != null &&
                        (disc to trackNum) in albumSlots
                    val artistPositionOk = artistHit && positionHit

                    if (!mbidOk && !artistPositionOk && !albumTagLooksRight(tags, title)) {
                        filesWrongAlbumTag++
                        if (rejectedAlbumSamples.size < 5) {
                            tags.album?.takeIf { it.isNotBlank() }
                                ?.let { rejectedAlbumSamples.add(it) }
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
                    if (artistPositionOk && !mbidOk && !albumTagLooksRight(tags, title)) {
                        filesAcceptedByArtistPosition++
                        if (filesAcceptedByArtistPosition <= 10) {
                            log.info(
                                "rescanAlbum title={}: ACCEPT by artist+position path={} " +
                                "FILE[artist=\"{}\" disc={} track={}]",
                                titleId, p,
                                tags.albumArtist ?: tags.trackArtist ?: "(null)",
                                tags.discNumber, tags.trackNumber
                            )
                        }
                    }
                    filesConsidered += p to tags
                }
            }
        }

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
        val perTrackBest = mutableMapOf<Long, Pair<Path, AudioTagReader.AudioTags>>()

        for (track in unlinkedTracks) {
            val best = filesConsidered
                .mapNotNull { (p, tags) ->
                    val score = scoreMatch(track, tags, p.toFile().name)
                    if (score > 0) Triple(p, tags, score) else null
                }
                .maxByOrNull { it.third }
                ?: continue
            perTrackBest[track.id!!] = best.first to best.second
        }

        val fileToTrack = mutableMapOf<Path, Long>()
        val trackScores = mutableMapOf<Long, Int>()
        for (track in unlinkedTracks) {
            val entry = perTrackBest[track.id!!] ?: continue
            val (path, tags) = entry
            val score = scoreMatch(track, tags, path.toFile().name)
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

        val stillUnlinked = unlinkedTracks
            .filter { it.file_path.isNullOrBlank() }
            .map { t ->
                UnlinkedTrack(
                    trackId = t.id!!,
                    discNumber = t.disc_number,
                    trackNumber = t.track_number,
                    name = t.name,
                )
            }

        log.info(
            "rescanAlbum title={} \"{}\" FINAL: linked={}, still_unlinked={}, " +
            "candidates_considered={}, accepted_by_artist_position={}, " +
            "files_walked={}, wrong_album={}, path_skipped={}, already_linked={}, " +
            "rejected_album_samples={}",
            titleId, title.name, linkedCount, stillUnlinked.size,
            filesConsidered.size, filesAcceptedByArtistPosition,
            filesWalked, filesWrongAlbumTag, filesPathRejected, filesAlreadyLinked,
            rejectedAlbumSamples.toList()
        )

        return Outcome.Success(Result(
            linked = linkedCount,
            skippedAlreadyLinked = tracks.size - unlinkedTracks.size,
            noMatch = stillUnlinked.size,
            candidatesConsidered = filesConsidered.size,
            filesWalked = filesWalked,
            filesAlreadyLinkedElsewhere = filesAlreadyLinked,
            filesWrongAlbumTag = filesWrongAlbumTag,
            filesPathRejected = filesPathRejected,
            filesAcceptedByArtistPosition = filesAcceptedByArtistPosition,
            rejectedAlbumTagSamples = rejectedAlbumSamples.toList(),
            rootsWalked = rootsWalked,
            musicRootConfigured = musicRootDir?.absolutePath ?: "(not set)",
            unlinkedAfterRescan = stillUnlinked,
        ))
    }

    /** Higher score = better candidate. 0 = not a candidate. MBID match
     *  short-circuits at 100; otherwise require disc + track agreement
     *  (50) plus optional name / filename similarity (+10..+30). */
    private fun scoreMatch(
        track: Track,
        tags: AudioTagReader.AudioTags,
        filename: String
    ): Int {
        var score = 0
        if (!track.musicbrainz_recording_id.isNullOrBlank() &&
            tags.musicBrainzRecordingId == track.musicbrainz_recording_id) {
            return 100
        }
        if (tags.discNumber == track.disc_number &&
            tags.trackNumber == track.track_number) {
            score += 50
            val normName = normalize(track.name)
            if (normName.isNotBlank()) {
                if (tags.title != null && normalize(tags.title).contains(normName)) score += 20
                if (normalize(filename).contains(normName)) score += 10
            }
        }
        return score
    }

    /** True when the file's album tag reasonably matches the title row.
     *  Protects the full-library fallback walk from pulling in unrelated
     *  files. */
    private fun albumTagLooksRight(
        tags: AudioTagReader.AudioTags,
        title: Title
    ): Boolean {
        val album = tags.album ?: return false
        val titleNorm = normalize(title.name)
        if (titleNorm.isBlank()) return false
        val albumNorm = normalize(album)
        return albumNorm.contains(titleNorm) || titleNorm.contains(albumNorm)
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

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
}
