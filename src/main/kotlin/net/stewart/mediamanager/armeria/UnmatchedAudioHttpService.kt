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
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import java.time.LocalDateTime

/**
 * Admin-only endpoints for the Unmatched Audio queue (M4). Mirrors
 * [UnmatchedBookHttpService] — when the music scanner can't auto-link
 * a file to a [Track], it parks the file here. Admin resolves each row
 * by picking a specific Track via [linkToTrack] or marking it [ignore].
 */
@Blocking
class UnmatchedAudioHttpService {

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
