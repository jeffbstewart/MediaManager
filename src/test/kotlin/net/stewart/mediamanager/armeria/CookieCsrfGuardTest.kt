package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.DeviceToken
import net.stewart.mediamanager.entity.SessionToken
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.LegalRequirements
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure header evaluation for [CookieCsrfGuard]. */
class CookieCsrfGuardTest {

    private fun headers(
        method: HttpMethod,
        origin: String? = null,
        secFetchSite: String? = null,
        contentType: String? = null,
        contentLength: Long? = null,
        authority: String = "media.example.test",
    ): RequestHeaders {
        val b = RequestHeaders.builder(method, "/api/v2/x").authority(authority)
        if (origin != null) b.add("origin", origin)
        if (secFetchSite != null) b.add("sec-fetch-site", secFetchSite)
        if (contentType != null) b.add("content-type", contentType)
        if (contentLength != null) b.contentLength(contentLength)
        return b.build()
    }

    @Test fun `safe methods always pass`() {
        for (m in listOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)) {
            assertEquals(CookieCsrfGuard.Verdict.OK, CookieCsrfGuard.evaluate(
                headers(m, origin = "https://evil.example.test", contentType = "text/plain")))
        }
    }

    @Test fun `same-origin JSON POST passes`() {
        assertEquals(CookieCsrfGuard.Verdict.OK, CookieCsrfGuard.evaluate(
            headers(HttpMethod.POST, origin = "https://media.example.test",
                contentType = "application/json; charset=utf-8")))
    }

    @Test fun `origin port difference is tolerated (proxy rewrite)`() {
        assertEquals(CookieCsrfGuard.Verdict.OK, CookieCsrfGuard.evaluate(
            headers(HttpMethod.POST, origin = "https://media.example.test:443",
                contentType = "application/json", authority = "media.example.test:9090")))
    }

    @Test fun `sibling subdomain origin is rejected`() {
        assertEquals(CookieCsrfGuard.Verdict.CROSS_ORIGIN, CookieCsrfGuard.evaluate(
            headers(HttpMethod.POST, origin = "https://other.example.test", contentType = "application/json")))
    }

    @Test fun `opaque null origin is rejected`() {
        assertEquals(CookieCsrfGuard.Verdict.CROSS_ORIGIN, CookieCsrfGuard.evaluate(
            headers(HttpMethod.DELETE, origin = "null")))
    }

    @Test fun `without Origin, cross-site Sec-Fetch-Site is rejected`() {
        for (site in listOf("same-site", "cross-site")) {
            assertEquals(CookieCsrfGuard.Verdict.CROSS_ORIGIN, CookieCsrfGuard.evaluate(
                headers(HttpMethod.POST, secFetchSite = site, contentType = "application/json")))
        }
        assertEquals(CookieCsrfGuard.Verdict.OK, CookieCsrfGuard.evaluate(
            headers(HttpMethod.POST, secFetchSite = "same-origin", contentType = "application/json")))
    }

    @Test fun `CORS-safelisted body types are refused`() {
        for (ct in listOf("text/plain", "text/plain;charset=UTF-8",
                "application/x-www-form-urlencoded", "multipart/form-data; boundary=x")) {
            assertEquals(CookieCsrfGuard.Verdict.SIMPLE_BODY, CookieCsrfGuard.evaluate(
                headers(HttpMethod.POST, origin = "https://media.example.test", contentType = ct)), ct)
        }
    }

    @Test fun `binary upload types pass`() {
        for (ct in listOf("application/octet-stream", "image/jpeg", "image/png")) {
            assertEquals(CookieCsrfGuard.Verdict.OK, CookieCsrfGuard.evaluate(
                headers(HttpMethod.POST, origin = "https://media.example.test", contentType = ct)), ct)
        }
    }

    @Test fun `untyped body handling depends on length`() {
        assertEquals(CookieCsrfGuard.Verdict.OK,
            CookieCsrfGuard.evaluate(headers(HttpMethod.DELETE, contentLength = 0)))
        assertEquals(CookieCsrfGuard.Verdict.SIMPLE_BODY,
            CookieCsrfGuard.evaluate(headers(HttpMethod.POST, contentLength = 12)))
        assertEquals(CookieCsrfGuard.Verdict.NEEDS_EMPTY_BODY,
            CookieCsrfGuard.evaluate(headers(HttpMethod.DELETE)))
    }

    @Test fun `originPermitted rules`() {
        assertTrue(CookieCsrfGuard.originPermitted(null, "h"))
        assertTrue(CookieCsrfGuard.originPermitted("https://h:1", "h:2"))
        assertTrue(CookieCsrfGuard.originPermitted("http://[::1]:8080", "[::1]:9090"))
        assertFalse(CookieCsrfGuard.originPermitted("https://h", null))
        assertFalse(CookieCsrfGuard.originPermitted("https://h.evil", "h"))
        assertFalse(CookieCsrfGuard.originPermitted("not a url", "h"))
    }
}

