package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Playlist
import net.stewart.mediamanager.entity.PlaylistTrack
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.service.PlaylistService

/**
 * REST API for slice-1 playlists. Owner-only mutations are enforced
 * inside [PlaylistService]; the duplicate endpoint deliberately does
 * not require ownership — any user can fork any playlist.
 *
 * Routes:
 *   GET    /api/v2/playlists                  — all playlists (browse)
 *   GET    /api/v2/playlists/mine             — playlists this user owns
 *   GET    /api/v2/playlists/{id}             — detail w/ tracks
 *   POST   /api/v2/playlists                  — create empty
 *   POST   /api/v2/playlists/{id}/rename
 *   DELETE /api/v2/playlists/{id}
 *   POST   /api/v2/playlists/{id}/tracks      — append track ids
 *   DELETE /api/v2/playlists/{id}/tracks/{playlistTrackId}
 *   POST   /api/v2/playlists/{id}/reorder     — full new order
 *   POST   /api/v2/playlists/{id}/hero        — set/clear hero track
 *   POST   /api/v2/playlists/{id}/duplicate   — fork (any user)
 *   GET    /api/v2/playlists/library-shuffle  — ephemeral library shuffle
 */
@Blocking
class PlaylistHttpService {

    private val gson = Gson()

    @Get("/api/v2/playlists")
    fun listAll(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val playlists = PlaylistService.listVisibleTo(user.id!!)
        val fallback = computeFallbackPosters(playlists)
        val rows = playlists.map { it.toSummary(user.id!!, fallback[it.id]) }
        val smart = PlaylistService.listSmartPlaylists(user).map { it.toSummary() }
        return jsonResponse(gson.toJson(mapOf(
            "playlists" to rows,
            "smart_playlists" to smart
        )))
    }

    @Get("/api/v2/playlists/smart/{key}")
    fun getSmart(ctx: ServiceRequestContext, @Param("key") key: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val view = PlaylistService.getSmartPlaylist(key, user) ?: return notFound()
        val payload = mapOf(
            "key" to view.key,
            "name" to view.name,
            "description" to view.description,
            "is_smart" to true,
            "track_count" to view.tracks.size,
            "total_duration_seconds" to view.totalDurationSeconds,
            "hero_poster_url" to view.heroTitleId?.let { Title.findById(it)?.posterUrl(PosterSize.FULL) },
            "tracks" to view.tracks.map { entry ->
                val title = Title.findById(entry.track.title_id)
                mapOf(
                    "playlist_track_id" to entry.playlistTrackId,
                    "position" to entry.position,
                    "track_id" to entry.track.id,
                    "track_name" to entry.track.name,
                    "duration_seconds" to entry.track.duration_seconds,
                    "title_id" to entry.track.title_id,
                    "title_name" to title?.name,
                    "poster_url" to title?.posterUrl(PosterSize.THUMBNAIL),
                    "playable" to !entry.track.file_path.isNullOrBlank()
                )
            }
        )
        return jsonResponse(gson.toJson(payload))
    }

    @Get("/api/v2/playlists/mine")
    fun listMine(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val playlists = PlaylistService.listOwnedBy(user.id!!)
        val fallback = computeFallbackPosters(playlists)
        val rows = playlists.map { it.toSummary(user.id!!, fallback[it.id]) }
        return jsonResponse(gson.toJson(mapOf("playlists" to rows)))
    }

    @Get("/api/v2/playlists/library-shuffle")
    fun libraryShuffle(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val tracks = PlaylistService.libraryShuffle(user, limit = 200)
        val titlesById = Title.findAll().filter { t -> tracks.any { it.title_id == t.id } }
            .associateBy { it.id }
        val rows = tracks.map { it.toJson(titlesById[it.title_id]) }
        return jsonResponse(gson.toJson(mapOf("tracks" to rows)))
    }

