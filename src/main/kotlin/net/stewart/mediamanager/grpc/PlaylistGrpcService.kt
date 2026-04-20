package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Playlist as PlaylistEntity
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track as TrackEntity
import net.stewart.mediamanager.service.PlaylistService

/**
 * gRPC parity for the Angular [PlaylistHttpService]. iOS uses these
 * RPCs to drive the slice-1 playlists feature. Owner-only mutations
 * are enforced inside [PlaylistService]; we map the
 * [PlaylistService.PlaylistAccessDenied] and [PlaylistService.PlaylistNotFound]
 * exceptions to PERMISSION_DENIED / NOT_FOUND status.
 */
class PlaylistGrpcService : PlaylistServiceGrpcKt.PlaylistServiceCoroutineImplBase() {

    override suspend fun listPlaylists(request: ListPlaylistsRequest): ListPlaylistsResponse {
        val user = currentUser()
        val rows = when (request.scope) {
            PlaylistScope.PLAYLIST_SCOPE_MINE -> PlaylistService.listOwnedBy(user.id!!)
            else -> PlaylistService.listVisibleTo(user.id!!)
        }
        return listPlaylistsResponse {
            playlists.addAll(rows.map { it.toSummary(user) })
        }
    }

    override suspend fun getPlaylist(request: GetPlaylistRequest): PlaylistDetail {
        val user = currentUser()
        val view = try {
            PlaylistService.getDetail(request.id, viewerUserId = user.id)
        } catch (_: PlaylistService.PlaylistNotFound) {
            throw StatusException(Status.NOT_FOUND.withDescription("playlist ${request.id} not found"))
        } catch (_: PlaylistService.PlaylistAccessDenied) {
            // Don't leak existence of private playlists.
            throw StatusException(Status.NOT_FOUND.withDescription("playlist ${request.id} not found"))
        }
        val titlesById = Title.findAll().associateBy { it.id }
        val resumeInfo = PlaylistService.getResume(user.id!!, request.id)
        return playlistDetail {
            summary = view.playlist.toSummary(user)
            totalDurationSeconds = view.totalDurationSeconds
            tracks.addAll(view.tracks.map { entry ->
                val parent = titlesById[entry.track.title_id]
                playlistTrackEntry {
                    playlistTrackId = entry.playlistTrackId
                    position = entry.position
                    track = entry.track.toProto()
                    titleId = entry.track.title_id
                    titleName = parent?.name ?: ""
                }
            })
            resumeInfo?.let { r ->
                resume = playlistResume {
                    playlistTrackId = r.playlistTrackId
                    trackId = r.trackId
                    positionSeconds = r.positionSeconds
                    r.updatedAt?.let { updatedAt = it.toProtoTimestamp() }
                }
            }
        }
    }

