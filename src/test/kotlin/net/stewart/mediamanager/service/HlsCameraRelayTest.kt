package net.stewart.mediamanager.service

import org.junit.After
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [HlsCameraRelay] and [HlsRelayManager]. The polling
 * loop and segment-parse paths are reachable through a scripted
 * [RelayHttpFetcher] fake; the lifecycle, ring buffer, idle tracking,
 * and m3u8-generation paths run without any HTTP at all.
 */
internal class HlsCameraRelayTest {

    /**
     * In-memory fetcher: tests pre-load `responses` keyed on URL, and
     * the relay reads scripted bytes/strings on demand. Unscripted URLs
     * return null (mirrors the production "404 → drop the request" shape).
     */
    private class FakeFetcher : RelayHttpFetcher {
        val textResponses = ConcurrentHashMap<String, String>()
        val binaryResponses = ConcurrentHashMap<String, ByteArray>()
        val textRequests = mutableListOf<String>()
        val binaryRequests = mutableListOf<String>()

        override fun fetchText(url: String): String? {
            synchronized(textRequests) { textRequests.add(url) }
            return textResponses[url]
        }

        override fun fetchBinary(url: String): ByteArray? {
            synchronized(binaryRequests) { binaryRequests.add(url) }
            return binaryResponses[url]
        }
    }

    @After
    fun cleanupRelays() {
        // HlsRelayManager is an object — state persists across tests.
        HlsRelayManager.stopAll()
    }

    // ---------------------- lifecycle + idle tracking ----------------------

    @Test
    fun `isRunning reflects start and stop`() {
        val relay = HlsCameraRelay(1L, "front", 1984, FakeFetcher())
        assertFalse(relay.isRunning())
        relay.start()
        assertTrue(relay.isRunning())
        relay.stop()
        // Brief wait — stop interrupts the polling thread; the running
        // flag flips synchronously.
        assertFalse(relay.isRunning())
    }

    @Test
    fun `start is idempotent`() {
        val relay = HlsCameraRelay(2L, "back", 1984, FakeFetcher())
        relay.start()
        relay.start()  // second call is a no-op (compareAndSet guard)
        assertTrue(relay.isRunning())
        relay.stop()
    }

    @Test
    fun `stop on a never-started relay is a no-op`() {
        val relay = HlsCameraRelay(3L, "side", 1984, FakeFetcher())
        relay.stop()  // must not throw
        assertFalse(relay.isRunning())
    }

    @Test
    fun `touch and idleMillis tracking`() {
        val relay = HlsCameraRelay(4L, "porch", 1984, FakeFetcher())
        // idleMillis is monotonic from the relay's lastAccessTime.
        // Two reads in quick succession should be small + non-negative.
        val first = relay.idleMillis()
        Thread.sleep(20)
        val second = relay.idleMillis()
        assertTrue(second >= first, "idleMillis monotone increasing")
        relay.touch()
        // After touch, idleMillis resets near zero.
        assertTrue(relay.idleMillis() < second,
            "touch resets idleMillis below previous value")
    }

    // ---------------------- empty ring buffer ----------------------

    @Test
    fun `segmentCount is 0 on a fresh relay`() {
        val relay = HlsCameraRelay(5L, "x", 1984, FakeFetcher())
        assertEquals(0, relay.segmentCount())
    }

    @Test
    fun `generatePlaylist returns null when buffer is below the minSegments threshold`() {
        val relay = HlsCameraRelay(6L, "x", 1984, FakeFetcher())
        assertNull(relay.generatePlaylist(
            baseUrl = "https://example.test",
            keyParam = "key=abc",
            minSegments = 10,
        ))
    }

    @Test
    fun `getSegment returns null when buffer is empty`() {
        val relay = HlsCameraRelay(7L, "x", 1984, FakeFetcher())
        assertNull(relay.getSegment(0L))
    }

    // ---------------------- happy-path polling + buffering ----------------------