/** [ArmeriaAuthDecorator] wiring of the cookie CSRF gate. */
internal class ArmeriaAuthDecoratorCsrfTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("authcsrf") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val decorator = ArmeriaAuthDecorator()

    @Before
    fun reset() {
        DeviceToken.deleteAll()
        SessionToken.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
        AuthService.invalidateHasUsersCache()
        LegalRequirements.refresh()
    }

    /** Don't leak a cached "users exist" answer into later test classes. */
    @After
    fun cleanup() {
        SessionToken.deleteAll()
        DeviceToken.deleteAll()
        AppUser.deleteAll()
        AuthService.invalidateHasUsersCache()
    }

    /** Delegate that echoes the request body it can read from ctx.request(). */
    private val echoDelegate = HttpService { ctx, _ ->
        HttpResponse.of(ctx.request().aggregate().thenApply { agg ->
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "body=[" + agg.contentUtf8() + "]")
        })
    }

    private fun request(
        method: HttpMethod,
        cookie: String? = null,
        bearer: String? = null,
        key: String? = null,
        origin: String? = null,
        contentType: String? = null,
        body: String? = null,
    ): ServiceRequestContext {
        val path = if (key != null) "/api/v2/thing?key=$key" else "/api/v2/thing"
        val b = RequestHeaders.builder(method, path).scheme("https").authority("media.example.test")
        if (cookie != null) b.add("cookie", cookie)
        if (bearer != null) b.add("authorization", "Bearer $bearer")
        if (origin != null) b.add("origin", origin)
        if (contentType != null) b.add("content-type", contentType)
        val req = if (body != null) HttpRequest.of(b.build(), HttpData.ofUtf8(body)) else HttpRequest.of(b.build())
        return ServiceRequestContext.builder(req).build()
    }

    private fun sessionCookie(): String {
        val user = getOrCreateUser("viewer", level = 1)
        return "${AuthService.COOKIE_NAME}=${AuthService.createSession(user, "test-agent")}"
    }

    private fun serve(ctx: ServiceRequestContext) = decorator.serve(echoDelegate, ctx, ctx.request())

    @Test
    fun `cookie POST from a foreign origin is 403`() {
        val ctx = request(HttpMethod.POST, cookie = sessionCookie(),
            origin = "https://attacker.example.test", contentType = "application/json", body = "{}")
        assertEquals(HttpStatus.FORBIDDEN, statusOf(serve(ctx)))
    }

    @Test
    fun `cookie POST with text-plain body is 415`() {
        val ctx = request(HttpMethod.POST, cookie = sessionCookie(),
            origin = "https://media.example.test", contentType = "text/plain", body = """{"a":1}""")
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, statusOf(serve(ctx)))
    }

    @Test
    fun `cookie POST with untyped body is 415`() {
        val ctx = request(HttpMethod.POST, cookie = sessionCookie(), body = """{"a":1}""")
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, statusOf(serve(ctx)))
    }

    @Test
    fun `same-origin cookie JSON POST reaches the handler with its body`() {
        val ctx = request(HttpMethod.POST, cookie = sessionCookie(),
            origin = "https://media.example.test", contentType = "application/json", body = """{"a":1}""")
        val resp = serve(ctx).aggregate().join()
        assertEquals(HttpStatus.OK, resp.status())
        assertEquals("""body=[{"a":1}]""", resp.contentUtf8())
    }

    @Test
    fun `cookie DELETE without a body passes and the handler can still read the request`() {
        val ctx = request(HttpMethod.DELETE, cookie = sessionCookie(), origin = "https://media.example.test")
        val resp = serve(ctx).aggregate().join()
        assertEquals(HttpStatus.OK, resp.status())
        assertEquals("body=[]", resp.contentUtf8())
    }

    @Test
    fun `cookie GET is not subject to the gate`() {
        val ctx = request(HttpMethod.GET, cookie = sessionCookie(), origin = "https://attacker.example.test")
        assertEquals(HttpStatus.OK, statusOf(serve(ctx)))
    }

    @Test
    fun `bearer-token POST is not subject to the gate`() {
        val user = getOrCreateUser("viewer", level = 1)
        val token = JwtService.createTokenPair(user, "test").accessToken
        val ctx = request(HttpMethod.POST, bearer = token,
            origin = "https://attacker.example.test", contentType = "text/plain", body = "x")
        assertEquals(HttpStatus.OK, statusOf(serve(ctx)))
    }

    @Test
    fun `device-key POST is not subject to the gate`() {
        val user = getOrCreateUser("viewer", level = 1)
        val raw = "device-test-token-value"
        DeviceToken(
            user_id = user.id!!,
            token_hash = AuthService.hashToken(raw),
            device_name = "Roku",
            created_at = LocalDateTime.now(),
            last_used_at = LocalDateTime.now(),
        ).save()
        val ctx = request(HttpMethod.POST, key = raw, body = "x")
        assertEquals(HttpStatus.OK, statusOf(serve(ctx)))
    }
}