    @Get("/api/v2/playlists/{id}")
    fun get(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val view = try {
            PlaylistService.getDetail(id, viewerUserId = user.id)
        } catch (_: PlaylistService.PlaylistNotFound) {
            return notFound()
        } catch (_: PlaylistService.PlaylistAccessDenied) {
            // Indistinguishable from "not found" by design — don't leak
            // the existence of private playlists to non-owners.
            return notFound()
        }

        val heroTitle = view.heroTitleId?.let { Title.findById(it) }
        val resume = PlaylistService.getResume(user.id!!, id)
        val payload = mapOf(
            "id" to view.playlist.id,
            "name" to view.playlist.name,
            "description" to view.playlist.description,
            "owner_user_id" to view.playlist.owner_user_id,
            "owner_username" to view.ownerUsername,
            "is_owner" to (view.playlist.owner_user_id == user.id),
            "is_private" to view.playlist.is_private,
            "hero_track_id" to view.playlist.hero_track_id,
            "hero_poster_url" to heroTitle?.posterUrl(PosterSize.FULL),
            "track_count" to view.tracks.size,
            "total_duration_seconds" to view.totalDurationSeconds,
            "created_at" to view.playlist.created_at?.toString(),
            "updated_at" to view.playlist.updated_at?.toString(),
            "resume" to resume?.let { r ->
                mapOf(
                    "playlist_track_id" to r.playlistTrackId,
                    "track_id" to r.trackId,
                    "position_seconds" to r.positionSeconds,
                    "updated_at" to r.updatedAt?.toString()
                )
            },
            "tracks" to view.tracks.map { entry ->
                val title = Title.findById(entry.track.title_id)
                mapOf(
                    "playlist_track_id" to entry.playlistTrackId,
                    "position" to entry.position,
                    "track_id" to entry.track.id,
                    "track_name" to entry.track.name,
                    "duration_seconds" to entry.track.duration_seconds,
                    "title_id" to entry.track.title_id,
                    "title_name" to title?.name,
                    "poster_url" to title?.posterUrl(PosterSize.THUMBNAIL),
                    "playable" to !entry.track.file_path.isNullOrBlank()
                )
            }
        )
        return jsonResponse(gson.toJson(payload))
    }

    @Post("/api/v2/playlists")
    fun create(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val body = readBody(ctx)
        val name = (body["name"] as? String)?.trim().orEmpty()
        if (name.isBlank()) return badRequest("name required")
        val description = body["description"] as? String

        val pl = PlaylistService.create(user, name, description)
        return jsonResponse(gson.toJson(mapOf("id" to pl.id, "name" to pl.name)))
    }

    @Post("/api/v2/playlists/{id}/rename")
    fun rename(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val body = readBody(ctx)
        val name = (body["name"] as? String)?.trim().orEmpty()
        if (name.isBlank()) return badRequest("name required")
        val description = body["description"] as? String
        return ownedAction(id, user) { _ ->
            PlaylistService.rename(id, user, name, description)
            jsonResponse(gson.toJson(mapOf("ok" to true)))
        }
    }

    @Delete("/api/v2/playlists/{id}")
    fun delete(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        return ownedAction(id, user) { _ ->
            PlaylistService.delete(id, user)
            jsonResponse(gson.toJson(mapOf("ok" to true)))
        }
    }

    @Post("/api/v2/playlists/{id}/tracks")
    fun addTracks(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val body = readBody(ctx)
        val rawIds = body["track_ids"] as? List<*> ?: return badRequest("track_ids required")
        val trackIds = rawIds.mapNotNull { (it as? Number)?.toLong() }
        return ownedAction(id, user) { _ ->
            val added = PlaylistService.addTracks(id, user, trackIds)
            jsonResponse(gson.toJson(mapOf(
                "added" to added.size,
                "playlist_track_ids" to added.map { it.id }
            )))
        }
    }

    @Delete("/api/v2/playlists/{id}/tracks/{playlistTrackId}")
    fun removeTrack(
        ctx: ServiceRequestContext,
        @Param("id") id: Long,
        @Param("playlistTrackId") playlistTrackId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        return ownedAction(id, user) { _ ->
            PlaylistService.removeTrack(id, user, playlistTrackId)
            jsonResponse(gson.toJson(mapOf("ok" to true)))
        }
    }

