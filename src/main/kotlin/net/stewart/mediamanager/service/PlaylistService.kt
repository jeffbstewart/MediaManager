package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Playlist
import net.stewart.mediamanager.entity.PlaylistProgress
import net.stewart.mediamanager.entity.PlaylistTrack
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackPlayCount

/**
 * CRUD + reorder + duplicate for user-curated [Playlist]s. Owner-only
 * mutations (the auth check lives at the call site — every method that
 * mutates an existing playlist takes the acting user and throws
 * [PlaylistAccessDenied] when they don't own it). The [duplicate] call
 * is the one exception: any user may fork any playlist into one of
 * their own.
 *
 * Playback / hero / shuffle are pure reads and don't enforce ownership.
 *
 * See docs/MUSIC.md (Playlists) for the slice-1 scope and V086 for the
 * schema. The unique key on (playlist_id, position) means reorders must
 * either run inside a transaction or write through a temporary
 * negative-position pass; we use the latter to stay compatible with
 * VoK's per-call session.
 */
object PlaylistService {

    class PlaylistNotFound(id: Long) : RuntimeException("playlist $id not found")
    class PlaylistAccessDenied(id: Long, userId: Long) :
        RuntimeException("user $userId does not own playlist $id")
    class TrackNotFound(id: Long) : RuntimeException("track $id not found")

    /**
     * One playlist plus its ordered tracks, hydrated for an API
     * response. The track list is empty for the lightweight list
     * endpoint; the detail endpoint fills it in.
     */
    data class PlaylistView(
        val playlist: Playlist,
        val ownerUsername: String,
        val tracks: List<TrackOnPlaylist>,
        val heroTitleId: Long?,
        val totalDurationSeconds: Int
    )

    data class TrackOnPlaylist(
        val playlistTrackId: Long,
        val position: Int,
        val track: Track
    )

    // ----------------------------- read ------------------------------

    /**
     * All playlists visible to [viewerUserId], newest-first by updated_at.
     * Private playlists are hidden from non-owners. Used for the
     * "browse" landing page.
     */
    fun listVisibleTo(viewerUserId: Long): List<Playlist> =
        Playlist.findAll()
            .filter { !it.is_private || it.owner_user_id == viewerUserId }
            .sortedByDescending { it.updated_at }

    /** Playlists this user owns, newest-first by updated_at. */
    fun listOwnedBy(userId: Long): List<Playlist> =
        Playlist.findAll()
            .filter { it.owner_user_id == userId }
            .sortedByDescending { it.updated_at }

    /**
     * Detail view: playlist row, hydrated tracks in position order, hero
     * title resolution. When [viewerUserId] is non-null and the playlist
     * is private, throws [PlaylistAccessDenied] for non-owners — same
     * status code as a mutate attempt, intentionally indistinguishable
     * from "doesn't exist for you".
     */
    fun getDetail(
        playlistId: Long,
        viewerUserId: Long? = null,
        clock: Clock = SystemClock
    ): PlaylistView {
        val playlist = Playlist.findById(playlistId) ?: throw PlaylistNotFound(playlistId)
        if (playlist.is_private && viewerUserId != null && playlist.owner_user_id != viewerUserId) {
            throw PlaylistAccessDenied(playlistId, viewerUserId)
        }
        val entries = PlaylistTrack.findAll()
            .filter { it.playlist_id == playlistId }
            .sortedBy { it.position }
        val trackIds = entries.map { it.track_id }.toSet()
        val tracksById = if (trackIds.isEmpty()) emptyMap()
            else Track.findAll().filter { it.id in trackIds }.associateBy { it.id }

        val hydrated = entries.mapNotNull { entry ->
            val track = tracksById[entry.track_id] ?: return@mapNotNull null
            TrackOnPlaylist(
                playlistTrackId = entry.id!!,
                position = entry.position,
                track = track
            )
        }

        val heroTitleId = playlist.hero_track_id?.let { tracksById[it]?.title_id }
            ?: hydrated.firstOrNull()?.track?.title_id

        val total = hydrated.sumOf { it.track.duration_seconds ?: 0 }

        // touch clock to keep the parameter live for tests that monkey
        // with time around playlist reads — currently unused otherwise.
        @Suppress("UNUSED_VARIABLE") val _now = clock.now()

        val ownerUsername = AppUser.findById(playlist.owner_user_id)?.username ?: "?"
        return PlaylistView(playlist, ownerUsername, hydrated, heroTitleId, total)
    }

