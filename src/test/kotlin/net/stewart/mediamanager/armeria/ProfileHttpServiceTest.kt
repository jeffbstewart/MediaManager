package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.SessionToken
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.service.PasswordService
import net.stewart.mediamanager.service.UserTitleFlagService
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage for [ProfileHttpService] — the SPA's profile page endpoints.
 * Drives every method through the same [ArmeriaTestBase] context-builder
 * harness used for HomeFeedHttpServiceTest. Bodies for POST endpoints
 * are attached via the [ctxFor] `jsonBody` parameter.
 */
internal class ProfileHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("profile") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = ProfileHttpService()

    @Before
    fun reset() {
        UserTitleFlag.deleteAll()
        SessionToken.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    // ---------------------- /api/v2/profile ----------------------

    @Test
    fun `getProfile returns 401 unauthenticated`() {
        val resp = service.getProfile(ctxFor("/api/v2/profile", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `getProfile echoes the user's own fields`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.getProfile(ctxFor("/api/v2/profile", user = admin))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(admin.id, body.get("id").asLong)
        assertEquals("admin", body.get("username").asString)
        assertEquals(true, body.get("is_admin").asBoolean)
        assertTrue(body.has("live_tv_min_quality"))
        assertTrue(body.has("has_live_tv"))
        assertTrue(body.has("passkeys_enabled"))
    }

    // ---------------------- /api/v2/profile/tv-quality ----------------------

    @Test
    fun `updateTvQuality returns 401 unauthenticated`() {
        val resp = service.updateTvQuality(
            ctxFor("/api/v2/profile/tv-quality", method = HttpMethod.POST,
                user = null, jsonBody = """{"quality": 3}""")
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `updateTvQuality returns 400 when 'quality' is missing from the body`() {
        val resp = service.updateTvQuality(
            ctxFor("/api/v2/profile/tv-quality", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"foo": "bar"}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `updateTvQuality persists the new value, coerced to the 1-5 range`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.updateTvQuality(
            ctxFor("/api/v2/profile/tv-quality", method = HttpMethod.POST,
                user = admin, jsonBody = """{"quality": 99}""")
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val refreshed = AppUser.findById(admin.id!!)!!
        assertEquals(5, refreshed.live_tv_min_quality, "99 coerced to upper bound 5")
    }

    // ---------------------- /api/v2/profile/change-password ----------------------

    @Test
    fun `changePassword returns 401 unauthenticated`() {
        val resp = service.changePassword(
            ctxFor("/api/v2/profile/change-password", method = HttpMethod.POST,
                user = null, jsonBody = "{}")
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `changePassword returns 400 when current_password is missing`() {
        val resp = service.changePassword(
            ctxFor("/api/v2/profile/change-password", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"new_password": "Whatever1234!"}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `changePassword reports ok=false when the current password is wrong`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.changePassword(
            ctxFor("/api/v2/profile/change-password", method = HttpMethod.POST,
                user = admin,
                jsonBody = """{"current_password": "wrong-pw",
                                "new_password": "Excellent1234!"}""")
        )
        // 200 OK with ok=false — the response shape is for the SPA to display.
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(false, body.get("ok").asBoolean)
        assertTrue(body.get("error").asString.contains("incorrect", ignoreCase = true))
    }

    @Test
    fun `changePassword reports ok=false with the first violation when new password is invalid`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.changePassword(
            ctxFor("/api/v2/profile/change-password", method = HttpMethod.POST,
                user = admin,
                // Current password is the value getOrCreateUser uses.
                jsonBody = """{"current_password": "Test1234!@#",
                                "new_password": "x"}""")
        )
        val body = readJsonObject(resp)
        assertEquals(false, body.get("ok").asBoolean)
        // First violation in PasswordService.validate is the length check.
        assertTrue(body.get("error").asString.contains("at least"))
    }

    @Test
    fun `changePassword saves the new hash and clears must_change_password`() {
        val admin = getOrCreateUser("admin", level = 2).apply {
            must_change_password = true
            save()
        }
        val resp = service.changePassword(
            ctxFor("/api/v2/profile/change-password", method = HttpMethod.POST,
                user = admin,
                jsonBody = """{"current_password": "Test1234!@#",
                                "new_password": "Excellent1234!"}""")
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val refreshed = AppUser.findById(admin.id!!)!!
        assertTrue(PasswordService.verify("Excellent1234!", refreshed.password_hash))
        assertFalse(refreshed.must_change_password,
            "must_change_password should be cleared after a successful change")
    }

    // ---------------------- /api/v2/profile/sessions ----------------------

    @Test
    fun `listSessions returns 401 unauthenticated`() {
        val resp = service.listSessions(ctxFor("/api/v2/profile/sessions", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `listSessions returns the user's browser SessionTokens`() {
        val admin = getOrCreateUser("admin", level = 2)
        val now = LocalDateTime.now()
        SessionToken(
            user_id = admin.id!!,
            token_hash = "abc",
            user_agent = "Test/1.0",
            created_at = now,
            last_used_at = now,
            expires_at = now.plusDays(30),
        ).save()

        val resp = service.listSessions(ctxFor("/api/v2/profile/sessions", user = admin))
        val sessions = readJsonArrayOf(resp, "sessions")
        assertEquals(1, sessions.size)
        val s = sessions.single()
        assertEquals("browser", s.get("type").asString)
        assertEquals("Test/1.0", s.get("user_agent").asString)
        assertEquals(false, s.get("is_current").asBoolean,
            "no cookie on the test ctx → no session matches as current")
    }

    @Test
    fun `listSessions marks the current session via the cookie hash`() {
        val admin = getOrCreateUser("admin", level = 2)
        val rawToken = "raw-cookie-token-${java.util.UUID.randomUUID()}"
        val tokenHash = net.stewart.mediamanager.service.AuthService.hashToken(rawToken)
        val now = LocalDateTime.now()
        SessionToken(
            user_id = admin.id!!, token_hash = tokenHash,
            user_agent = "Test/1.0", created_at = now,
            last_used_at = now, expires_at = now.plusDays(30),
        ).save()

        val resp = service.listSessions(ctxFor(
            "/api/v2/profile/sessions",
            user = admin,
            cookieHeader = "${net.stewart.mediamanager.service.AuthService.COOKIE_NAME}=$rawToken",
        ))
        val s = readJsonArrayOf(resp, "sessions").single()
        assertEquals(true, s.get("is_current").asBoolean,
            "cookie hash matches the SessionToken hash → is_current = true")
    }

    @Test
    fun `revokeSession returns 401 unauthenticated`() {
        val resp = service.revokeSession(
            ctxFor("/api/v2/profile/sessions/1", method = HttpMethod.DELETE, user = null),
            sessionId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `revokeSession deletes the SessionToken when it belongs to the calling user`() {
        val admin = getOrCreateUser("admin", level = 2)
        val token = SessionToken(
            user_id = admin.id!!, token_hash = "h",
            user_agent = "Test", created_at = LocalDateTime.now(),
            last_used_at = LocalDateTime.now(),
            expires_at = LocalDateTime.now().plusDays(30),
        ).apply { save() }

        val resp = service.revokeSession(
            ctxFor("/api/v2/profile/sessions/${token.id}",
                method = HttpMethod.DELETE, user = admin),
            sessionId = token.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(0, SessionToken.findAll().size)
    }

    @Test
    fun `revokeOtherSessions clears every browser session except the current one`() {
        val admin = getOrCreateUser("admin", level = 2)
        val now = LocalDateTime.now()
        val rawToken = "raw-cookie-${java.util.UUID.randomUUID()}"
        val currentHash = net.stewart.mediamanager.service.AuthService.hashToken(rawToken)
        SessionToken(
            user_id = admin.id!!, token_hash = currentHash,
            user_agent = "Current", created_at = now,
            last_used_at = now, expires_at = now.plusDays(30),
        ).save()
        SessionToken(
            user_id = admin.id!!, token_hash = "stale-1",
            user_agent = "Old", created_at = now,
            last_used_at = now, expires_at = now.plusDays(30),
        ).save()
        SessionToken(
            user_id = admin.id!!, token_hash = "stale-2",
            user_agent = "Older", created_at = now,
            last_used_at = now, expires_at = now.plusDays(30),
        ).save()

        val resp = service.revokeOtherSessions(ctxFor(
            "/api/v2/profile/sessions/revoke-others",
            method = HttpMethod.POST,
            user = admin,
            cookieHeader = "${net.stewart.mediamanager.service.AuthService.COOKIE_NAME}=$rawToken",
        ))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val remaining = SessionToken.findAll()
        assertEquals(1, remaining.size)
        assertEquals(currentHash, remaining.single().token_hash,
            "the current session is preserved")
    }

    // ---------------------- /api/v2/profile/passkeys ----------------------

    @Test
    fun `passkeyRegistrationOptions returns 400 when WebAuthn is not configured`() {
        // No webauthn_rp_id config → WebAuthnService.generateRegistrationOptions
        // throws IllegalStateException, the endpoint returns 400.
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.passkeyRegistrationOptions(
            ctxFor("/api/v2/profile/passkeys/registration-options",
                method = HttpMethod.POST, user = admin)
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `passkeyRegister returns 400 when challenge field is missing`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.passkeyRegister(ctxFor(
            "/api/v2/profile/passkeys/register",
            method = HttpMethod.POST, user = admin,
            jsonBody = """{"credential": {}}""",
        ))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `passkeyRegister returns 400 when credential field is missing`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.passkeyRegister(ctxFor(
            "/api/v2/profile/passkeys/register",
            method = HttpMethod.POST, user = admin,
            jsonBody = """{"challenge": "abc"}""",
        ))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `listPasskeys returns 401 unauthenticated`() {
        val resp = service.listPasskeys(ctxFor("/api/v2/profile/passkeys", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `listPasskeys returns an empty list when the user has no passkeys`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.listPasskeys(ctxFor("/api/v2/profile/passkeys", user = admin))
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(0, readJsonArrayOf(resp, "passkeys").size)
    }

    @Test
    fun `deletePasskey returns 404 when the credential id is unknown`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.deletePasskey(
            ctxFor("/api/v2/profile/passkeys/9999",
                method = HttpMethod.DELETE, user = admin),
            credentialId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    // ---------------------- /api/v2/profile/hidden-titles ----------------------

    @Test
    fun `hiddenTitles returns 401 unauthenticated`() {
        val resp = service.hiddenTitles(ctxFor("/api/v2/profile/hidden-titles", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `hiddenTitles returns the user's UserTitleFlag(HIDDEN) titles, sorted by name`() {
        val admin = getOrCreateUser("admin", level = 2)
        val zebra = Title(name = "Zebra", media_type = MediaType.MOVIE.name,
            sort_name = "zebra").apply { save() }
        val alpha = Title(name = "Alpha", media_type = MediaType.MOVIE.name,
            sort_name = "alpha").apply { save() }
        Title(name = "NotHidden", media_type = MediaType.MOVIE.name,
            sort_name = "nothidden").apply { save() }

        UserTitleFlagService.setFlagForUser(admin.id!!, zebra.id!!, UserFlagType.HIDDEN)
        UserTitleFlagService.setFlagForUser(admin.id!!, alpha.id!!, UserFlagType.HIDDEN)

        val resp = service.hiddenTitles(ctxFor("/api/v2/profile/hidden-titles", user = admin))
        val titles = readJsonArrayOf(resp, "titles")
        assertEquals(2, titles.size)
        assertEquals("Alpha", titles[0].get("title_name").asString)
        assertEquals("Zebra", titles[1].get("title_name").asString)
    }

    @Test
    fun `unhideTitle clears the HIDDEN flag for the calling user only`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = Title(name = "Hide Me", media_type = MediaType.MOVIE.name,
            sort_name = "hide me").apply { save() }
        UserTitleFlagService.setFlagForUser(admin.id!!, title.id!!, UserFlagType.HIDDEN)
        assertEquals(1, UserTitleFlagService.getHiddenTitleIdsForUser(admin.id!!).size)

        val resp = service.unhideTitle(
            ctxFor("/api/v2/profile/hidden-titles/${title.id}",
                method = HttpMethod.DELETE, user = admin),
            titleId = title.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(0, UserTitleFlagService.getHiddenTitleIdsForUser(admin.id!!).size)
    }
}
