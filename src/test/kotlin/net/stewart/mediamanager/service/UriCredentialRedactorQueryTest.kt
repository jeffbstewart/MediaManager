package net.stewart.mediamanager.service

import kotlin.test.*

class UriCredentialRedactorQueryTest {

    @Test fun redactsDeviceKeyParam() {
        assertEquals("key=REDACTED", UriCredentialRedactor.redactQuery("key=0123abcd-device-token"))
    }

    @Test fun redactsTokenFamilyAndKnownNames() {
        val q = "token=a&access_token=b&refresh_token=c&code=d&password=e&api_key=f&deviceToken=g"
        assertEquals(
            "token=REDACTED&access_token=REDACTED&refresh_token=REDACTED&code=REDACTED" +
                "&password=REDACTED&api_key=REDACTED&deviceToken=REDACTED",
            UriCredentialRedactor.redactQuery(q)
        )
    }

    @Test fun preservesNonSensitiveParamsAndOrder() {
        assertEquals(
            "sort=name&key=REDACTED&page=2&q=star%20trek",
            UriCredentialRedactor.redactQuery("sort=name&key=secret&page=2&q=star%20trek")
        )
    }

    @Test fun matchesParamNameCaseInsensitivelyAndUrlDecoded() {
        assertEquals("KEY=REDACTED", UriCredentialRedactor.redactQuery("KEY=x"))
        assertEquals("api%5Fkey=REDACTED", UriCredentialRedactor.redactQuery("api%5Fkey=x"))
    }

    @Test fun keepsValuelessAndEmptyParts() {
        assertEquals("flag&&x=1", UriCredentialRedactor.redactQuery("flag&&x=1"))
        assertEquals("", UriCredentialRedactor.redactQuery(""))
    }

    @Test fun redactsRepeatedSensitiveParams() {
        assertEquals("key=REDACTED&key=REDACTED", UriCredentialRedactor.redactQuery("key=a&key=b"))
    }

    @Test fun malformedPercentEncodingDoesNotThrow() {
        assertEquals("%zz=1&key=REDACTED", UriCredentialRedactor.redactQuery("%zz=1&key=s"))
    }

    @Test fun redactForLogHandlesQueryAndUserinfo() {
        assertEquals(
            "https://***:***@host/x?api_key=REDACTED&format=json#frag",
            UriCredentialRedactor.redactForLog("https://u:p@host/x?api_key=abc123&format=json#frag")
        )
        assertEquals(
            "https://ws.example.com/2.0/?method=artist.getsimilar&api_key=REDACTED",
            UriCredentialRedactor.redactForLog("https://ws.example.com/2.0/?method=artist.getsimilar&api_key=abc")
        )
    }

    @Test fun redactForLogLeavesPlainUrlAlone() {
        val url = "https://example.com/path/to/image.jpg"
        assertEquals(url, UriCredentialRedactor.redactForLog(url))
    }

    @Test fun existingRedactDoesNotTouchQuery() {
        // Camera URL display/round-trip relies on redact() leaving the query alone.
        assertEquals(
            "rtsp://***:***@host:554/s?token=abc",
            UriCredentialRedactor.redact("rtsp://u:p@host:554/s?token=abc")
        )
    }
}
