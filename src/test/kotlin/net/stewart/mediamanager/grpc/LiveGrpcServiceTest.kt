package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [LiveGrpcService] — listCameras, listTvChannels,
 * and the warmUpStream side-effect RPC.
 */
class LiveGrpcServiceTest : GrpcTestBase() {

    @Before
    fun cleanLiveTables() {
        LiveTvChannel.deleteAll()
        LiveTvTuner.deleteAll()
        Camera.deleteAll()
    }

    // ---------------------- listCameras ----------------------

    @Test
    fun `listCameras returns enabled cameras in display_order, hiding disabled`() = runBlocking {
        val viewer = createViewerUser(username = "live-cams")
        // Insert deliberately scrambled to verify sort.
        Camera(name = "Front Door", rtsp_url = "rtsp://example.invalid/1",
            display_order = 2, enabled = true).save()
        Camera(name = "Back Yard", rtsp_url = "rtsp://example.invalid/2",
            display_order = 1, enabled = true).save()
        Camera(name = "Disabled", rtsp_url = "rtsp://example.invalid/3",
            display_order = 0, enabled = false).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = LiveServiceGrpcKt.LiveServiceCoroutineStub(authed)
            val resp = stub.listCameras(Empty.getDefaultInstance())
            assertEquals(2, resp.camerasCount, "disabled camera filtered out")
            assertEquals(listOf("Back Yard", "Front Door"),
                resp.camerasList.map { it.name },
                "display_order ascending")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listCameras returns empty when no cameras configured`() = runBlocking {
        val viewer = createViewerUser(username = "live-cams-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = LiveServiceGrpcKt.LiveServiceCoroutineStub(authed)
            val resp = stub.listCameras(Empty.getDefaultInstance())
            assertEquals(0, resp.camerasCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listCameras requires authentication`() = runBlocking {
        val stub = LiveServiceGrpcKt.LiveServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.listCameras(Empty.getDefaultInstance())
        }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
    }

    // ---------------------- listTvChannels ----------------------

    @Test
    fun `listTvChannels returns enabled channels in display_order, hiding disabled`() = runBlocking {
        val viewer = createViewerUser(username = "live-tv")
        val tuner = LiveTvTuner(name = "Tuner",
            ip_address = "203.0.113.50", tuner_count = 2,
            enabled = true).apply { save() }
        LiveTvChannel(tuner_id = tuner.id!!, guide_number = "9.1",
            guide_name = "Channel Nine", stream_url = "http://example.invalid/9",
            display_order = 2, enabled = true).save()
        LiveTvChannel(tuner_id = tuner.id!!, guide_number = "1.1",
            guide_name = "Channel One", stream_url = "http://example.invalid/1",
            display_order = 1, enabled = true).save()
        LiveTvChannel(tuner_id = tuner.id!!, guide_number = "5.1",
            guide_name = "Disabled", stream_url = "http://example.invalid/5",
            display_order = 0, enabled = false).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = LiveServiceGrpcKt.LiveServiceCoroutineStub(authed)
            val resp = stub.listTvChannels(Empty.getDefaultInstance())
            assertEquals(2, resp.channelsCount, "disabled channel filtered out")
            assertEquals(listOf("Channel One", "Channel Nine"),
                resp.channelsList.map { it.name },
                "display_order ascending")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listTvChannels returns empty when no channels configured`() = runBlocking {
        val viewer = createViewerUser(username = "live-tv-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = LiveServiceGrpcKt.LiveServiceCoroutineStub(authed)
            val resp = stub.listTvChannels(Empty.getDefaultInstance())
            assertEquals(0, resp.channelsCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- warmUpStream ----------------------

    @Test
    fun `warmUpStream returns Empty without error for any path`() = runBlocking {
        val viewer = createViewerUser(username = "live-warmup")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = LiveServiceGrpcKt.LiveServiceCoroutineStub(authed)
            // The RPC is a hint to start backing infrastructure; it intentionally
            // doesn't surface go2rtc/ffmpeg failures, so any path returns Empty.
            stub.warmUpStream(warmUpStreamRequest { path = "cameras/front" })
            assertTrue(true, "warmUpStream returned without throwing")
        } finally {
            authed.shutdownNow()
        }
    }
}
