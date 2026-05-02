package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.RefreshToken
import net.stewart.mediamanager.entity.SessionToken
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.service.PasswordService
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [AuthRestService] — the SPA-facing auth endpoints
 * (setup, login, refresh, logout, passkey ceremony). Every endpoint
 * (except `discover`) requires the request to look like it transited
 * the TLS-terminating reverse proxy, so [proxyHeaders] is attached to
 * each test ctx.
 */
internal class AuthRestServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource
        private val ipCounter = AtomicInteger(0)

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("authrest") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = AuthRestService()

    @Before
    fun reset() {
        RefreshToken.deleteAll()
        SessionToken.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
        // AuthService caches hasUsers() — invalidate after a manual
        // table wipe so the cache doesn't lie about the empty schema.
        AuthService.invalidateHasUsersCache()
        LegalRequirements.refresh()
    }

    /**
     * Each test gets a unique fake IP so the per-IP rate limiter inside
     * AuthRestService can't carry state from one test into the next when
     * tests run sequentially within a single JVM.
     */
    private fun proxyHeaders(): Map<String, String> = mapOf(
        "x-forwarded-proto" to "https",
        "x-forwarded-for" to "10.0.${ipCounter.incrementAndGet() % 256}.${ipCounter.get() % 256}",
    )

    // ---------------------- discover ----------------------

    @Test
    fun `discover reports setup_required true on a clean database`() {
        val resp = service.discover(ctxFor("/api/v2/auth/discover"))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(true, body.get("setup_required").asBoolean)
        assertTrue(body.has("passkeys_available"))
    }

    @Test
    fun `discover reports setup_required false once a user exists`() {
        getOrCreateUser("admin", level = 2)
        val resp = service.discover(ctxFor("/api/v2/auth/discover"))
        assertEquals(false, readJsonObject(resp).get("setup_required").asBoolean)
    }

    // ---------------------- requireProxy gate ----------------------

    @Test
    fun `setup returns 403 when proxy headers are absent`() {
        val resp = service.setup(ctxFor("/api/v2/auth/setup",
            method = HttpMethod.POST,
            jsonBody = """{"username": "admin", "password": "Excellent1234!"}"""))
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `setup returns 403 when x-forwarded-proto is not https`() {
        val resp = service.setup(ctxFor("/api/v2/auth/setup",
            method = HttpMethod.POST,
            jsonBody = """{"username": "admin", "password": "Excellent1234!"}""",
            extraHeaders = mapOf(
                "x-forwarded-proto" to "http",
                "x-forwarded-for" to "10.0.0.${(ipCounter.incrementAndGet() % 254) + 1}",
            )))
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    // ---------------------- setup ----------------------

    @Test
    fun `setup returns 400 when username is missing`() {
        val resp = service.setup(ctxFor("/api/v2/auth/setup",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{"password": "Excellent1234!"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setup returns 400 when password violates policy (too short)`() {
        val resp = service.setup(ctxFor("/api/v2/auth/setup",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{"username": "admin", "password": "x"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setup returns 409 when users already exist`() {
        getOrCreateUser("existing", level = 2)
        val resp = service.setup(ctxFor("/api/v2/auth/setup",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{"username": "newadmin", "password": "Excellent1234!"}"""))
        assertEquals(HttpStatus.CONFLICT, statusOf(resp))
    }

    @Test
    fun `setup returns 400 when legal URLs are not https or about-blank`() {
        val resp = service.setup(ctxFor("/api/v2/auth/setup",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{"username": "admin",
                            "password": "Excellent1234!",
                            "privacy_policy_url": "ftp://nope"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setup creates the first admin and seeds default legal URLs on the happy path`() {
        val resp = service.setup(ctxFor("/api/v2/auth/setup",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{"username": "admin", "password": "Excellent1234!"}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))

        val body = readJsonObject(resp)
        assertTrue(body.has("access_token"))

        val saved = AppUser.findAll().single()
        assertEquals("admin", saved.username)
        assertEquals(2, saved.access_level)
        assertTrue(PasswordService.verify("Excellent1234!", saved.password_hash))
        // Default legal URLs were seeded.
        val configKeys = AppConfig.findAll().map { it.config_key }.toSet()
        assertTrue("privacy_policy_url" in configKeys)
        assertTrue("web_terms_of_use_url" in configKeys)
    }

    // ---------------------- login ----------------------

    @Test
    fun `login returns 403 with no proxy headers`() {
        val resp = service.login(ctxFor("/api/v2/auth/login",
            method = HttpMethod.POST,
            jsonBody = """{"username": "x", "password": "y"}"""))
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `login returns 400 when username or password is missing`() {
        val resp = service.login(ctxFor("/api/v2/auth/login",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{"username": "x"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `login returns 401 for an unknown user`() {
        val resp = service.login(ctxFor("/api/v2/auth/login",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{"username": "nobody", "password": "Whatever1234!"}"""))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `login returns 401 for the wrong password`() {
        val now = LocalDateTime.now()
        AppUser(username = "loginuser", display_name = "loginuser",
            password_hash = PasswordService.hash("CorrectHorse1234!"),
            access_level = 1, created_at = now, updated_at = now).save()

        val resp = service.login(ctxFor("/api/v2/auth/login",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{"username": "loginuser", "password": "WrongHorse1234!"}"""))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `login happy path returns access_token and password_change_required flag`() {
        val now = LocalDateTime.now()
        AppUser(username = "happyuser", display_name = "Happy User",
            password_hash = PasswordService.hash("CorrectHorse1234!"),
            access_level = 1, created_at = now, updated_at = now).save()

        val resp = service.login(ctxFor("/api/v2/auth/login",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{"username": "happyuser",
                            "password": "CorrectHorse1234!"}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertTrue(body.has("access_token"))
        assertEquals(false, body.get("password_change_required").asBoolean)
    }

    // ---------------------- passkey endpoints ----------------------

    @Test
    fun `passkeyAuthenticationOptions returns 503 when WebAuthn is not configured`() {
        // No webauthn_rp_id set → IllegalStateException → 503.
        val resp = service.passkeyAuthenticationOptions(
            ctxFor("/api/v2/auth/passkey/authentication-options",
                method = HttpMethod.POST, extraHeaders = proxyHeaders())
        )
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf(resp))
    }

    @Test
    fun `passkeyAuthenticate returns 400 when challenge is missing`() {
        val resp = service.passkeyAuthenticate(
            ctxFor("/api/v2/auth/passkey/authenticate",
                method = HttpMethod.POST, extraHeaders = proxyHeaders(),
                jsonBody = """{"credential": {}}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `passkeyAuthenticate returns 400 when credential is missing`() {
        val resp = service.passkeyAuthenticate(
            ctxFor("/api/v2/auth/passkey/authenticate",
                method = HttpMethod.POST, extraHeaders = proxyHeaders(),
                jsonBody = """{"challenge": "abc"}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    // ---------------------- refresh ----------------------

    @Test
    fun `refresh returns 403 with no proxy headers`() {
        val resp = service.refresh(ctxFor("/api/v2/auth/refresh",
            method = HttpMethod.POST, jsonBody = """{}"""))
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `refresh returns 401 when no refresh cookie is attached`() {
        val resp = service.refresh(ctxFor("/api/v2/auth/refresh",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{}"""))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `refresh returns 401 with an invalid refresh token`() {
        val resp = service.refresh(ctxFor("/api/v2/auth/refresh",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            cookieHeader = "mm_refresh=this-is-not-a-valid-token",
            jsonBody = """{}"""))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    // ---------------------- logout ----------------------

    @Test
    fun `logout returns 403 with no proxy headers`() {
        val resp = service.logout(ctxFor("/api/v2/auth/logout",
            method = HttpMethod.POST, jsonBody = """{}"""))
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `logout returns 200 even when no cookies are attached`() {
        val resp = service.logout(ctxFor("/api/v2/auth/logout",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            jsonBody = """{}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(true, readJsonObject(resp).get("ok").asBoolean)
    }

    @Test
    fun `logout clears the cookies on success and returns ok=true`() {
        val resp = service.logout(ctxFor("/api/v2/auth/logout",
            method = HttpMethod.POST, extraHeaders = proxyHeaders(),
            cookieHeader = "mm_refresh=anything; mm_session=alsoanything",
            jsonBody = """{}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}