    @Post("/api/v2/playlists/{id}/reorder")
    fun reorder(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val body = readBody(ctx)
        val rawIds = body["playlist_track_ids"] as? List<*>
            ?: return badRequest("playlist_track_ids required")
        val ids = rawIds.mapNotNull { (it as? Number)?.toLong() }
        return ownedAction(id, user) { _ ->
            PlaylistService.reorder(id, user, ids)
            jsonResponse(gson.toJson(mapOf("ok" to true)))
        }
    }

    @Post("/api/v2/playlists/{id}/hero")
    fun setHero(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val body = readBody(ctx)
        // null/missing track_id clears the hero override.
        val trackId = (body["track_id"] as? Number)?.toLong()
        return ownedAction(id, user) { _ ->
            PlaylistService.setHero(id, user, trackId)
            jsonResponse(gson.toJson(mapOf("ok" to true)))
        }
    }

    /**
     * Fork a playlist into one owned by the current user. Any user may
     * call this on any playlist — that's the whole point of the
     * affordance ("use this as a starting point").
     */
    @Post("/api/v2/playlists/{id}/duplicate")
    fun duplicate(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val body: Map<*, *> = if (ctx.request().headers().contentLength() > 0L) readBody(ctx) else emptyMap<Any?, Any?>()
        val newName = (body["name"] as? String)?.takeIf { it.isNotBlank() }

        return try {
            val fork = PlaylistService.duplicate(id, user, newName)
            jsonResponse(gson.toJson(mapOf("id" to fork.id, "name" to fork.name)))
        } catch (_: PlaylistService.PlaylistNotFound) {
            notFound()
        }
    }

    @Post("/api/v2/playlists/{id}/privacy")
    fun setPrivacy(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val body = readBody(ctx)
        val isPrivate = body["is_private"] as? Boolean
            ?: return badRequest("is_private (boolean) required")
        return ownedAction(id, user) { _ ->
            PlaylistService.setPrivacy(id, user, isPrivate)
            jsonResponse(gson.toJson(mapOf("ok" to true, "is_private" to isPrivate)))
        }
    }

