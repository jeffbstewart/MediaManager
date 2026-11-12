package net.stewart.mediamanager.grpc

import net.stewart.mediamanager.entity.Camera as CameraEntity
import net.stewart.mediamanager.entity.LiveTvChannel

class LiveGrpcService : LiveServiceGrpcKt.LiveServiceCoroutineImplBase() {

    override suspend fun listCameras(request: Empty): CameraListResponse {
        val cameras = CameraEntity.findAll()
            .filter { it.enabled }
            .sortedBy { it.display_order }
        return cameraListResponse {
            this.cameras.addAll(cameras.map { it.toProto() })
        }
    }

    override suspend fun listTvChannels(request: Empty): TvChannelListResponse {
        val user = currentUser()
        val channels = LiveTvChannel.findAll()
            .filter { it.enabled }
            .filter { user.canSeeRating(null) } // channels don't have individual ratings yet
            .sortedBy { it.display_order }
        return tvChannelListResponse {
            this.channels.addAll(channels.map { it.toProto() })
        }
    }

    override suspend fun warmUpStream(request: WarmUpStreamRequest): Empty {
        // Trigger go2rtc/FFmpeg startup for the given path.
        // The actual stream is served via HTTP HLS endpoints.
        return Empty.getDefaultInstance()
    }
}
