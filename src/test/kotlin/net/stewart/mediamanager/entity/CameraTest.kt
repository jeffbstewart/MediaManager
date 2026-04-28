package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraTest {

    // Camera.toString is the only logic on the entity. It exists
    // specifically so a Camera dumped to a log doesn't print the
    // RTSP password. Verify the redactor is applied.

    @Test
    fun `toString redacts credentials in rtsp_url`() {
        val c = Camera(
            id = 1,
            name = "Front Door",
            rtsp_url = "rtsp://admin:hunter2@cam.lan:554/stream1",
        )
        val s = c.toString()
        assertFalse(s.contains("hunter2"), "password should be redacted: $s")
        // The redactor preserves the user portion + host so the log is
        // still useful for triage.
        assertTrue(s.contains("Front Door"), s)
        assertTrue(s.contains("id=1"), s)
        assertTrue(s.contains("cam.lan"), s)
    }

    @Test
    fun `toString tolerates a credential-less url`() {
        val c = Camera(id = 2, name = "Back Yard", rtsp_url = "rtsp://cam.lan/stream")
        val s = c.toString()
        assertTrue(s.contains("Back Yard"))
        assertTrue(s.contains("cam.lan"))
    }
}