    /**
     * Upsert the per-user resume cursor for this playlist. The audio
     * player calls this on a rate-limited cadence while a playlist is
     * the active queue source. Silent on private playlists owned by
     * someone else (the service treats it as a no-op).
     */
    @Post("/api/v2/playlists/{id}/progress")
    fun reportProgress(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val body = readBody(ctx)
        val ptId = (body["playlist_track_id"] as? Number)?.toLong()
            ?: return badRequest("playlist_track_id required")
        val pos = (body["position_seconds"] as? Number)?.toInt() ?: 0
        PlaylistService.reportProgress(user.id!!, id, ptId, pos)
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    @Delete("/api/v2/playlists/{id}/progress")
    fun clearProgress(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        PlaylistService.clearResume(user.id!!, id)
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    /**
     * "I finished a track" event. Bumps the per-user, per-track play
     * count which drives Most Played. Decoupled from the broader
     * /api/v2/audio/progress endpoint so we don't write a row on every
     * 10-second tick.
     */
    @Post("/api/v2/playlists/track-completed")
    fun trackCompleted(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return unauthorized()
        val body = readBody(ctx)
        val trackId = (body["track_id"] as? Number)?.toLong()
            ?: return badRequest("track_id required")
        PlaylistService.recordTrackCompletion(user.id!!, trackId)
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    // ----------------------------- helpers ---------------------------

    private fun ownedAction(
        playlistId: Long,
        user: AppUser,
        block: (Playlist) -> HttpResponse
    ): HttpResponse {
        val pl = Playlist.findById(playlistId) ?: return notFound()
        if (pl.owner_user_id != user.id) return forbidden()
        return try {
            block(pl)
        } catch (_: PlaylistService.PlaylistAccessDenied) {
            forbidden()
        } catch (_: PlaylistService.PlaylistNotFound) {
            notFound()
        }
    }

    private fun Playlist.toSummary(currentUserId: Long, fallbackPosterUrl: String? = null): Map<String, Any?> {
        val ownerUsername = AppUser.findById(this.owner_user_id)?.username ?: "?"
        // Resolve hero poster eagerly so the browse grid doesn't have to
        // do a follow-up call per row. Match the detail endpoint's fallback
        // (first track's title poster) so card hero == detail hero — see
        // Playlist.kt's class-doc contract.
        val heroPosterUrl = this.hero_track_id
            ?.let { Track.findById(it) }
            ?.let { Title.findById(it.title_id) }
            ?.posterUrl(PosterSize.THUMBNAIL)
            ?: fallbackPosterUrl
        return mapOf(
            "id" to this.id,
            "name" to this.name,
            "description" to this.description,
            "owner_user_id" to this.owner_user_id,
            "owner_username" to ownerUsername,
            "is_owner" to (this.owner_user_id == currentUserId),
            "is_private" to this.is_private,
            "hero_poster_url" to heroPosterUrl,
            "updated_at" to this.updated_at?.toString()
        )
    }

    /**
     * Build a `playlist.id -> first-track-title-poster-url` map for every
     * playlist that has no explicit hero_track_id. One PlaylistTrack scan
     * + one Track scan + one Title scan, vs. N+1 if [toSummary] did it
     * inline.
     */
    private fun computeFallbackPosters(playlists: List<Playlist>): Map<Long, String?> {
        val needsFallback = playlists.filter { it.hero_track_id == null && it.id != null }
        if (needsFallback.isEmpty()) return emptyMap()
        val pids = needsFallback.mapNotNull { it.id }.toSet()
        val firstTrackByPlaylist = PlaylistTrack.findAll()
            .filter { it.playlist_id in pids }
            .groupBy { it.playlist_id }
            .mapValues { (_, rows) -> rows.minByOrNull { it.position }?.track_id }
        val trackIds = firstTrackByPlaylist.values.filterNotNull().toSet()
        val titleIdByTrack = if (trackIds.isEmpty()) emptyMap()
            else Track.findAll().filter { it.id in trackIds }.associate { it.id!! to it.title_id }
        return needsFallback.associate { pl ->
            val trackId = firstTrackByPlaylist[pl.id]
            val titleId = trackId?.let { titleIdByTrack[it] }
            pl.id!! to titleId?.let { Title.findById(it)?.posterUrl(PosterSize.THUMBNAIL) }
        }
    }

    private fun PlaylistService.SmartPlaylistView.toSummary(): Map<String, Any?> {
        val heroPoster = this.heroTitleId?.let { Title.findById(it)?.posterUrl(PosterSize.THUMBNAIL) }
        return mapOf(
            "key" to this.key,
            "name" to this.name,
            "description" to this.description,
            "is_smart" to true,
            "track_count" to this.tracks.size,
            "hero_poster_url" to heroPoster
        )
    }

    private fun Track.toJson(title: Title?): Map<String, Any?> = mapOf(
        "track_id" to this.id,
        "track_name" to this.name,
        "title_id" to this.title_id,
        "title_name" to title?.name,
        "poster_url" to title?.posterUrl(PosterSize.THUMBNAIL),
        "duration_seconds" to this.duration_seconds,
        "playable" to !this.file_path.isNullOrBlank()
    )

    private fun readBody(ctx: ServiceRequestContext): Map<*, *> {
        val body = ctx.request().aggregate().join().contentUtf8()
        if (body.isBlank()) return emptyMap<Any?, Any?>()
        return gson.fromJson(body, Map::class.java) ?: emptyMap<Any?, Any?>()
    }

    private fun jsonResponse(json: String, status: HttpStatus = HttpStatus.OK): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(status)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun unauthorized(): HttpResponse = HttpResponse.of(HttpStatus.UNAUTHORIZED)
    private fun forbidden(): HttpResponse =
        jsonResponse(gson.toJson(mapOf("error" to "not your playlist")), HttpStatus.FORBIDDEN)
    private fun notFound(): HttpResponse =
        jsonResponse(gson.toJson(mapOf("error" to "not found")), HttpStatus.NOT_FOUND)
    private fun badRequest(msg: String): HttpResponse =
        jsonResponse(gson.toJson(mapOf("error" to msg)), HttpStatus.BAD_REQUEST)
}
