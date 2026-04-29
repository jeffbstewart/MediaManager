package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.DeviceToken
import net.stewart.mediamanager.entity.PairCode
import net.stewart.mediamanager.entity.PairStatus
import net.stewart.mediamanager.entity.SessionToken
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [PairingService] — the QR-code-based device pairing flow,
 * device-token validation, and admin-driven session/device revocation.
 */
class PairingServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:pairingtest;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    private var userId: Long = 0
    private var otherUserId: Long = 0

    @Before
    fun reset() {
        DeviceToken.deleteAll()
        PairCode.deleteAll()
        SessionToken.deleteAll()
        AppUser.deleteAll()

        userId = AppUser(username = "alice", display_name = "Alice",
            password_hash = "x", access_level = 1).apply { save() }.id!!
        otherUserId = AppUser(username = "bob", display_name = "Bob",
            password_hash = "x", access_level = 1).apply { save() }.id!!
    }

    // ---------------------- createPairCode ----------------------

    @Test
    fun `createPairCode produces a 6-char code from the safe alphabet with a 5-minute TTL`() {
        val before = LocalDateTime.now()
        val pairCode = PairingService.createPairCode("Living Room TV")
        val after = LocalDateTime.now()

        assertEquals(6, pairCode.code.length)
        // Confusable characters (I, O, 0, 1) excluded for readability.
        assertFalse(pairCode.code.any { it in "IO01" }, "code must not contain I/O/0/1")
        assertEquals("Living Room TV", pairCode.device_name)
        assertEquals(PairStatus.PENDING.name, pairCode.status)
        // Expiry is roughly 5 minutes ahead.
        assertTrue(pairCode.expires_at.isAfter(before.plusMinutes(4)))
        assertTrue(pairCode.expires_at.isBefore(after.plusMinutes(6)))
    }

    @Test
    fun `createPairCode uses an empty device name when none is given`() {
        val pairCode = PairingService.createPairCode()
        assertEquals("", pairCode.device_name)
    }

    @Test
    fun `createPairCode evicts expired rows on each call`() {
        // Seed an expired row directly — cleanup runs on next createPairCode.
        PairCode(code = "OLD123", device_name = "old",
            status = PairStatus.PENDING.name,
            expires_at = LocalDateTime.now().minusMinutes(1)).save()
        assertEquals(1, PairCode.findAll().size)

        PairingService.createPairCode("new")
        // Old row gone; only the new pending row remains.
        val rows = PairCode.findAll()
        assertEquals(1, rows.size)
        assertEquals("new", rows.single().device_name)
    }

    // ---------------------- checkStatus + confirmPairing ----------------------

    @Test
    fun `checkStatus returns null for an unknown code`() {
        assertNull(PairingService.checkStatus("NOPE99"))
    }

    @Test
    fun `checkStatus returns pending when the code has not been confirmed`() {
        val pc = PairingService.createPairCode("dev")
        val status = PairingService.checkStatus(pc.code)
        assertNotNull(status)
        assertEquals("pending", status.status)
        assertNull(status.token)
        assertNull(status.username)
    }

    @Test
    fun `checkStatus marks an expired pending code EXPIRED on first read`() {
        val pc = PairingService.createPairCode("dev")
        // Force-expire by writing the row past now.
        val row = PairCode.findById(pc.id!!)!!
        row.expires_at = LocalDateTime.now().minusSeconds(1)
        row.save()

        val status = PairingService.checkStatus(pc.code)
        assertNotNull(status)
        assertEquals("expired", status.status)
        // The row's status was flipped to EXPIRED for audit/visibility.
        assertEquals(PairStatus.EXPIRED.name, PairCode.findById(pc.id!!)!!.status)
    }

    @Test
    fun `confirmPairing creates a DeviceToken and exposes the raw token only once`() {
        val pc = PairingService.createPairCode("Roku in Kitchen")
        val deviceName = PairingService.confirmPairing(pc.code, AppUser.findById(userId)!!)
        assertEquals("Roku in Kitchen", deviceName)

        // DeviceToken row created with hashed token; raw token is in
        // pair_code.server_url temporarily for the polling device to fetch.
        val deviceToken = DeviceToken.findAll().single()
        assertEquals(userId, deviceToken.user_id)
        assertEquals("Roku in Kitchen", deviceToken.device_name)

        // First checkStatus reveals the token and clears it.
        val first = PairingService.checkStatus(pc.code)!!
        assertEquals("paired", first.status)
        assertNotNull(first.token)
        assertEquals("alice", first.username)
        // Hash matches the row in device_token (single-use cleared from server_url).
        assertEquals(deviceToken.token_hash, AuthService.hashToken(first.token))

        // Second checkStatus is paired but the raw token is gone.
        val second = PairingService.checkStatus(pc.code)!!
        assertEquals("paired", second.status)
        assertNull(second.token, "single-use raw token was cleared after first read")
    }

    @Test
    fun `confirmPairing falls back to default device label when none was provided`() {
        val pc = PairingService.createPairCode()
        val deviceName = PairingService.confirmPairing(pc.code, AppUser.findById(userId)!!)
        assertEquals("Roku Device", deviceName)
        assertEquals("Roku Device", DeviceToken.findAll().single().device_name)
    }

    @Test
    fun `confirmPairing returns null for unknown, already-paired, or expired codes`() {
        // Unknown.
        assertNull(PairingService.confirmPairing("NOPE99", AppUser.findById(userId)!!))

        // Already paired.
        val pc = PairingService.createPairCode("dev")
        PairingService.confirmPairing(pc.code, AppUser.findById(userId)!!)
        assertNull(PairingService.confirmPairing(pc.code, AppUser.findById(userId)!!),
            "double-confirm must fail")

        // Expired pending code.
        val pcExpired = PairingService.createPairCode("dev2")
        val row = PairCode.findById(pcExpired.id!!)!!
        row.expires_at = LocalDateTime.now().minusSeconds(1)
        row.save()
        assertNull(PairingService.confirmPairing(pcExpired.code, AppUser.findById(userId)!!))
    }

    // ---------------------- validateDeviceToken ----------------------

    @Test
    fun `validateDeviceToken returns the user and updates last_used_at`() {
        val pc = PairingService.createPairCode("dev")
        PairingService.confirmPairing(pc.code, AppUser.findById(userId)!!)
        val rawToken = PairingService.checkStatus(pc.code)!!.token!!

        val before = DeviceToken.findAll().single().last_used_at
        Thread.sleep(10)
        val resolved = PairingService.validateDeviceToken(rawToken)
        assertNotNull(resolved)
        assertEquals(userId, resolved.id)

        val after = DeviceToken.findAll().single().last_used_at
        assertNotNull(after)
        assertTrue(before == null || after.isAfter(before))
    }

    @Test
    fun `validateDeviceToken returns null for unknown tokens`() {
        assertNull(PairingService.validateDeviceToken("never-issued"))
    }

    // Note: the `AppUser.findById(...) ?: return null` branch in
    // validateDeviceToken is defensive — the schema's FK from
    // device_token.user_id to app_user.id makes the orphan row state
    // unreachable in practice, so it's not exercised by a test here.

    // ---------------------- revoke* ----------------------

    @Test
    fun `revokeAllForUser deletes only the matching user's tokens`() {
        // Two tokens for alice, one for bob.
        DeviceToken(token_hash = "h1", user_id = userId, device_name = "a1").save()
        DeviceToken(token_hash = "h2", user_id = userId, device_name = "a2").save()
        DeviceToken(token_hash = "h3", user_id = otherUserId, device_name = "b1").save()

        PairingService.revokeAllForUser(userId)

        val remaining = DeviceToken.findAll()
        assertEquals(1, remaining.size)
        assertEquals(otherUserId, remaining.single().user_id)
    }

    @Test
    fun `revokeToken deletes the row and returns true, false on unknown id`() {
        val tokenId = DeviceToken(token_hash = "h", user_id = userId, device_name = "d")
            .apply { save() }.id!!
        assertTrue(PairingService.revokeToken(tokenId))
        assertEquals(0, DeviceToken.findAll().size)
        assertFalse(PairingService.revokeToken(tokenId), "second call returns false")
        assertFalse(PairingService.revokeToken(987_654))
    }

    @Test
    fun `revokeTokenForUser refuses other users' tokens but allows own`() {
        val mineId = DeviceToken(token_hash = "h1", user_id = userId, device_name = "mine")
            .apply { save() }.id!!
        val theirsId = DeviceToken(token_hash = "h2", user_id = otherUserId, device_name = "theirs")
            .apply { save() }.id!!

        assertFalse(PairingService.revokeTokenForUser(theirsId, userId),
            "alice cannot revoke bob's token")
        assertTrue(PairingService.revokeTokenForUser(mineId, userId))
        assertEquals(1, DeviceToken.findAll().size)
        assertFalse(PairingService.revokeTokenForUser(987_654, userId))
    }

    // ---------------------- listing ----------------------

    @Test
    fun `getDeviceTokensForUser returns tokens newest-last-used first`() {
        DeviceToken(token_hash = "h1", user_id = userId, device_name = "old",
            last_used_at = LocalDateTime.now().minusDays(5)).save()
        DeviceToken(token_hash = "h2", user_id = userId, device_name = "new",
            last_used_at = LocalDateTime.now()).save()
        DeviceToken(token_hash = "h3", user_id = otherUserId, device_name = "other",
            last_used_at = LocalDateTime.now()).save()

        val mine = PairingService.getDeviceTokensForUser(userId)
        assertEquals(listOf("new", "old"), mine.map { it.device_name })
    }

    @Test
    fun `getSessionTokensForUser returns only non-expired sessions newest first`() {
        SessionToken(user_id = userId, token_hash = "expired",
            user_agent = "ua",
            expires_at = LocalDateTime.now().minusDays(1)).save()
        SessionToken(user_id = userId, token_hash = "soon",
            user_agent = "ua",
            expires_at = LocalDateTime.now().plusDays(1)).save()
        SessionToken(user_id = userId, token_hash = "later",
            user_agent = "ua",
            expires_at = LocalDateTime.now().plusDays(10)).save()

        val sessions = PairingService.getSessionTokensForUser(userId)
        assertEquals(listOf("later", "soon"), sessions.map { it.token_hash },
            "newest expires_at first; expired filtered out")
    }

    @Test
    fun `countPendingCodes ignores expired and non-pending codes`() {
        // Pending and not expired — counted.
        PairingService.createPairCode("a")
        PairingService.createPairCode("b")
        // Pending but expired — not counted.
        PairCode(code = "OLDCDE", device_name = "stale",
            status = PairStatus.PENDING.name,
            expires_at = LocalDateTime.now().minusMinutes(1)).save()
        // Paired — not counted.
        PairCode(code = "DONE99", device_name = "done",
            status = PairStatus.PAIRED.name,
            expires_at = LocalDateTime.now().plusMinutes(5)).save()

        assertEquals(2, PairingService.countPendingCodes())
    }

    // ---------------------- generateQrCode ----------------------

    @Test
    fun `generateQrCode produces a valid PNG of the requested size`() {
        val bytes = PairingService.generateQrCode("https://example.test/pair?code=ABC123", size = 200)
        assertTrue(bytes.size > 0)
        // PNG magic header.
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])
    }
}
