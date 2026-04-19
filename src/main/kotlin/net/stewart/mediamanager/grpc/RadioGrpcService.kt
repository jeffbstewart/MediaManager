package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.service.RadioSeedStore
import net.stewart.mediamanager.service.RadioService

/**
 * gRPC surface for M7 "Start Radio". Mirrors the HTTP service in
 * [net.stewart.mediamanager.armeria.RadioHttpService]. Session state is
 * 4-hour-TTL in-memory only (see [RadioSeedStore]); callers replay the
 * opaque radio_session_id on each /next call.
 */
class RadioGrpcService : RadioServiceGrpcKt.RadioServiceCoroutineImplBase() {

    companion object {
        private const val EARLY_SKIP_SECONDS = 30.0
    }

    override suspend fun startRadio(request: StartRadioRequest): StartRadioResponse {
        currentUser()
        val batch = when {
            request.hasSeedAlbumId() && request.seedAlbumId > 0 ->
                RadioService.startFromAlbum(request.seedAlbumId)
            request.hasSeedTrackId() && request.seedTrackId > 0 ->
                RadioService.startFromTrack(request.seedTrackId)
            else -> throw StatusException(
                Status.INVALID_ARGUMENT.withDescription("seed_track_id or seed_album_id required")
            )
        } ?: throw StatusException(Status.NOT_FOUND.withDescription("seed not found"))

        val sessionId = RadioSeedStore.put(batch.seed)
        return startRadioResponse {
            radioSessionId = sessionId
            seed = batch.seed.toProto()
            initialBatch.addAll(batch.tracks.map { it.toProtoTrack() })
        }
    }

    override suspend fun nextRadioBatch(request: NextRadioBatchRequest): NextRadioBatchResponse {
        currentUser()
        val seed = RadioSeedStore.get(request.radioSessionId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("radio session expired"))

        val history = request.historyList.map { h ->
            // "Early" skip = user bailed within 30 seconds. Full-completion
            // plays leave skipped_at absent, so those are not penalized.
            val skippedEarly = h.hasSkippedAt() && h.skippedAt.seconds < EARLY_SKIP_SECONDS
            RadioService.HistoryEntry(trackId = h.trackId, skippedEarly = skippedEarly)
        }

        val batch = RadioService.nextBatch(seed, history)
        return nextRadioBatchResponse {
            tracks.addAll(batch.tracks.map { it.toProtoTrack() })
        }
    }

    override suspend fun stopRadio(request: StopRadioRequest): Empty {
        currentUser()
        RadioSeedStore.remove(request.radioSessionId)
        return Empty.getDefaultInstance()
    }

    private fun RadioService.RadioSeed.toProto(): RadioSeed {
        val entity = this
        return radioSeed {
            this.seedType = when (entity.seedType) {
                "album" -> RadioSeedType.RADIO_SEED_TYPE_ALBUM
                "track" -> RadioSeedType.RADIO_SEED_TYPE_TRACK
                else -> RadioSeedType.RADIO_SEED_TYPE_UNKNOWN
            }
            this.seedId = entity.seedId
            this.seedName = entity.seedName
            this.seedArtistName = entity.seedArtistName ?: ""
        }
    }

    // The server-side RadioService exposes a lightweight [TrackRef] for its
    // in-memory pipeline; translate it to the full [Track] proto so iOS has
    // playability + duration fields. The DB Track row is authoritative.
    private fun RadioService.TrackRef.toProtoTrack(): Track {
        val track = net.stewart.mediamanager.entity.Track.findById(trackId)
        return if (track != null) {
            val names = artistName?.let { listOf(it) } ?: emptyList()
            track.toProto(trackArtistNames = names)
        } else {
            // Fallback when the underlying row was deleted mid-session — emit
            // the minimal info the ref carries so clients can still render.
            track {
                id = trackId
                titleId = albumTitleId
                trackNumber = this@toProtoTrack.trackNumber
                discNumber = this@toProtoTrack.discNumber
                name = trackName
                artistName?.let { trackArtistNames.add(it) }
            }
        }
    }
}
