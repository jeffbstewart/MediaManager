package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.LoginAttempt
import net.stewart.mediamanager.entity.SessionToken
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AuthService] — login (with rate-limit / lockout), session
 * lifecycle (create / validate / revoke / cleanup), and the in-memory
 * token cache helpers.
 *
 * BCrypt at cost 12 is slow (~250 ms per call). The test reuses a single
 * hashed password fixture and exercises the password-bearing paths
 * sparingly — most session-token and rate-limit tests poke DB rows
 * directly to keep the suite fast.
 */
class AuthServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource
        private lateinit var hashedPassword: String
        private const val PLAINTEXT = "correct-horse-battery-staple"

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:authservicetest;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
            // Hash once and share — BCrypt cost 12 is the slowest single
            // operation in this suite.
            hashedPassword = PasswordService.hash(PLAINTEXT)
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    private var userId: Long = 0
    private val clock = TestClock(LocalDateTime.of(2026, 3, 1, 12, 0, 0))

    @After
    fun restoreClock() {
        AuthService.clock = SystemClock
    }

    @Before
    fun reset() {
        AuthService.clock = clock
        SessionToken.deleteAll()
        LoginAttempt.deleteAll()
        AppUser.deleteAll()
        AuthService.clearTokenCache()
        AuthService.invalidateHasUsersCache()

        val u = AppUser(
            username = "alice",
            display_name = "Alice",
            password_hash = hashedPassword,
            access_level = 2
        )
        u.save()
        userId = u.id!!
    }

    // ---------------------- pure helpers ----------------------

    @Test
    fun `hashToken produces stable 64-char SHA-256 hex`() {
        val a = AuthService.hashToken("abc")
        val b = AuthService.hashToken("abc")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
        assertFalse(a == AuthService.hashToken("abd"), "different inputs hash to different outputs")
    }

    @Test
    fun `maskUsername hides the middle and clamps very long inputs`() {
        // Direct call — internal but available within the same package.
        assertEquals("***", maskUsername("ab"))
        assertEquals("***", maskUsername("abc"))
        // 4-char username: keep first 2 + last 2, no stars in between.
        assertEquals("alce", maskUsername("alce"))
        // 6-char: 2 + 2 stars + 2.
        assertEquals("al**ce", maskUsername("alalce"))
        // Over 30 chars: truncated to 30 then masked.
        val raw = "a".repeat(40)
        val masked = maskUsername(raw)
        assertTrue(masked.length <= 30, "masked length should be capped")
        assertTrue(masked.startsWith("aa"))
    }

    @Test
    fun `buildSessionCookieHeader sets HttpOnly SameSite path and respects Secure`() {
        val plain = AuthService.buildSessionCookieHeader("tok", secure = false)
        assertTrue(plain.startsWith("mm_session=tok; "))
        assertTrue("HttpOnly" in plain)
        assertTrue("SameSite=Lax" in plain)
        assertTrue("Path=/" in plain)
        assertFalse("Secure" in plain)
        // Max-Age = 30 days * 24 * 60 * 60.
        assertTrue("Max-Age=2592000" in plain)

        val secure = AuthService.buildSessionCookieHeader("tok", secure = true)
        assertTrue(secure.endsWith("; Secure"))
    }

    @Test
    fun `buildExpireSessionCookieHeader uses Max-Age=0 and no Secure`() {
        val expire = AuthService.buildExpireSessionCookieHeader()
        assertTrue("Max-Age=0" in expire)
        assertTrue("HttpOnly" in expire)
        // No "Secure" marker — clearing the cookie doesn't need it.
        assertFalse("Secure" in expire)
    }

    // ---------------------- hasUsers cache ----------------------

    @Test
    fun `hasUsers caches the result and invalidates on demand`() {
        // Fresh fixture has one user; cache miss returns true.
        assertTrue(AuthService.hasUsers())

        // Wipe users directly. Without invalidation the cached `true`
        // should still be returned — proving the cache is in effect.
        AppUser.deleteAll()
        assertTrue(AuthService.hasUsers(), "stale cache returns true even though DB has zero users")

        AuthService.invalidateHasUsersCache()
        assertFalse(AuthService.hasUsers())
    }

    // ---------------------- login ----------------------

    @Test
    fun `login succeeds with a correct password and records a successful attempt`() {
        val result = AuthService.login("alice", PLAINTEXT, "203.0.113.4")
        assertTrue(result is LoginResult.Success)
        val attempt = LoginAttempt.findAll().single()
        assertTrue(attempt.success)
        assertEquals("203.0.113.4", attempt.ip_address)
    }

    @Test
    fun `login fails with a wrong password and records a failed attempt`() {
        val result = AuthService.login("alice", "wrong", "203.0.113.4")
        assertTrue(result is LoginResult.Failed)
        val attempt = LoginAttempt.findAll().single()
        assertFalse(attempt.success)
    }

    @Test
    fun `login fails for unknown usernames without leaking that fact`() {
        // Should still record an attempt with success=false. Returns Failed,
        // not RateLimited or some other distinguishable state.
        val result = AuthService.login("ghost", "any", "203.0.113.4")
        assertTrue(result is LoginResult.Failed)
        val attempt = LoginAttempt.findAll().single()
        assertFalse(attempt.success)
        assertEquals("ghost", attempt.username)
    }

    @Test
    fun `login on a locked account returns Failed and does not check the password`() {
        val u = AppUser.findById(userId)!!
        u.locked = true
        u.save()
        val result = AuthService.login("alice", PLAINTEXT, "203.0.113.4")
        assertTrue(result is LoginResult.Failed,
            "locked accounts must not be admitted even with correct password")
    }

    // ---------------------- throttling ----------------------

    private fun seedFailures(n: Int, username: String, ip: String, at: LocalDateTime = clock.now()) {
        repeat(n) {
            LoginAttempt(username = username, ip_address = ip, attempted_at = at, success = false).save()
        }
    }

    private fun advanceSeconds(s: Long) = clock.set(clock.now().plusSeconds(s))

    @Test
    fun `login rate-limits after too many recent failures from the same IP`() {
        // Five failures from this IP (against other usernames) — the sixth
        // attempt from the IP waits out a 30-second cooldown.
        val ip = "203.0.113.99"
        seedFailures(5, "someone-else", ip, at = clock.now().minusSeconds(5))
        val result = AuthService.login("alice", PLAINTEXT, ip)
        assertTrue(result is LoginResult.RateLimited)
        assertTrue(result.retryAfterSeconds in 1..30)
    }

    @Test
    fun `IP failures throttle that IP but do not lock out the account it targets`() {
        val noisyIp = "203.0.113.99"
        seedFailures(20, "random-guess", noisyIp)

        assertTrue(AuthService.login("alice", PLAINTEXT, noisyIp) is LoginResult.RateLimited)
        // The same account from a different IP is unaffected.
        assertTrue(AuthService.login("alice", PLAINTEXT, "203.0.113.4") is LoginResult.Success)
        assertFalse(AppUser.findById(userId)!!.locked)
    }

    @Test
    fun `username failures throttle the account from every IP`() {
        seedFailures(5, "Alice", "203.0.113.100")
        // Case-insensitive, and applies to a fresh IP.
        val result = AuthService.login("alice", PLAINTEXT, "203.0.113.4")
        assertTrue(result is LoginResult.RateLimited)
    }

    @Test
    fun `many failures cool off temporarily and never set the locked flag`() {
        val ip = "203.0.113.100"
        seedFailures(40, "alice", ip)

        val throttled = AuthService.login("alice", "wrong", ip)
        assertTrue(throttled is LoginResult.RateLimited)
        assertEquals(AuthService.RATE_LIMIT_MAX_COOLDOWN_SECONDS + 1, throttled.retryAfterSeconds)
        assertFalse(AppUser.findById(userId)!!.locked,
            "automatic throttling must not set the administrative locked flag")

        // Once the failures age out of the window, the correct password works again.
        clock.advance(16)
        assertTrue(AuthService.login("alice", PLAINTEXT, "203.0.113.4") is LoginResult.Success)
    }

    @Test
    fun `cooldown grows exponentially with further failures`() {
        val ip = "203.0.113.100"
        seedFailures(5, "alice", ip)
        assertEquals(31, (AuthService.login("alice", "wrong", ip) as LoginResult.RateLimited).retryAfterSeconds)

        advanceSeconds(31)
        assertTrue(AuthService.login("alice", "wrong", ip) is LoginResult.Failed, "cooldown expired")
        // Six failures now: 60-second cooldown from the latest.
        assertEquals(61, (AuthService.login("alice", "wrong", ip) as LoginResult.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `daily cap holds until failures age out of 24 hours`() {
        // Spread so none fall in the 15-minute window, but 100 within 24h.
        seedFailures(AuthService.DAILY_FAILURE_CAP, "alice", "203.0.113.100", at = clock.now().minusHours(2))
        assertTrue(AuthService.login("alice", PLAINTEXT, "203.0.113.4") is LoginResult.RateLimited)

        clock.advanceHours(23)
        assertTrue(AuthService.login("alice", PLAINTEXT, "203.0.113.4") is LoginResult.Success)
    }

    @Test
    fun `throttled attempts are not recorded`() {
        seedFailures(5, "alice", "203.0.113.100")
        AuthService.login("alice", "wrong", "203.0.113.100")
        assertEquals(5, LoginAttempt.findAll().size)
    }

    @Test
    fun `parallel bad attempts cannot exceed the threshold and the lockout expires`() {
        // Many concurrent wrong-password logins for one account, each from
        // its own IP so only the per-username limit is in play. Without an
        // atomic check-and-reserve, every request would pass the check
        // during the ~250ms password hash.
        val parallelism = 25
        val pool = Executors.newFixedThreadPool(parallelism)
        val start = CountDownLatch(1)
        try {
            val futures = (1..parallelism).map { i ->
                pool.submit<LoginResult> {
                    start.await()
                    AuthService.login("alice", "wrong-$i", "198.51.100.$i")
                }
            }
            start.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }

            val failed = results.count { it is LoginResult.Failed }
            val throttled = results.count { it is LoginResult.RateLimited }
            assertEquals(AuthService.THROTTLE_THRESHOLD, failed,
                "exactly the threshold may reach the password check")
            assertEquals(parallelism - AuthService.THROTTLE_THRESHOLD, throttled)
            assertEquals(AuthService.THROTTLE_THRESHOLD, LoginAttempt.findAll().size)
        } finally {
            pool.shutdownNow()
        }

        assertFalse(AppUser.findById(userId)!!.locked)
        assertTrue(AuthService.login("alice", PLAINTEXT, "203.0.113.4") is LoginResult.RateLimited)
        // The 30-second cool-off expires; the real owner gets back in.
        advanceSeconds(31)
        assertTrue(AuthService.login("alice", PLAINTEXT, "203.0.113.4") is LoginResult.Success)
    }

    @Test
    fun `successful login is recorded as a success`() {
        AuthService.login("alice", PLAINTEXT, "203.0.113.4")
        val attempt = LoginAttempt.findAll().single()
        assertTrue(attempt.success)
        assertEquals(clock.now(), attempt.attempted_at)
    }

    // ---------------------- session create / validate / revoke ----------------------

    @Test
    fun `createSession persists a hashed token and validateCookieToken round-trips`() {
        val user = AppUser.findById(userId)!!
        val token = AuthService.createSession(user, "TestUA/1.0")
        assertNotNull(token)
        val tokenRow = SessionToken.findAll().single()
        // Token in DB is hashed, never the raw value.
        assertEquals(AuthService.hashToken(token), tokenRow.token_hash)
        assertEquals("TestUA/1.0", tokenRow.user_agent)
        // Round-trip via validateCookieToken.
        val resolved = AuthService.validateCookieToken(token)
        assertNotNull(resolved)
        assertEquals(userId, resolved.id)
    }

    @Test
    fun `validateCookieToken returns null for unknown and expired tokens`() {
        // Unknown.
        assertNull(AuthService.validateCookieToken("never-issued"))

        // Expired.
        val user = AppUser.findById(userId)!!
        val token = AuthService.createSession(user, "ua")
        val row = SessionToken.findAll().single()
        row.expires_at = LocalDateTime.now().minusMinutes(1)
        row.save()
        AuthService.clearTokenCache() // invalidate any cached entry from the prior validate
        assertNull(AuthService.validateCookieToken(token))
    }

    @Test
    fun `createSession trims the oldest session when the per-user cap is reached`() {
        val user = AppUser.findById(userId)!!
        // Seed 10 existing tokens — hash-1 has the soonest expiry (oldest),
        // hash-10 has the latest.
        for (i in 1..10) {
            SessionToken(
                user_id = userId,
                token_hash = "hash-$i",
                user_agent = "ua",
                expires_at = LocalDateTime.now().plusDays(i.toLong()),
                last_used_at = LocalDateTime.now()
            ).save()
        }
        assertEquals(10, SessionToken.findAll().size)

        // Adding the 11th forces the oldest (hash-1) to be trimmed: drop(9)
        // on a DESC-sorted list keeps the 9 newest and removes the rest.
        AuthService.createSession(user, "new-ua")
        assertEquals(10, SessionToken.findAll().size,
            "session count is capped at maxSessionsPerUser")
        val remainingHashes = SessionToken.findAll().map { it.token_hash }.toSet()
        assertFalse("hash-1" in remainingHashes, "oldest session should have been trimmed")
        assertTrue("hash-2" in remainingHashes, "second-oldest survives the trim")
        assertTrue("hash-10" in remainingHashes, "newest preserved")
    }

    @Test
    fun `revokeSessionByToken deletes the row and returns the user`() {
        val user = AppUser.findById(userId)!!
        val token = AuthService.createSession(user, "ua")
        val revoked = AuthService.revokeSessionByToken(token)
        assertNotNull(revoked)
        assertEquals(userId, revoked.id)
        assertEquals(0, SessionToken.findAll().size)
        // Second call returns null — token is gone.
        assertNull(AuthService.revokeSessionByToken(token))
    }

    @Test
    fun `revokeSession refuses other users' tokens and the caller's own current token`() {
        val user = AppUser.findById(userId)!!
        val tok1 = AuthService.createSession(user, "ua-1")
        val tok2 = AuthService.createSession(user, "ua-2")
        val tokenIds = SessionToken.findAll().map { it.id!! }

        // Different caller — refused.
        val otherUserId = AppUser(username = "bob", display_name = "Bob",
            password_hash = "x").apply { save() }.id!!
        assertFalse(AuthService.revokeSession(tokenIds[0], otherUserId, currentTokenHash = null))

        // Caller's own current token — refused.
        val currentHash = AuthService.hashToken(tok1)
        assertFalse(AuthService.revokeSession(tokenIds[0], userId, currentTokenHash = currentHash),
            "you can't revoke your own current session via this path")

        // Caller's *other* token — succeeds.
        val otherTokenId = SessionToken.findAll().single { it.token_hash != currentHash }.id!!
        assertTrue(AuthService.revokeSession(otherTokenId, userId, currentTokenHash = currentHash))
        assertEquals(1, SessionToken.findAll().size)
    }

    @Test
    fun `revokeSession returns false on unknown token id`() {
        assertFalse(AuthService.revokeSession(987_654, userId, currentTokenHash = null))
    }

    @Test
    fun `revokeAllSessionsExceptCurrent keeps the current token only`() {
        val user = AppUser.findById(userId)!!
        val keep = AuthService.createSession(user, "keep")
        AuthService.createSession(user, "drop1")
        AuthService.createSession(user, "drop2")
        assertEquals(3, SessionToken.findAll().size)

        AuthService.revokeAllSessionsExceptCurrent(userId, AuthService.hashToken(keep))
        val remaining = SessionToken.findAll()
        assertEquals(1, remaining.size)
        assertEquals(AuthService.hashToken(keep), remaining.single().token_hash)
    }

    @Test
    fun `revokeAllSessionsExceptCurrent with null currentTokenHash deletes everything`() {
        val user = AppUser.findById(userId)!!
        AuthService.createSession(user, "a")
        AuthService.createSession(user, "b")
        AuthService.revokeAllSessionsExceptCurrent(userId, currentTokenHash = null)
        assertEquals(0, SessionToken.findAll().size)
    }

    @Test
    fun `invalidateUserSessions wipes all tokens for the user`() {
        val user = AppUser.findById(userId)!!
        AuthService.createSession(user, "a")
        AuthService.createSession(user, "b")
        // Other user's token should be untouched.
        val otherUserId = AppUser(username = "bob", display_name = "Bob",
            password_hash = "x").apply { save() }.id!!
        SessionToken(user_id = otherUserId, token_hash = "h", user_agent = "ua",
            expires_at = LocalDateTime.now().plusDays(1)).save()

        AuthService.invalidateUserSessions(userId)

        val remaining = SessionToken.findAll()
        assertEquals(1, remaining.size)
        assertEquals(otherUserId, remaining.single().user_id)
    }

    // ---------------------- token cache eviction ----------------------

    @Test
    fun `evictTokenCache removes a single entry without touching the DB`() {
        val user = AppUser.findById(userId)!!
        val token = AuthService.createSession(user, "ua")
        // Populate the cache.
        AuthService.validateCookieToken(token)
        // Evict — DB row is untouched.
        AuthService.evictTokenCache(AuthService.hashToken(token))
        assertEquals(1, SessionToken.findAll().size)
        // Next validate is a cache miss, but the DB still has the row, so it succeeds.
        assertNotNull(AuthService.validateCookieToken(token))
    }

    @Test
    fun `evictTokenCacheForUser drops all of one user's cached entries`() {
        val user = AppUser.findById(userId)!!
        val tok1 = AuthService.createSession(user, "ua-1")
        val tok2 = AuthService.createSession(user, "ua-2")
        // Populate cache for both.
        AuthService.validateCookieToken(tok1)
        AuthService.validateCookieToken(tok2)
        AuthService.evictTokenCacheForUser(userId)
        // DB rows still present.
        assertEquals(2, SessionToken.findAll().size)
    }

    // ---------------------- countAdmins / cleanup ----------------------

    @Test
    fun `countAdmins counts users at access_level 2 and above`() {
        // One admin from setup. Add a viewer + another admin.
        AppUser(username = "v", display_name = "V", password_hash = "x", access_level = 1).save()
        AppUser(username = "a2", display_name = "A2", password_hash = "x", access_level = 2).save()
        assertEquals(2L, AuthService.countAdmins())
    }

    @Test
    fun `cleanupExpiredTokens deletes tokens past expires_at`() {
        SessionToken(user_id = userId, token_hash = "expired",
            user_agent = "ua",
            expires_at = LocalDateTime.now().minusDays(1)).save()
        SessionToken(user_id = userId, token_hash = "fresh",
            user_agent = "ua",
            expires_at = LocalDateTime.now().plusDays(1)).save()
        AuthService.cleanupExpiredTokens()
        val remaining = SessionToken.findAll().map { it.token_hash }
        assertEquals(listOf("fresh"), remaining)
    }

    @Test
    fun `cleanupOldAttempts deletes login attempts older than 30 days`() {
        LoginAttempt(username = "alice", ip_address = "203.0.113.4",
            attempted_at = LocalDateTime.now().minusDays(31), success = false).save()
        LoginAttempt(username = "alice", ip_address = "203.0.113.4",
            attempted_at = LocalDateTime.now().minusDays(1), success = false).save()
        AuthService.cleanupOldAttempts()
        assertEquals(1, LoginAttempt.findAll().size,
            "only the recent attempt should remain")
    }
}