    // ----------------------------- create ----------------------------

    fun create(
        owner: AppUser,
        name: String,
        description: String?,
        clock: Clock = SystemClock
    ): Playlist {
        val now = clock.now()
        val pl = Playlist(
            name = name.trim().ifBlank { "Untitled" },
            description = description?.trim()?.ifBlank { null },
            owner_user_id = owner.id!!,
            created_at = now,
            updated_at = now
        )
        pl.save()
        return pl
    }

    // ----------------------------- mutate ----------------------------

    fun rename(
        playlistId: Long,
        actor: AppUser,
        newName: String,
        newDescription: String?,
        clock: Clock = SystemClock
    ): Playlist {
        val pl = requireOwner(playlistId, actor)
        pl.name = newName.trim().ifBlank { "Untitled" }
        pl.description = newDescription?.trim()?.ifBlank { null }
        pl.updated_at = clock.now()
        pl.save()
        return pl
    }

    fun delete(playlistId: Long, actor: AppUser) {
        requireOwner(playlistId, actor).delete()
    }

    /**
     * Owner-only privacy toggle. Private playlists are hidden from
     * [listVisibleTo] for non-owners and reject [getDetail] reads with
     * [PlaylistAccessDenied].
     */
    fun setPrivacy(
        playlistId: Long,
        actor: AppUser,
        isPrivate: Boolean,
        clock: Clock = SystemClock
    ): Playlist {
        val pl = requireOwner(playlistId, actor)
        if (pl.is_private != isPrivate) {
            pl.is_private = isPrivate
            pl.updated_at = clock.now()
            pl.save()
        }
        return pl
    }

    /**
     * Append the given tracks (in order) to the end of the playlist.
     * Skips any track ids that don't exist in the catalog. Duplicates
     * are permitted — the same track id may be appended any number of
     * times.
     */
    fun addTracks(
        playlistId: Long,
        actor: AppUser,
        trackIds: List<Long>,
        clock: Clock = SystemClock
    ): List<PlaylistTrack> {
        val pl = requireOwner(playlistId, actor)
        if (trackIds.isEmpty()) return emptyList()

        val validIds = Track.findAll().mapNotNull { it.id }.toSet()
        val keep = trackIds.filter { it in validIds }
        if (keep.isEmpty()) return emptyList()

        val existing = PlaylistTrack.findAll().filter { it.playlist_id == playlistId }
        var nextPos = (existing.maxOfOrNull { it.position } ?: -1) + 1
        val now = clock.now()

        val added = keep.map { trackId ->
            val pt = PlaylistTrack(
                playlist_id = playlistId,
                track_id = trackId,
                position = nextPos++,
                created_at = now
            )
            pt.save()
            pt
        }
        pl.updated_at = now
        pl.save()
        return added
    }

    /**
     * Remove one entry. The caller passes the [PlaylistTrack.id], not the
     * underlying [Track.id] — the same track may legitimately appear at
     * multiple positions and we mustn't yank all of them. After removal
     * we compact positions so the list stays dense (0..n-1).
     */
    fun removeTrack(
        playlistId: Long,
        actor: AppUser,
        playlistTrackId: Long,
        clock: Clock = SystemClock
    ) {
        val pl = requireOwner(playlistId, actor)
        val entry = PlaylistTrack.findById(playlistTrackId) ?: return
        if (entry.playlist_id != playlistId) return
        entry.delete()
        compactPositions(playlistId)
        pl.updated_at = clock.now()
        pl.save()
    }