    /**
     * Construct, start, and drive a relay through one go2rtc poll cycle.
     * Returns once [expectedSegmentCount] segments have been buffered
     * (or `timeoutMs` elapses, whichever comes first).
     */
    private fun pollUntilBuffered(
        relay: HlsCameraRelay,
        expectedSegmentCount: Int,
        timeoutMs: Long = 3_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (relay.segmentCount() >= expectedSegmentCount) return true
            Thread.sleep(20)
        }
        return relay.segmentCount() >= expectedSegmentCount
    }

    @Test
    fun `relay fetches master plus variant playlists and buffers each new segment`() {
        val fake = FakeFetcher()
        // go2rtc master playlist: a single variant URL line.
        fake.textResponses["http://127.0.0.1:1984/api/stream.m3u8?src=front"] = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1024
            hls/playlist.m3u8?id=session1
        """.trimIndent()
        // Variant playlist: two segments, with TARGETDURATION metadata.
        fake.textResponses["http://127.0.0.1:1984/api/hls/playlist.m3u8?id=session1"] = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:1
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:0.500,
            seg0.ts?id=session1&n=0
            #EXTINF:0.500,
            seg1.ts?id=session1&n=1
        """.trimIndent()
        // Segment bytes.
        fake.binaryResponses["http://127.0.0.1:1984/api/hls/seg0.ts?id=session1&n=0"] =
            byteArrayOf(0x47, 0x40, 0x11)  // MPEG-TS-shaped magic
        fake.binaryResponses["http://127.0.0.1:1984/api/hls/seg1.ts?id=session1&n=1"] =
            byteArrayOf(0x47, 0x40, 0x12)

        val relay = HlsCameraRelay(10L, "front", 1984, fake)
        relay.start()
        try {
            assertTrue(pollUntilBuffered(relay, expectedSegmentCount = 2),
                "relay should buffer both segments within the timeout")
            assertEquals(2, relay.segmentCount())
            // Sequence numbers are assigned in arrival order starting at 0.
            assertNotNull(relay.getSegment(0L))
            assertNotNull(relay.getSegment(1L))
            assertNull(relay.getSegment(99L))
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `generatePlaylist after polling produces a valid m3u8 with sequence numbers`() {
        val fake = FakeFetcher()
        fake.textResponses["http://127.0.0.1:1984/api/stream.m3u8?src=cam"] =
            "#EXTM3U\nhls/p.m3u8?s=1"
        // Build 12 segments so we clear the default minSegments=10 gate.
        val segLines = (0 until 12).joinToString("\n") {
            "#EXTINF:0.5,\nseg$it.ts?s=1&n=$it"
        }
        fake.textResponses["http://127.0.0.1:1984/api/hls/p.m3u8?s=1"] =
            "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n$segLines"
        for (i in 0 until 12) {
            fake.binaryResponses["http://127.0.0.1:1984/api/hls/seg$i.ts?s=1&n=$i"] =
                byteArrayOf(i.toByte())
        }

        val relay = HlsCameraRelay(11L, "cam", 1984, fake)
        relay.start()
        try {
            assertTrue(pollUntilBuffered(relay, expectedSegmentCount = 12))
            val playlist = relay.generatePlaylist(
                baseUrl = "https://example.test",
                keyParam = "key=secret",
                minSegments = 10,
            )
            assertNotNull(playlist)
            assertTrue("#EXTM3U" in playlist)
            assertTrue("#EXT-X-VERSION:3" in playlist)
            assertTrue("#EXT-X-MEDIA-SEQUENCE:0" in playlist)
            // First segment line carries our base URL + the relay's seq,
            // and the key param is appended.
            assertTrue("https://example.test/cam/11/hls/segment/0?key=secret" in playlist)
            // EXTINF carries the duration we parsed out of the variant.
            assertTrue("#EXTINF:0.500" in playlist)
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `ring buffer evicts oldest segments past the cap of 30`() {
        val fake = FakeFetcher()
        fake.textResponses["http://127.0.0.1:1984/api/stream.m3u8?src=ring"] =
            "#EXTM3U\nv.m3u8?s=1"
        // 35 segments — cap is 30, oldest 5 should be evicted.
        val segLines = (0 until 35).joinToString("\n") {
            "#EXTINF:0.5,\ns$it.ts?n=$it"
        }
        fake.textResponses["http://127.0.0.1:1984/api/v.m3u8?s=1"] =
            "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n$segLines"
        for (i in 0 until 35) {
            fake.binaryResponses["http://127.0.0.1:1984/api/s$i.ts?n=$i"] =
                byteArrayOf(i.toByte())
        }

        val relay = HlsCameraRelay(12L, "ring", 1984, fake)
        relay.start()
        try {
            // Wait until ring-buffer fills up (30 cap).
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && relay.segmentCount() < 30) {
                Thread.sleep(50)
            }
            assertEquals(30, relay.segmentCount(),
                "ring buffer should cap at 30 once it fills up")
            // Oldest sequence numbers (0..4) should have been evicted.
            assertNull(relay.getSegment(0L))
            // Newest sequence numbers should be present.
            assertNotNull(relay.getSegment(34L))
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `relay deduplicates segments seen within a single session`() {
        val fake = FakeFetcher()
        fake.textResponses["http://127.0.0.1:1984/api/stream.m3u8?src=dedup"] =
            "#EXTM3U\nv.m3u8?s=1"
        // Same segment appears in every variant playlist poll. Only one
        // copy lands in the ring buffer.
        fake.textResponses["http://127.0.0.1:1984/api/v.m3u8?s=1"] = """
            #EXTM3U
            #EXT-X-TARGETDURATION:1
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:0.500,
            stable.ts?n=0
        """.trimIndent()
        fake.binaryResponses["http://127.0.0.1:1984/api/stable.ts?n=0"] =
            byteArrayOf(0xAA.toByte())

        val relay = HlsCameraRelay(13L, "dedup", 1984, fake)
        relay.start()
        try {
            // Wait long enough for several poll cycles (POLL_INTERVAL=500ms).
            Thread.sleep(2_000)
            assertEquals(1, relay.segmentCount(),
                "duplicate segment must not be re-buffered")
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `master playlist with no variant URL aborts the session and the relay retries`() {
        val fake = FakeFetcher()
        // Master playlist with only headers, no variant line.
        fake.textResponses["http://127.0.0.1:1984/api/stream.m3u8?src=novar"] =
            "#EXTM3U\n#EXT-X-VERSION:3"

        val relay = HlsCameraRelay(14L, "novar", 1984, fake)
        relay.start()
        try {
            // Give the relay a chance to attempt the master fetch a couple
            // of times. Each attempt should bail without buffering.
            Thread.sleep(500)
            assertEquals(0, relay.segmentCount())
            // The relay should still be running — the loop catches the
            // abort and retries after a 2s sleep.
            assertTrue(relay.isRunning())
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `relay continues running when master playlist fetch returns null`() {
        val fake = FakeFetcher()
        // No responses configured — every fetch returns null.
        val relay = HlsCameraRelay(15L, "broken", 1984, fake)
        relay.start()
        try {
            Thread.sleep(300)
            // No segments buffered, but the relay is still alive.
            assertEquals(0, relay.segmentCount())
            assertTrue(relay.isRunning())
            // The fetcher recorded at least one attempt.
            assertTrue(fake.textRequests.isNotEmpty())
        } finally {
            relay.stop()
        }
    }

    // ---------------------- HlsRelayManager ----------------------

    @Test
    fun `getOrCreateRelay returns a new relay and starts it`() {
        val relay = HlsRelayManager.getOrCreateRelay(100L, "x", 1984)
        assertNotNull(relay)
        assertEquals(100L, relay.cameraId)
        // Production fetcher will fail to talk to 127.0.0.1:1984 but the
        // relay still ticks. Stop it immediately to keep the test quick.
        assertEquals(1, HlsRelayManager.activeRelayCount())
    }

    @Test
    fun `getOrCreateRelay returns the same instance on a second call`() {
        val first = HlsRelayManager.getOrCreateRelay(101L, "x", 1984)
        val second = HlsRelayManager.getOrCreateRelay(101L, "x", 1984)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.cameraId, second.cameraId)
        assertEquals(1, HlsRelayManager.activeRelayCount())
    }

    @Test
    fun `getRelay returns null for an unknown camera id`() {
        assertNull(HlsRelayManager.getRelay(9999L))
    }

    @Test
    fun `getRelay returns the existing relay and refreshes its idle timer`() {
        val created = HlsRelayManager.getOrCreateRelay(102L, "x", 1984)!!
        Thread.sleep(20)
        val before = created.idleMillis()
        val fetched = HlsRelayManager.getRelay(102L)
        assertNotNull(fetched)
        // touch() inside getRelay resets idleMillis below the prior read.
        assertTrue(fetched.idleMillis() <= before)
    }

    @Test
    fun `stopAll clears every active relay`() {
        HlsRelayManager.getOrCreateRelay(103L, "a", 1984)
        HlsRelayManager.getOrCreateRelay(104L, "b", 1984)
        assertEquals(2, HlsRelayManager.activeRelayCount())
        HlsRelayManager.stopAll()
        assertEquals(0, HlsRelayManager.activeRelayCount())
    }
}
