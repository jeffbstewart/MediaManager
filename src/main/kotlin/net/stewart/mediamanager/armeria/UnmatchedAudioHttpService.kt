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
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import net.stewart.mediamanager.service.MusicBrainzHttpService
import net.stewart.mediamanager.service.MusicBrainzReleaseLookup
import net.stewart.mediamanager.service.MusicBrainzResult
import net.stewart.mediamanager.service.MusicBrainzService
import net.stewart.mediamanager.service.MusicIngestionService
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * Admin-only endpoints for the Unmatched Audio queue (M4). Mirrors
 * [UnmatchedBookHttpService] — when the music scanner can't auto-link
 * a file to a [Track], it parks the file here. Admin resolves each row
 * by picking a specific Track via [linkToTrack] or marking it [ignore].
 */
@Blocking
class UnmatchedAudioHttpService(
    private val musicBrainz: MusicBrainzService = MusicBrainzHttpService()
) {

    private val gson = Gson()

    @Get("/api/v2/admin/unmatched-audio")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val rows = UnmatchedAudio.findAll()
            .filter { it.match_status == UnmatchedAudioStatus.UNMATCHED.name }
            .sortedByDescending { it.discovered_at }
            .map { row ->
                mapOf(
                    "id" to row.id,
                    "file_path" to row.file_path,
                    "file_name" to row.file_name,
                    "file_size_bytes" to row.file_size_bytes,
                    "media_format" to row.media_format,
                    "parsed_title" to row.parsed_title,
                    "parsed_album" to row.parsed_album,
                    "parsed_album_artist" to row.parsed_album_artist,
                    "parsed_track_artist" to row.parsed_track_artist,
                    "parsed_track_number" to row.parsed_track_number,
                    "parsed_disc_number" to row.parsed_disc_number,
                    "parsed_duration_seconds" to row.parsed_duration_seconds,
                    "parsed_mb_release_id" to row.parsed_mb_release_id,
                    "parsed_mb_recording_id" to row.parsed_mb_recording_id,
                    "parsed_upc" to row.parsed_upc,
                    "parsed_isrc" to row.parsed_isrc,
                    "parsed_catalog_number" to row.parsed_catalog_number,
                    "parsed_label" to row.parsed_label,
                    "discovered_at" to row.discovered_at?.toString()
                )
            }

        return jsonResponse(gson.toJson(mapOf("files" to rows, "total" to rows.size)))
    }

    /**
     * Admin picks a specific [Track] to link this file to. Used when MB
     * tags were missing or incorrect, but the admin can identify the
     * matching track from an already-catalogued album. Sets
     * `track.file_path` and marks the unmatched row LINKED.
     */
    @Post("/api/v2/admin/unmatched-audio/{id}/link-track")
    fun linkToTrack(
        ctx: ServiceRequestContext,
        @Param("id") id: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val row = UnmatchedAudio.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        val trackId = (body["track_id"] as? Number)?.toLong()
            ?: return badRequest("track_id required")

        val track = Track.findById(trackId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        if (track.file_path != null && track.file_path != row.file_path) {
            return badRequest("track already linked to a different file")
        }

        val now = LocalDateTime.now()
        track.file_path = row.file_path
        track.updated_at = now
        track.save()

        row.match_status = UnmatchedAudioStatus.LINKED.name
        row.linked_track_id = track.id
        row.linked_at = now
        row.save()

        return jsonResponse(gson.toJson(mapOf(
            "ok" to true,
            "track_id" to track.id,
            "title_id" to track.title_id
        )))
    }

    /**
     * Search existing tracks for manual linking. Returns up to 20 hits
     * matching the query against the track title or the parent album name;
     * each row includes album + disc / track number so the admin can pick
     * the right slot when the track title isn't unique.
     */
    @Get("/api/v2/admin/unmatched-audio/search-tracks")
    fun searchTracks(ctx: ServiceRequestContext, @Param("q") query: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val q = query.trim()
        if (q.length < 2) return jsonResponse(gson.toJson(mapOf("tracks" to emptyList<Any>())))

        val lower = q.lowercase()
        val albumTitles = Title.findAll()
            .filter { it.media_type == net.stewart.mediamanager.entity.MediaType.ALBUM.name }
            .associateBy { it.id }
        val allTracks = Track.findAll()

        // Name-match against track, and also include every track on any
        // matching album — admins often remember the album, not the song.
        val albumHits = albumTitles.values
            .filter {
                it.name.lowercase().contains(lower) ||
                    (it.sort_name?.lowercase()?.contains(lower) == true)
            }
            .map { it.id }
            .toSet()

        val primaryArtistByTitle = TitleArtist.findAll()
            .filter { it.artist_order == 0 }
            .associate { it.title_id to it.artist_id }
        val artists = Artist.findAll().associateBy { it.id }

        val hits = allTracks
            .asSequence()
            .filter {
                it.title_id in albumHits ||
                    it.name.lowercase().contains(lower)
            }
            .sortedWith(compareBy(
                { albumTitles[it.title_id]?.name?.lowercase() ?: "" },
                { it.disc_number },
                { it.track_number }
            ))
            .take(50)
            .mapNotNull { track ->
                val title = albumTitles[track.title_id] ?: return@mapNotNull null
                val artistName = primaryArtistByTitle[title.id]?.let { artists[it]?.name }
                mapOf(
                    "track_id" to track.id,
                    "track_name" to track.name,
                    "disc_number" to track.disc_number,
                    "track_number" to track.track_number,
                    "album_title_id" to title.id,
                    "album_name" to title.name,
                    "artist_name" to artistName,
                    "already_linked" to (track.file_path != null)
                )
            }
            .toList()

        return jsonResponse(gson.toJson(mapOf("tracks" to hits)))
    }

    @Post("/api/v2/admin/unmatched-audio/{id}/ignore")
    fun ignore(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val row = UnmatchedAudio.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        row.match_status = UnmatchedAudioStatus.IGNORED.name
        row.save()
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    // -------------------------------------------------------------------
    // Group-based triage (the new flow)
    //
    // Flat-row UX falls apart once an admin has 60+ files staged and
    // wants to act on whole albums at a time. The "groups" endpoint
    // collapses rows by (album_artist, album), with multi-disc folders
    // (e.g. ".../Disc 1" + ".../Disc 2" siblings) merged into one
    // logical album. The follow-on endpoints operate on a group at a
    // time: search MB for candidate releases, link the whole album to
    // a chosen MBID, or create the album from file tags only when MB
    // doesn't have it.
    // -------------------------------------------------------------------

    @Get("/api/v2/admin/unmatched-audio/groups")
    fun listGroups(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val rows = UnmatchedAudio.findAll()
            .filter { it.match_status == UnmatchedAudioStatus.UNMATCHED.name }
        val groups = computeGroups(rows).map { it.toMap() }

        return jsonResponse(gson.toJson(mapOf(
            "groups" to groups,
            "total_groups" to groups.size,
            "total_files" to rows.size
        )))
    }

    @Post("/api/v2/admin/unmatched-audio/musicbrainz-search")
    fun searchMusicBrainz(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = parseBody(ctx) ?: return badRequest("invalid body")
        val ids = (body["unmatched_audio_ids"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
            ?: return badRequest("unmatched_audio_ids required")
        val rows = UnmatchedAudio.findAll().filter { it.id in ids }
        if (rows.isEmpty()) return badRequest("no rows found")

        val override = (body["query_override"] as? String)?.takeIf { it.isNotBlank() }

        val candidates = mutableListOf<String>()

        // 1) UPC tier — if all rows agree on a barcode, try it first.
        val dominantUpc = rows.mapNotNull { it.parsed_upc?.takeIf { s -> s.isNotBlank() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (override == null && dominantUpc != null) {
            (musicBrainz.lookupByBarcode(dominantUpc) as? MusicBrainzResult.Success)?.let {
                candidates += it.release.musicBrainzReleaseId
            }
        }

        // 2) Artist+Album tier — either the explicit override (admin-typed)
        //    or the dominant tag values from the group.
        val dominantArtist = rows.mapNotNull { it.parsed_album_artist?.takeIf { s -> s.isNotBlank() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val dominantAlbum = rows.mapNotNull { it.parsed_album?.takeIf { s -> s.isNotBlank() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        val (searchArtist, searchAlbum) = if (override != null) {
            // Treat override as a free-form query: split on " - " if present,
            // otherwise feed as the album with "Various Artists" as the
            // artist (matches MB compilation-search shape).
            val dash = override.indexOf(" - ")
            if (dash > 0) override.substring(0, dash) to override.substring(dash + 3)
            else (dominantArtist ?: "Various Artists") to override
        } else {
            (dominantArtist ?: "Various Artists") to (dominantAlbum ?: "")
        }
        if (searchAlbum.isNotBlank()) {
            candidates += musicBrainz.searchReleaseByArtistAndAlbum(searchArtist, searchAlbum)
        }

        // De-dup while preserving order; cap to the first 10 to keep MB
        // detail-fetch traffic bounded (each detail call is one 1.1 s tick).
        val ordered = candidates.distinct().take(10)

        // Fetch detail per candidate so the UI can show track count,
        // year, country, label without a second round trip.
        val details = ordered.mapNotNull { mbid ->
            val lookup = (musicBrainz.lookupByReleaseMbid(mbid) as? MusicBrainzResult.Success)?.release
            if (lookup != null) candidateInfo(rows, lookup) else null
        }

        return jsonResponse(gson.toJson(mapOf(
            "search_artist" to searchArtist,
            "search_album" to searchAlbum,
            "candidates" to details
        )))
    }

    @Post("/api/v2/admin/unmatched-audio/link-album-to-release")
    fun linkAlbumToRelease(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = parseBody(ctx) ?: return badRequest("invalid body")
        val ids = (body["unmatched_audio_ids"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
            ?: return badRequest("unmatched_audio_ids required")
        val mbid = (body["release_mbid"] as? String)?.takeIf { it.isNotBlank() }
            ?: return badRequest("release_mbid required")

        val rows = UnmatchedAudio.findAll().filter { it.id in ids }
        if (rows.isEmpty()) return badRequest("no rows found")

        val lookup = (musicBrainz.lookupByReleaseMbid(mbid) as? MusicBrainzResult.Success)?.release
            ?: return badRequest("MB release not found")

        val ingest = MusicIngestionService.ingest(
            upc = null,
            mediaFormat = MediaFormat.AUDIO_FLAC,
            lookup = lookup
        )
        val tracks = Track.findAll().filter { it.title_id == ingest.title.id }
        val (linked, failed) = linkRowsToTracks(rows, tracks)

        return jsonResponse(gson.toJson(mapOf(
            "title_id" to ingest.title.id,
            "title_name" to ingest.title.name,
            "linked" to linked.size,
            "failed" to failed
        )))
    }

    @Post("/api/v2/admin/unmatched-audio/link-album-manual")
    fun linkAlbumManual(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = parseBody(ctx) ?: return badRequest("invalid body")
        val ids = (body["unmatched_audio_ids"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
            ?: return badRequest("unmatched_audio_ids required")

        val rows = UnmatchedAudio.findAll().filter { it.id in ids }
        if (rows.isEmpty()) return badRequest("no rows found")

        val title = MusicIngestionService.ingestManualFromRows(rows)
        val tracks = Track.findAll().filter { it.title_id == title.id }
        val (linked, failed) = linkRowsToTracks(rows, tracks)

        return jsonResponse(gson.toJson(mapOf(
            "title_id" to title.id,
            "title_name" to title.name,
            "linked" to linked.size,
            "failed" to failed
        )))
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /** Stable ID for a group given its file-row IDs. */
    private fun groupIdFor(ids: List<Long>): String {
        val joined = ids.sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Group by (parsed_album_artist, parsed_album). When album_artist is
     * null (the typical Various Artists compilation case), group by album
     * alone — this merges sibling ".../Disc 1" + ".../Disc 2" folders
     * into one logical album as long as their album tag agrees.
     * When both are null/blank, fall back to the parent directory so
     * unrelated fragments don't collide.
     */
    internal fun computeGroups(rows: List<UnmatchedAudio>): List<UnmatchedAudioGroup> {
        if (rows.isEmpty()) return emptyList()
        val byKey = rows.groupBy { mergeKeyFor(it) }
        return byKey.map { (_, members) ->
            val sortedMembers = members.sortedWith(
                compareBy({ it.parsed_disc_number ?: 1 }, { it.parsed_track_number ?: 0 })
            )
            UnmatchedAudioGroup(
                groupId = groupIdFor(sortedMembers.mapNotNull { it.id }),
                dirs = sortedMembers.map { parentDir(it.file_path) }.distinct().sorted(),
                dominantAlbum = dominant(sortedMembers) { it.parsed_album },
                dominantAlbumArtist = dominant(sortedMembers) { it.parsed_album_artist },
                dominantUpc = dominant(sortedMembers) { it.parsed_upc },
                dominantMbReleaseId = dominant(sortedMembers) { it.parsed_mb_release_id },
                dominantLabel = dominant(sortedMembers) { it.parsed_label },
                dominantCatalogNumber = dominant(sortedMembers) { it.parsed_catalog_number },
                discNumbers = sortedMembers.mapNotNull { it.parsed_disc_number }.distinct().sorted(),
                totalFiles = sortedMembers.size,
                recordingMbidCount = sortedMembers.count { !it.parsed_mb_recording_id.isNullOrBlank() },
                files = sortedMembers
            )
        }.sortedWith(
            compareByDescending<UnmatchedAudioGroup> { it.totalFiles }
                .thenBy { it.dominantAlbum?.lowercase() ?: it.dirs.firstOrNull()?.lowercase() ?: "" }
        )
    }

    private fun mergeKeyFor(row: UnmatchedAudio): String {
        val album = row.parsed_album?.takeIf { it.isNotBlank() }
        val artist = row.parsed_album_artist?.takeIf { it.isNotBlank() }
        return when {
            album != null -> "album|${album.lowercase()}|${artist?.lowercase().orEmpty()}"
            else -> "dir|${parentDir(row.file_path)}"
        }
    }

    private fun parentDir(path: String): String =
        path.substringBeforeLast('/').ifEmpty { path.substringBeforeLast('\\') }

    private fun <T> dominant(rows: List<UnmatchedAudio>, picker: (UnmatchedAudio) -> T?): T? =
        rows.mapNotNull(picker).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * Build a single MB-search-result entry annotated with how well it
     * fits the group's files. The two key signals:
     *  - accommodates_files: every file's (disc, track) has a slot on
     *    the release. The scanner uses this same gate today.
     *  - recording_mbid_coverage: count of files whose
     *    parsed_mb_recording_id is on the release. Strong confirmation
     *    when non-zero — the new ripper writes recording MBIDs, so a
     *    perfect match is the gold standard.
     */
    private fun candidateInfo(rows: List<UnmatchedAudio>, lookup: MusicBrainzReleaseLookup): Map<String, Any?> {
        val positions = lookup.tracks.map { it.discNumber to it.trackNumber }.toSet()
        val recordingIds = lookup.tracks.map { it.musicBrainzRecordingId }.toSet()
        val accommodates = rows.all { row ->
            val tn = row.parsed_track_number ?: return@all true
            val dn = row.parsed_disc_number ?: 1
            (dn to tn) in positions
        }
        val mbidHits = rows.count { it.parsed_mb_recording_id in recordingIds && !it.parsed_mb_recording_id.isNullOrBlank() }
        return mapOf(
            "release_mbid" to lookup.musicBrainzReleaseId,
            "release_group_mbid" to lookup.musicBrainzReleaseGroupId,
            "title" to lookup.title,
            "artist_credit" to lookup.albumArtistCredits.joinToString(", ") { it.name },
            "year" to lookup.releaseYear,
            "label" to lookup.label,
            "barcode" to lookup.barcode,
            "track_count" to lookup.tracks.size,
            "disc_count" to lookup.tracks.map { it.discNumber }.distinct().size,
            "accommodates_files" to accommodates,
            "recording_mbid_coverage" to mbidHits
        )
    }

    /**
     * Link each unmatched row to a Track row on the freshly-ingested
     * title. Recording MBID first (most precise — the new ripper writes
     * these); falls through to (disc, track) for older rips. Updates
     * Track.file_path; deletes the unmatched row on success.
     *
     * Returns (linked rows, list of failure descriptors). Failures are
     * the rows that resolved a title but couldn't be matched to a track —
     * usually because the file's tags are slightly off.
     */
    private fun linkRowsToTracks(
        rows: List<UnmatchedAudio>,
        tracks: List<Track>
    ): Pair<List<UnmatchedAudio>, List<Map<String, Any?>>> {
        val now = LocalDateTime.now()
        val linked = mutableListOf<UnmatchedAudio>()
        val failed = mutableListOf<Map<String, Any?>>()
        for (row in rows) {
            val target = tracks.firstOrNull {
                !row.parsed_mb_recording_id.isNullOrBlank() &&
                    it.musicbrainz_recording_id == row.parsed_mb_recording_id
            } ?: tracks.firstOrNull {
                row.parsed_track_number != null &&
                    it.track_number == row.parsed_track_number &&
                    it.disc_number == (row.parsed_disc_number ?: 1)
            }
            if (target == null || (target.file_path != null && target.file_path != row.file_path)) {
                failed += mapOf(
                    "file_path" to row.file_path,
                    "reason" to (if (target == null) "no track slot match"
                                 else "track ${target.disc_number}/${target.track_number} already linked")
                )
                continue
            }
            target.file_path = row.file_path
            target.updated_at = now
            target.save()
            row.match_status = UnmatchedAudioStatus.LINKED.name
            row.linked_track_id = target.id
            row.linked_at = now
            row.save()
            linked += row
        }
        return linked to failed
    }

    private fun parseBody(ctx: ServiceRequestContext): Map<*, *>? {
        val str = ctx.request().aggregate().join().contentUtf8()
        return runCatching { gson.fromJson(str, Map::class.java) }.getOrNull()
    }

    /** Group payload for the JSON response. */
    internal data class UnmatchedAudioGroup(
        val groupId: String,
        val dirs: List<String>,
        val dominantAlbum: String?,
        val dominantAlbumArtist: String?,
        val dominantUpc: String?,
        val dominantMbReleaseId: String?,
        val dominantLabel: String?,
        val dominantCatalogNumber: String?,
        val discNumbers: List<Int>,
        val totalFiles: Int,
        val recordingMbidCount: Int,
        val files: List<UnmatchedAudio>
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "group_id" to groupId,
            "dirs" to dirs,
            "dominant_album" to dominantAlbum,
            "dominant_album_artist" to dominantAlbumArtist,
            "dominant_upc" to dominantUpc,
            "dominant_mb_release_id" to dominantMbReleaseId,
            "dominant_label" to dominantLabel,
            "dominant_catalog_number" to dominantCatalogNumber,
            "disc_numbers" to discNumbers,
            "total_files" to totalFiles,
            "recording_mbid_count" to recordingMbidCount,
            "file_ids" to files.mapNotNull { it.id },
            "files" to files.map { row ->
                mapOf(
                    "id" to row.id,
                    "file_path" to row.file_path,
                    "file_name" to row.file_name,
                    "parsed_title" to row.parsed_title,
                    "parsed_track_artist" to row.parsed_track_artist,
                    "parsed_track_number" to row.parsed_track_number,
                    "parsed_disc_number" to row.parsed_disc_number,
                    "parsed_duration_seconds" to row.parsed_duration_seconds,
                    "parsed_mb_recording_id" to row.parsed_mb_recording_id
                )
            }
        )
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(message: String): HttpResponse =
        HttpResponse.builder()
            .status(HttpStatus.BAD_REQUEST)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to message)))
            .build()
}