    /**
     * Replace the playlist's order with the given playlist_track ids.
     * Any ids not in the input are dropped from the playlist (so this
     * doubles as a "remove a batch" operation if the caller leaves them
     * out). Unknown ids are silently ignored.
     *
     * Implementation note: the (playlist_id, position) UNIQUE index
     * means we can't move two rows past each other in a single UPDATE
     * without a constraint hit. We do a two-pass write — first push
     * everything to negative positions, then assign final positions —
     * to stay compatible with VoK's per-call session.
     */
    fun reorder(
        playlistId: Long,
        actor: AppUser,
        playlistTrackIdsInOrder: List<Long>,
        clock: Clock = SystemClock
    ) {
        val pl = requireOwner(playlistId, actor)
        val current = PlaylistTrack.findAll()
            .filter { it.playlist_id == playlistId }
            .associateBy { it.id!! }
        if (current.isEmpty()) return

        val ordered = playlistTrackIdsInOrder.mapNotNull { current[it] }
        val orderedIds = ordered.map { it.id!! }.toSet()

        // Drop entries that the caller omitted — interpret as removal.
        for ((id, entry) in current) {
            if (id !in orderedIds) entry.delete()
        }

        // Pass 1 — park surviving rows at -1, -2, -3... so the unique
        // index has no chance of colliding while we shuffle them.
        ordered.forEachIndexed { i, entry ->
            entry.position = -(i + 1)
            entry.save()
        }
        // Pass 2 — assign final dense positions.
        ordered.forEachIndexed { i, entry ->
            entry.position = i
            entry.save()
        }

        pl.updated_at = clock.now()
        pl.save()
    }

    /**
     * Pin one of the playlist's tracks as the hero. [trackId] null
     * clears the override. The track must already be in the playlist
     * (we don't auto-add). Silently no-ops if the track isn't on the
     * playlist.
     */
    fun setHero(
        playlistId: Long,
        actor: AppUser,
        trackId: Long?,
        clock: Clock = SystemClock
    ): Playlist {
        val pl = requireOwner(playlistId, actor)
        if (trackId == null) {
            pl.hero_track_id = null
        } else {
            val onList = PlaylistTrack.findAll()
                .any { it.playlist_id == playlistId && it.track_id == trackId }
            if (onList) pl.hero_track_id = trackId
        }
        pl.updated_at = clock.now()
        pl.save()
        return pl
    }

    // ---------------------------- duplicate --------------------------

    /**
     * Fork [sourceId] into a new playlist owned by [actor]. The actor
     * does **not** need to own the source — duplicate is the explicit
     * "use this as a starting point" affordance. Copies name (with
     * " (copy)" suffix unless [newName] overrides), description,
     * hero_track_id, and the full track list with positions intact.
     */
    fun duplicate(
        sourceId: Long,
        actor: AppUser,
        newName: String? = null,
        clock: Clock = SystemClock
    ): Playlist {
        val source = Playlist.findById(sourceId) ?: throw PlaylistNotFound(sourceId)
        val now = clock.now()

        val copy = Playlist(
            name = newName?.trim()?.ifBlank { null } ?: "${source.name} (copy)",
            description = source.description,
            owner_user_id = actor.id!!,
            // Hero only copies if the chosen track is also being copied —
            // it is, since we copy the whole list.
            hero_track_id = source.hero_track_id,
            created_at = now,
            updated_at = now
        )
        copy.save()

        val sourceEntries = PlaylistTrack.findAll()
            .filter { it.playlist_id == sourceId }
            .sortedBy { it.position }
        for (entry in sourceEntries) {
            PlaylistTrack(
                playlist_id = copy.id!!,
                track_id = entry.track_id,
                position = entry.position,
                created_at = now
            ).save()
        }
        return copy
    }

    // ----------------------------- shuffle ---------------------------

    /**
     * Build an ephemeral shuffle of all playable tracks in the catalog
     * the user is allowed to see. Returns a fresh ordering each call —
     * does **not** persist anything. The caller queues these into the
     * audio player; an explicit "Save as playlist" then calls [create]
     * + [addTracks] to make it durable.
     *
     * [limit] caps the result; pass null for "everything we have" (the
     * client may cap on its end for very large libraries).
     */
    fun libraryShuffle(user: AppUser, limit: Int? = 200): List<Track> {
        val titlesById = Title.findAll().associateBy { it.id }
        val playable = Track.findAll().filter { t ->
            !t.file_path.isNullOrBlank() &&
                titlesById[t.title_id]?.let { title ->
                    !title.hidden && user.canSeeRating(title.content_rating)
                } == true
        }
        val shuffled = playable.shuffled()
        return if (limit != null && limit > 0) shuffled.take(limit) else shuffled
    }

