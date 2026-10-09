package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.Camera
import kotlin.test.*

class CameraSourceValidatorTest {

    private fun rejects(url: String) =
        assertFailsWith<IllegalArgumentException>("expected rejection: $url") {
            CameraSourceValidator.requireValidRtspUrl(url)
        }

    // ---------------- accepted ----------------

    @Test fun acceptsPlainRtsp() {
        assertEquals("rtsp://cam.example.invalid:554/stream1",
            CameraSourceValidator.requireValidRtspUrl("rtsp://cam.example.invalid:554/stream1"))
    }

    @Test fun acceptsRtspsAndUppercaseScheme() {
        CameraSourceValidator.requireValidRtspUrl("rtsps://cam.example.invalid/s")
        CameraSourceValidator.requireValidRtspUrl("RTSP://cam.example.invalid/s")
    }

    @Test fun acceptsCredentialsWithSpecialCharacters() {
        // Real camera passwords contain chars java.net.URI rejects.
        CameraSourceValidator.requireValidRtspUrl(
            "rtsp://camuser:ab3#Qz^w9@192.168.1.100/cam/realmonitor?channel=1&subtype=0")
        CameraSourceValidator.requireValidRtspUrl("rtsp://user:p@ss/w@rd@192.168.1.100:554/s")
    }

    @Test fun acceptsIpv6Literal() {
        CameraSourceValidator.requireValidRtspUrl("rtsp://[2001:db8::1]:554/s")
    }

    @Test fun trimsSurroundingWhitespace() {
        assertEquals("rtsp://h/s", CameraSourceValidator.requireValidRtspUrl("  rtsp://h/s \n"))
    }

    // ---------------- rejected ----------------

    @Test fun rejectsBlank() { rejects(""); rejects("   ") }

    @Test fun rejectsGo2rtcCommandSchemes() {
        rejects("exec:ffmpeg -i x")
        rejects("exec:/bin/sh#killsignal=9")
        rejects("echo:hello")
        rejects("ffmpeg:rtsp://h/s#raw=-f")
        rejects("expr:let x = 1")
    }

    @Test fun rejectsOtherSchemes() {
        rejects("http://cam.example.invalid/s")
        rejects("file:///etc/passwd")
        rejects("rtspx://h/s")
    }

    @Test fun rejectsNewlineInjection() {
        rejects("rtsp://h/s\n    - exec:id")
        rejects("rtsp://h/s\r\n  evil:\n    - exec:id")
        rejects("rtsp://h/s\u2028- exec:id")
        rejects("rtsp://h/s\u0085- exec:id")
    }

    @Test fun rejectsControlCharsWhitespaceAndNonAscii() {
        rejects("rtsp://h/s\u0000")
        rejects("rtsp://h/s\tx")
        rejects("rtsp://h/a b")
        rejects("rtsp://h\u00e9/s")
    }

    @Test fun rejectsMissingOrMalformedHost() {
        rejects("rtsp://")
        rejects("rtsp:///path")
        rejects("rtsp://user:pass@/path")
        rejects("rtsp://h:99999999/s")
    }

    // ---------------- snapshot / stream name ----------------

    @Test fun snapshotUrlBlankAllowedHttpRequired() {
        assertEquals("", CameraSourceValidator.requireValidSnapshotUrl("  "))
        CameraSourceValidator.requireValidSnapshotUrl("https://u:p@cam.example.invalid/snap.jpg")
        assertFailsWith<IllegalArgumentException> { CameraSourceValidator.requireValidSnapshotUrl("exec:id") }
        assertFailsWith<IllegalArgumentException> {
            CameraSourceValidator.requireValidSnapshotUrl("http://h/x\ny")
        }
    }

    @Test fun streamNames() {
        assertEquals("front_door", CameraSourceValidator.requireValidStreamName("front_door"))
        CameraSourceValidator.requireValidStreamName("front-door-cam")
        for (bad in listOf("", "a b", "a:b", "a#b", "a\nb", "x".repeat(65), "\"q\"")) {
            assertFailsWith<IllegalArgumentException>(bad) { CameraSourceValidator.requireValidStreamName(bad) }
        }
    }

    // ---------------- go2rtc config rendering ----------------

    @Test fun yamlQuoteEscapesEverythingThatCouldBreakOut() {
        assertEquals("\"plain\"", Go2rtcAgent.yamlQuote("plain"))
        assertEquals("\"a\\\"b\\\\c\"", Go2rtcAgent.yamlQuote("a\"b\\c"))
        assertEquals("\"a\\u000ab\\u000dc\\u2028\"", Go2rtcAgent.yamlQuote("a\nb\rc\u2028"))
    }

    @Test fun configQuotesValidSourcesAndSkipsInvalidRows() {
        val good = Camera(id = 1, go2rtc_name = "front", rtsp_url = "rtsp://u:p#w@192.168.1.100/s1")
        // Rows written before validation existed must not reach go2rtc.
        val execRow = Camera(id = 2, go2rtc_name = "evil", rtsp_url = "exec:/bin/sh -c id")
        val newlineRow = Camera(id = 3, go2rtc_name = "nl", rtsp_url = "rtsp://h/s\n    - exec:id")
        val badName = Camera(id = 4, go2rtc_name = "x:\n  y", rtsp_url = "rtsp://h/s")
        val skipped = mutableListOf<Long?>()

        val yaml = Go2rtcAgent.buildConfig(
            apiPort = 1984,
            ffmpegPath = "C:\\tools\\ffmpeg.exe",
            cameras = listOf(good, execRow, newlineRow, badName)
        ) { cam, _ -> skipped += cam.id }

        assertEquals(listOf<Long?>(2, 3, 4), skipped)
        assertFalse(yaml.contains("exec:"))
        assertTrue(yaml.contains("  \"front\":\n    - \"rtsp://u:p#w@192.168.1.100/s1\"\n"))
        assertTrue(yaml.contains("    - \"ffmpeg:front#video=mjpeg\"\n"))
        // Windows path backslashes are escaped, not read as YAML escapes.
        assertTrue(yaml.contains("  bin: \"C:\\\\tools\\\\ffmpeg.exe\"\n"))
        // One stream key only.
        assertEquals(1, yaml.lines().count { it.startsWith("  \"") && it.endsWith("\":") })
    }
}
