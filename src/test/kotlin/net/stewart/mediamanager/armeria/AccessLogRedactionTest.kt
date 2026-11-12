package net.stewart.mediamanager.armeria

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AccessLogRedactionTest {

    @Test
    fun `device token query param is redacted`() {
        val uri = AccessLogDecorator.logSafeUri("/stream/42", "key=device-token-value-1111")
        assertEquals("/stream/42?key=REDACTED", uri)
        assertFalse(uri.contains("1111"))
    }

    @Test
    fun `non-sensitive params are kept verbatim`() {
        assertEquals(
            "/api/v2/catalog?sort=name&page=3",
            AccessLogDecorator.logSafeUri("/api/v2/catalog", "sort=name&page=3")
        )
    }

    @Test
    fun `mixed query only redacts credentials`() {
        assertEquals(
            "/roku/feed.json?key=REDACTED&lang=en",
            AccessLogDecorator.logSafeUri("/roku/feed.json", "key=abc&lang=en")
        )
    }

    @Test
    fun `path without query is unchanged`() {
        assertEquals("/posters/w500/7", AccessLogDecorator.logSafeUri("/posters/w500/7", null))
    }

    @Test
    fun `path-embedded public art token is redacted`() {
        assertEquals(
            "/public/album-art/REDACTED",
            AccessLogDecorator.logSafeUri("/public/album-art/eyJhbGciOi.signed", null)
        )
    }
}
