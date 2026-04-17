package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.*
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration tests for [JwtService]'s family-based refresh-token cap.
 *
 * Covers the behavior that fixes the "iOS gets auto-logged-out after rotations"
 * bug: the cap counts distinct login families, not individual rotation rows,
 * so a chatty device can refresh freely without evicting other devices.
 */
class JwtServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:jwttest;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
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

    private lateinit var user: AppUser

    @Before
    fun setup() {
        RefreshToken.deleteAll()
        AppUser.deleteAll()
        user = AppUser(
            username = "testuser",
            display_name = "Test User",
            password_hash = "x",
            access_level = 1
        )
        user.save()
    }

    @Test
    fun `many rotations on one device do not evict other device families`() {
        // Device A (one family) and Device B (another family).
        var deviceAToken = JwtService.createTokenPair(user, "Device A").refreshToken
        val deviceBToken = JwtService.createTokenPair(user, "Device B").refreshToken

        // Simulate Device A refreshing 15 times — more than the cap — within
        // a single family. None of these should count against the cap.
        repeat(15) {
            val result = JwtService.refresh(deviceAToken)
            assertTrue(result is RefreshResult.Success,
                "Device A refresh should succeed on iteration $it, got $result")
            deviceAToken = result.tokenPair.refreshToken
        }

        // Device B's token, untouched through all this, must still refresh.
        val deviceBResult = JwtService.refresh(deviceBToken)
        assertTrue(deviceBResult is RefreshResult.Success,
            "Device B should still refresh after Device A's many rotations, got $deviceBResult")
    }

    @Test
    fun `eleventh login evicts only the oldest family`() {
        // Create 10 logins (10 distinct families).
        val tokens = (1..10).map { i ->
            JwtService.createTokenPair(user, "Device $i").refreshToken
        }

        // 11th login pushes past the 10-family cap; oldest family should go.
        val eleventh = JwtService.createTokenPair(user, "Device 11").refreshToken

        // Oldest family (Device 1) should now fail to refresh.
        val oldestResult = JwtService.refresh(tokens[0])
        assertTrue(oldestResult is RefreshResult.InvalidToken,
            "Oldest family should be revoked; got $oldestResult")

        // Devices 2..10 and the new Device 11 should all still be valid.
        for (i in 1 until tokens.size) {
            val result = JwtService.refresh(tokens[i])
            assertTrue(result is RefreshResult.Success,
                "Device ${i + 1} should still refresh; got $result")
        }
        val eleventhResult = JwtService.refresh(eleventh)
        assertTrue(eleventhResult is RefreshResult.Success,
            "Newest login should refresh; got $eleventhResult")
    }
}
