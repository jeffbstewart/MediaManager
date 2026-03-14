package net.stewart.mediamanager.service

import kotlin.test.*

class UriCredentialRedactorTest {

    @Test fun redactsRtspCredentials() {
        assertEquals(
            "rtsp://***:***@192.168.1.100:554/stream",
            UriCredentialRedactor.redact("rtsp://admin:secret@192.168.1.100:554/stream")
        )
    }

    @Test fun redactsHttpCredentials() {
        assertEquals(
            "http://***:***@192.168.1.100/snapshot.jpg",
            UriCredentialRedactor.redact("http://user:pass@192.168.1.100/snapshot.jpg")
        )
    }

    @Test fun redactsHttpsCredentials() {
        assertEquals(
            "https://***:***@cam.example.com:8443/path",
            UriCredentialRedactor.redact("https://admin:p4ss@cam.example.com:8443/path")
        )
    }

    @Test fun preservesUrlWithoutCredentials() {
        val url = "rtsp://192.168.1.100:554/stream"
        assertEquals(url, UriCredentialRedactor.redact(url))
    }

    @Test fun preservesEmptyString() {
        assertEquals("", UriCredentialRedactor.redact(""))
    }

    @Test fun preservesBlankString() {
        assertEquals("  ", UriCredentialRedactor.redact("  "))
    }

    @Test fun preservesInvalidUrl() {
        val garbage = "not-a-url"
        assertEquals(garbage, UriCredentialRedactor.redact(garbage))
    }

    @Test fun preservesQueryAndFragment() {
        assertEquals(
            "rtsp://***:***@host:554/path?param=value#frag",
            UriCredentialRedactor.redact("rtsp://user:pass@host:554/path?param=value#frag")
        )
    }

    @Test fun redactsAllInText() {
        val text = "Connecting to rtsp://admin:secret@192.168.1.100:554/stream and http://user:pass@cam.local/snap"
        val redacted = UriCredentialRedactor.redactAll(text)
        assertContains(redacted, "rtsp://***:***@192.168.1.100:554/stream")
        assertContains(redacted, "http://***:***@cam.local/snap")
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("pass"))
    }

    @Test fun redactAllPreservesNonUrlText() {
        val text = "go2rtc started on port 1984, ready for streams"
        assertEquals(text, UriCredentialRedactor.redactAll(text))
    }

    @Test fun redactAllHandlesUrlWithoutCredentials() {
        val text = "Proxying http://127.0.0.1:1984/api/streams"
        assertEquals(text, UriCredentialRedactor.redactAll(text))
    }

    @Test fun handlesUrlWithUsernameOnly() {
        // URI with userInfo but no password separator
        val url = "rtsp://adminonly@192.168.1.100:554/stream"
        val redacted = UriCredentialRedactor.redact(url)
        assertFalse(redacted.contains("adminonly"))
    }

    @Test fun handlesUrlWithNoPort() {
        assertEquals(
            "rtsp://***:***@camera.local/stream1",
            UriCredentialRedactor.redact("rtsp://admin:pass@camera.local/stream1")
        )
    }
}