    override suspend fun createPlaylist(request: CreatePlaylistRequest): PlaylistSummary {
        val user = currentUser()
        if (request.name.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("name required"))
        }
        val pl = PlaylistService.create(
            user,
            request.name,
            if (request.hasDescription()) request.description else null
        )
        return pl.toSummary(user)
    }

    override suspend fun renamePlaylist(request: RenamePlaylistRequest): Empty {
        val user = currentUser()
        if (request.name.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("name required"))
        }
        ownedAction {
            PlaylistService.rename(
                request.id, user, request.name,
                if (request.hasDescription()) request.description else null
            )
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun deletePlaylist(request: DeletePlaylistRequest): Empty {
        val user = currentUser()
        ownedAction { PlaylistService.delete(request.id, user) }
        return Empty.getDefaultInstance()
    }

    override suspend fun addTracksToPlaylist(
        request: AddTracksToPlaylistRequest
    ): AddTracksToPlaylistResponse {
        val user = currentUser()
        val added = ownedAction {
            PlaylistService.addTracks(request.id, user, request.trackIdsList)
        }
        return addTracksToPlaylistResponse {
            this.added = added.size
            playlistTrackIds.addAll(added.mapNotNull { it.id })
        }
    }

    override suspend fun removeTrackFromPlaylist(request: RemoveTrackFromPlaylistRequest): Empty {
        val user = currentUser()
        ownedAction {
            PlaylistService.removeTrack(request.id, user, request.playlistTrackId)
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun reorderPlaylist(request: ReorderPlaylistRequest): Empty {
        val user = currentUser()
        ownedAction {
            PlaylistService.reorder(request.id, user, request.playlistTrackIdsInOrderList)
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun setPlaylistHero(request: SetPlaylistHeroRequest): Empty {
        val user = currentUser()
        // track_id == 0 (or absent) clears the override.
        val trackId = if (request.hasTrackId() && request.trackId > 0) request.trackId else null
        ownedAction { PlaylistService.setHero(request.id, user, trackId) }
        return Empty.getDefaultInstance()
    }

    override suspend fun duplicatePlaylist(request: DuplicatePlaylistRequest): PlaylistSummary {
        val user = currentUser()
        val newName = if (request.hasNewName() && request.newName.isNotBlank()) request.newName else null
        val fork = try {
            PlaylistService.duplicate(request.sourceId, user, newName)
        } catch (_: PlaylistService.PlaylistNotFound) {
            throw StatusException(Status.NOT_FOUND.withDescription("source playlist ${request.sourceId} not found"))
        }
        return fork.toSummary(user)
    }

    override suspend fun libraryShuffle(request: LibraryShuffleRequest): LibraryShuffleResponse {
        val user = currentUser()
        val limit = if (request.hasLimit() && request.limit > 0) request.limit else 200
        val tracks = PlaylistService.libraryShuffle(user, limit)
        return libraryShuffleResponse {
            this.tracks.addAll(tracks.map { it.toProto() })
        }
    }

    // ----------------------------- helpers ---------------------------

    /**
     * Wrap a service-layer mutation that requires playlist ownership,
     * mapping the typed exceptions to gRPC status codes. Any other
     * exception bubbles up unchanged.
     */
    private inline fun <T> ownedAction(block: () -> T): T = try {
        block()
    } catch (_: PlaylistService.PlaylistAccessDenied) {
        throw StatusException(Status.PERMISSION_DENIED.withDescription("not your playlist"))
    } catch (_: PlaylistService.PlaylistNotFound) {
        throw StatusException(Status.NOT_FOUND.withDescription("playlist not found"))
    }

    override suspend fun setPlaylistPrivacy(request: SetPlaylistPrivacyRequest): Empty {
        val user = currentUser()
        ownedAction { PlaylistService.setPrivacy(request.id, user, request.isPrivate) }
        return Empty.getDefaultInstance()
    }

    override suspend fun reportPlaylistProgress(request: ReportPlaylistProgressRequest): Empty {
        val user = currentUser()
        PlaylistService.reportProgress(
            user.id!!, request.id, request.playlistTrackId, request.positionSeconds
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun clearPlaylistProgress(request: ClearPlaylistProgressRequest): Empty {
        val user = currentUser()
        PlaylistService.clearResume(user.id!!, request.id)
        return Empty.getDefaultInstance()
    }

    override suspend fun recordTrackCompletion(request: RecordTrackCompletionRequest): Empty {
        val user = currentUser()
        PlaylistService.recordTrackCompletion(user.id!!, request.trackId)
        return Empty.getDefaultInstance()
    }

    override suspend fun listSmartPlaylists(request: ListSmartPlaylistsRequest): ListSmartPlaylistsResponse {
        val user = currentUser()
        return listSmartPlaylistsResponse {
            playlists.addAll(PlaylistService.listSmartPlaylists(user).map { it.toSummary() })
        }
    }

    override suspend fun getSmartPlaylist(request: GetSmartPlaylistRequest): SmartPlaylistDetail {
        val user = currentUser()
        val view = PlaylistService.getSmartPlaylist(request.key, user)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("smart playlist '${request.key}' not found"))
        val titlesById = Title.findAll().associateBy { it.id }
        return smartPlaylistDetail {
            summary = view.toSummary()
            totalDurationSeconds = view.totalDurationSeconds
            tracks.addAll(view.tracks.map { entry ->
                val parent = titlesById[entry.track.title_id]
                playlistTrackEntry {
                    playlistTrackId = entry.playlistTrackId
                    position = entry.position
                    track = entry.track.toProto()
                    titleId = entry.track.title_id
                    titleName = parent?.name ?: ""
                }
            })
        }
    }

    private fun PlaylistService.SmartPlaylistView.toSummary(): SmartPlaylistSummary {
        val view = this
        return smartPlaylistSummary {
            key = view.key
            name = view.name
            description = view.description
            trackCount = view.tracks.size
            view.heroTitleId?.let { heroTitleId = it }
        }
    }

    private fun PlaylistEntity.toSummary(actor: AppUser): PlaylistSummary {
        val pl = this
        // Resolve hero to the parent title id so iOS can hit the existing
        // ImageService with IMAGE_TYPE_POSTER_FULL without an extra round
        // trip per row.
        val heroTitleId: Long? = pl.hero_track_id?.let { tid ->
            TrackEntity.findById(tid)?.title_id
        }
        val ownerName = AppUser.findById(pl.owner_user_id)?.username ?: ""
        return playlistSummary {
            id = pl.id ?: 0
            name = pl.name
            pl.description?.takeIf { it.isNotBlank() }?.let { description = it }
            ownerUserId = pl.owner_user_id
            ownerUsername = ownerName
            isOwner = pl.owner_user_id == actor.id
            isPrivate = pl.is_private
            heroTitleId?.let { this.heroTitleId = it }
            pl.updated_at?.let { updatedAt = it.toProtoTimestamp() }
        }
    }
}
