package net.stewart.mediamanager.grpc

import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Camera
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 5 of [AdminGrpcService] coverage — camera-management RPCs.
 * Service-layer require() failures bubble up as gRPC errors; the tests
 * lock down both the happy paths and the validation surface.
 */
class AdminGrpcServiceCamerasTest : GrpcTestBase() {

    @Before
    fun cleanCameraTable() {
        Camera.deleteAll()
    }

    // ---------------------- listAdminCameras ----------------------

    @Test
    fun `listAdminCameras returns every camera sorted by display_order`() = runBlocking {
        val admin = createAdminUser(username = "cam-list-admin")
        Camera(name = "Front Door", rtsp_url = "rtsp://example.invalid/1",
            display_order = 2, enabled = true).save()
        Camera(name = "Back Yard", rtsp_url = "rtsp://example.invalid/2",
            display_order = 0, enabled = false).save()
        Camera(name = "Driveway", rtsp_url = "rtsp://example.invalid/3",
            display_order = 1, enabled = true).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listAdminCameras(Empty.getDefaultInstance())
            // listAll returns ALL cameras (including disabled) — admin view.
            assertEquals(3, resp.camerasCount)
            assertEquals(listOf("Back Yard", "Driveway", "Front Door"),
                resp.camerasList.map { it.name })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- createCamera ----------------------

    @Test
    fun `createCamera persists the row, normalizes stream_name, and sets display_order to next slot`() = runBlocking {
        val admin = createAdminUser(username = "cam-create")
        // Pre-existing camera at order 0 — new one should land at 1.
        Camera(name = "Existing", rtsp_url = "rtsp://example.invalid/0",
            display_order = 0).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.createCamera(createCameraRequest {
                name = "  Front Door  "
                rtspUrl = "rtsp://camera.invalid/stream1"
                snapshotUrl = ""
                enabled = true
                // No stream_name -> auto-generate from name.
            })
            assertTrue(resp.id > 0)
            assertEquals("Front Door", resp.name, "name trimmed")
            assertEquals("front_door", resp.streamName,
                "auto-generated slug from camera name")
            assertEquals(1, resp.displayOrder, "appended after existing")
            assertTrue(resp.enabled)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createCamera honors an explicit stream_name`() = runBlocking {
        val admin = createAdminUser(username = "cam-create-stream")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.createCamera(createCameraRequest {
                name = "Front Door"
                rtspUrl = "rtsp://camera.invalid/abc"
                snapshotUrl = ""
                enabled = true
                streamName = "front-door-cam"
            })
            assertEquals("front-door-cam", resp.streamName)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createCamera rejects blank name`() = runBlocking {
        val admin = createAdminUser(username = "cam-create-blank")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            // Service layer throws IllegalArgumentException via require();
            // gRPC infrastructure surfaces it as StatusException.
            val ex = assertFailsWith<StatusException> {
                stub.createCamera(createCameraRequest {
                    name = "   "
                    rtspUrl = "rtsp://x/y"
                    snapshotUrl = ""
                    enabled = true
                })
            }
            assertTrue(ex.status != null)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createCamera rejects non-rtsp URL`() = runBlocking {
        val admin = createAdminUser(username = "cam-create-nonrtsp")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.createCamera(createCameraRequest {
                    name = "Cam"
                    rtspUrl = "http://wrong-scheme/feed"
                    snapshotUrl = ""
                    enabled = true
                })
            }
            assertTrue(ex.status != null)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updateCamera ----------------------

    @Test
    fun `updateCamera mutates fields in place`() = runBlocking {
        val admin = createAdminUser(username = "cam-update")
        val cam = Camera(name = "Old Name",
            rtsp_url = "rtsp://example.invalid/old",
            snapshot_url = "http://example.invalid/snap",
            go2rtc_name = "old", enabled = true).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.updateCamera(updateCameraRequest {
                cameraId = cam.id!!
                name = "New Name"
                rtspUrl = "rtsp://example.invalid/new"
                snapshotUrl = ""
                streamName = "new-stream"
                enabled = false
            })
            assertEquals("New Name", resp.name)
            assertEquals("rtsp://example.invalid/new", resp.rtspUrl)
            assertEquals("new-stream", resp.streamName)
            assertEquals(false, resp.enabled)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateCamera throws on unknown id`() = runBlocking {
        val admin = createAdminUser(username = "cam-update-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateCamera(updateCameraRequest {
                    cameraId = 999_999
                    name = "x"
                    rtspUrl = "rtsp://x/y"
                    snapshotUrl = ""
                    streamName = "x"
                    enabled = true
                })
            }
            assertTrue(ex.status != null)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- deleteCamera ----------------------

    @Test
    fun `deleteCamera removes the row`() = runBlocking {
        val admin = createAdminUser(username = "cam-delete")
        val cam = Camera(name = "Doomed",
            rtsp_url = "rtsp://example.invalid/d").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.deleteCamera(cameraIdRequest { cameraId = cam.id!! })
            assertNull(Camera.findById(cam.id!!))
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deleteCamera throws on unknown id`() = runBlocking {
        val admin = createAdminUser(username = "cam-delete-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.deleteCamera(cameraIdRequest { cameraId = 999_999 })
            }
            assertTrue(ex.status != null)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- reorderCameras ----------------------

    @Test
    fun `reorderCameras rewrites display_order according to the supplied ordering`() = runBlocking {
        val admin = createAdminUser(username = "cam-reorder")
        val a = Camera(name = "A", rtsp_url = "rtsp://x/a",
            display_order = 0).apply { save() }
        val b = Camera(name = "B", rtsp_url = "rtsp://x/b",
            display_order = 1).apply { save() }
        val c = Camera(name = "C", rtsp_url = "rtsp://x/c",
            display_order = 2).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            // Reverse the order.
            stub.reorderCameras(reorderCamerasRequest {
                cameraIds.addAll(listOf(c.id!!, b.id!!, a.id!!))
            })
            assertEquals(0, Camera.findById(c.id!!)!!.display_order)
            assertEquals(1, Camera.findById(b.id!!)!!.display_order)
            assertEquals(2, Camera.findById(a.id!!)!!.display_order)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `reorderCameras silently skips unknown ids`() = runBlocking {
        val admin = createAdminUser(username = "cam-reorder-skip")
        val a = Camera(name = "A", rtsp_url = "rtsp://x/a",
            display_order = 0).apply { save() }
        val b = Camera(name = "B", rtsp_url = "rtsp://x/b",
            display_order = 1).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            // Mix a real id with a bogus one — service skips the unknown.
            stub.reorderCameras(reorderCamerasRequest {
                cameraIds.addAll(listOf(b.id!!, 999_999, a.id!!))
            })
            // 'b' moved to position 0; 'a' kept its order=1 because the
            // bogus id at index 1 is skipped (no shift), and 'a' lands
            // at index 2.
            assertEquals(0, Camera.findById(b.id!!)!!.display_order)
            assertEquals(2, Camera.findById(a.id!!)!!.display_order)
        } finally {
            authed.shutdownNow()
        }
    }
}