    // ------------------------- resume cursor -------------------------

    /** Per-user resume info for the playlist. Null when never started. */
    data class ResumeInfo(
        val playlistTrackId: Long,
        val trackId: Long,
        val positionSeconds: Int,
        val updatedAt: java.time.LocalDateTime?
    )

    /** Latest resume cursor for [userId] on [playlistId], or null if absent. */
    fun getResume(userId: Long, playlistId: Long): ResumeInfo? {
        val row = PlaylistProgress.findAll()
            .firstOrNull { it.user_id == userId && it.playlist_id == playlistId } ?: return null
        // Find the underlying playlist_track row to surface its track id.
        val pt = PlaylistTrack.findById(row.playlist_track_id) ?: return null
        return ResumeInfo(
            playlistTrackId = row.playlist_track_id,
            trackId = pt.track_id,
            positionSeconds = row.position_seconds,
            updatedAt = row.updated_at
        )
    }

    /**
     * Upsert the user's resume cursor on a playlist. Silently no-ops
     * when the playlist or playlist_track doesn't exist (e.g. the
     * client raced a delete) or when the playlist is private and the
     * user isn't the owner.
     */
    fun reportProgress(
        userId: Long,
        playlistId: Long,
        playlistTrackId: Long,
        positionSeconds: Int,
        clock: Clock = SystemClock
    ) {
        val pl = Playlist.findById(playlistId) ?: return
        if (pl.is_private && pl.owner_user_id != userId) return
        val pt = PlaylistTrack.findById(playlistTrackId) ?: return
        if (pt.playlist_id != playlistId) return

        val now = clock.now()
        val pos = positionSeconds.coerceAtLeast(0)
        val existing = PlaylistProgress.findAll()
            .firstOrNull { it.user_id == userId && it.playlist_id == playlistId }
        if (existing != null) {
            existing.playlist_track_id = playlistTrackId
            existing.position_seconds = pos
            existing.updated_at = now
            existing.save()
        } else {
            PlaylistProgress(
                user_id = userId,
                playlist_id = playlistId,
                playlist_track_id = playlistTrackId,
                position_seconds = pos,
                updated_at = now
            ).save()
        }
    }

    /** Drop the resume cursor for the user on a playlist. */
    fun clearResume(userId: Long, playlistId: Long) {
        PlaylistProgress.findAll()
            .firstOrNull { it.user_id == userId && it.playlist_id == playlistId }
            ?.delete()
    }

    // ------------------------- play counts ---------------------------

    /**
     * Bump the per-user play count for [trackId] by one. Called from
     * the audio player when a track plays through to (near) completion.
     * Drives the "Most Played" smart playlist.
     */
    fun recordTrackCompletion(userId: Long, trackId: Long, clock: Clock = SystemClock) {
        if (Track.findById(trackId) == null) return
        val now = clock.now()
        val existing = TrackPlayCount.findAll()
            .firstOrNull { it.user_id == userId && it.track_id == trackId }
        if (existing != null) {
            existing.play_count += 1
            existing.last_played = now
            existing.save()
        } else {
            TrackPlayCount(
                user_id = userId,
                track_id = trackId,
                play_count = 1,
                last_played = now
            ).save()
        }
    }

    // ------------------------- smart playlists -----------------------

    /**
     * A read-only computed playlist (Recently Added, Most Played).
     * Has a stable string id so the client can deep-link, but no
     * underlying [Playlist] row — mutations are blocked at the API
     * layer. Hero is the first track's title poster.
     */
    data class SmartPlaylistView(
        val key: String,
        val name: String,
        val description: String,
        val tracks: List<TrackOnPlaylist>,
        val totalDurationSeconds: Int,
        val heroTitleId: Long?
    )

    /** All smart playlist keys the server knows about. Keep in sync with [getSmartPlaylist]. */
    val SMART_PLAYLIST_KEYS: List<String> = listOf("recently-added", "most-played")

    /** Smart-playlist summaries for the landing page. Empty when the user has no music. */
    fun listSmartPlaylists(user: AppUser): List<SmartPlaylistView> =
        SMART_PLAYLIST_KEYS.mapNotNull { getSmartPlaylist(it, user) }
            .filter { it.tracks.isNotEmpty() }

    /**
     * Compute the smart playlist with the given key. Returns null on
     * an unknown key — keep callers tolerant of clients hitting stale
     * deep links after a key is renamed.
     */
    fun getSmartPlaylist(key: String, user: AppUser): SmartPlaylistView? {
        return when (key) {
            "recently-added" -> recentlyAdded(user)
            "most-played" -> mostPlayed(user)
            else -> null
        }
    }

    private fun recentlyAdded(user: AppUser, limit: Int = 50): SmartPlaylistView {
        val titlesById = Title.findAll().associateBy { it.id }
        val tracks = Track.findAll()
            .asSequence()
            .filter { !it.file_path.isNullOrBlank() }
            .filter { t ->
                titlesById[t.title_id]?.let { title ->
                    !title.hidden && user.canSeeRating(title.content_rating)
                } == true
            }
            .sortedByDescending { it.created_at }
            .take(limit)
            .toList()

        val entries = tracks.mapIndexed { i, t ->
            // playlist_track_id has no real value here — smart playlists
            // are read-only — but the API shape needs *some* stable
            // handle. Use a synthetic negative id derived from the index
            // so it's obviously not a real PlaylistTrack id.
            TrackOnPlaylist(
                playlistTrackId = -(i + 1L),
                position = i,
                track = t
            )
        }
        return SmartPlaylistView(
            key = "recently-added",
            name = "Recently Added",
            description = "The newest tracks in your library, freshest first.",
            tracks = entries,
            totalDurationSeconds = entries.sumOf { it.track.duration_seconds ?: 0 },
            heroTitleId = entries.firstOrNull()?.track?.title_id
        )
    }

    private fun mostPlayed(user: AppUser, limit: Int = 50): SmartPlaylistView {
        val titlesById = Title.findAll().associateBy { it.id }
        val tracksById = Track.findAll().associateBy { it.id }
        val counts = TrackPlayCount.findAll()
            .filter { it.user_id == user.id && it.play_count > 0 }
            .sortedByDescending { it.play_count }

        val entries = mutableListOf<TrackOnPlaylist>()
        for (row in counts) {
            if (entries.size >= limit) break
            val t = tracksById[row.track_id] ?: continue
            if (t.file_path.isNullOrBlank()) continue
            val title = titlesById[t.title_id] ?: continue
            if (title.hidden || !user.canSeeRating(title.content_rating)) continue
            entries += TrackOnPlaylist(
                playlistTrackId = -(entries.size + 1L),
                position = entries.size,
                track = t
            )
        }
        return SmartPlaylistView(
            key = "most-played",
            name = "Most Played",
            description = "Tracks you come back to the most.",
            tracks = entries,
            totalDurationSeconds = entries.sumOf { it.track.duration_seconds ?: 0 },
            heroTitleId = entries.firstOrNull()?.track?.title_id
        )
    }

    // ----------------------------- helpers ---------------------------

    private fun requireOwner(playlistId: Long, actor: AppUser): Playlist {
        val pl = Playlist.findById(playlistId) ?: throw PlaylistNotFound(playlistId)
        if (pl.owner_user_id != actor.id) throw PlaylistAccessDenied(playlistId, actor.id ?: 0)
        return pl
    }

    private fun compactPositions(playlistId: Long) {
        val rows = PlaylistTrack.findAll()
            .filter { it.playlist_id == playlistId }
            .sortedBy { it.position }
        // Same two-pass dance as reorder() — only run if anything
        // is actually out of order, to keep the steady-state cheap.
        val needsCompact = rows.withIndex().any { (i, row) -> row.position != i }
        if (!needsCompact) return
        rows.forEachIndexed { i, row ->
            row.position = -(i + 1)
            row.save()
        }
        rows.forEachIndexed { i, row ->
            row.position = i
            row.save()
        }
    }
}
