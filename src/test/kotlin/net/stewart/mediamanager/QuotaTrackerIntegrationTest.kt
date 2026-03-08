package net.stewart.mediamanager

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.service.QuotaTracker
import net.stewart.mediamanager.service.SystemClock
import net.stewart.mediamanager.service.TestClock
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.*

/**
 * Integration tests for QuotaTracker's daily reset behavior using a controllable TestClock.
 *
 * Stands up an in-memory H2 database with Flyway migrations, then exercises the
 * quota increment/reset logic with time jumps to verify daily boundaries.
 */
class QuotaTrackerIntegrationTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:quotatest;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    private lateinit var testClock: TestClock

    @Before
    fun setup() {
        AppConfig.deleteAll()
        testClock = TestClock(LocalDateTime.of(2025, 1, 1, 12, 0, 0))
        QuotaTracker.clock = testClock
    }

    @After
    fun restore() {
        QuotaTracker.clock = SystemClock
    }

    @Test
    fun `quota resets when clock advances to next day`() {
        // Increment 5 times on Jan 1
        repeat(5) { QuotaTracker.increment() }
        assertEquals(5, QuotaTracker.getStatus().used)

        // Advance to Jan 2
        testClock.advanceDays(1)
        val status = QuotaTracker.getStatus()
        assertEquals(0, status.used, "Quota should reset on new day")
        assertEquals(QuotaTracker.DAILY_LIMIT, status.remaining)
        assertFalse(status.exhausted)
    }

    @Test
    fun `quota does NOT reset on same day`() {
        repeat(3) { QuotaTracker.increment() }

        // Advance 6 hours (still Jan 1, 18:00)
        testClock.advance(360) // 6 hours in minutes
        val status = QuotaTracker.getStatus()
        assertEquals(3, status.used, "Quota should NOT reset on same day")
    }

    @Test
    fun `quota exhaustion at daily limit`() {
        repeat(QuotaTracker.DAILY_LIMIT) { QuotaTracker.increment() }

        val status = QuotaTracker.getStatus()
        assertTrue(status.exhausted)
        assertEquals(0, status.remaining)
        assertEquals(QuotaTracker.DAILY_LIMIT, status.used)

        // One more increment — used exceeds limit but remaining is clamped to 0
        val over = QuotaTracker.increment()
        assertEquals(QuotaTracker.DAILY_LIMIT + 1, over.used)
        assertEquals(0, over.remaining)
        assertTrue(over.exhausted)
    }

    @Test
    fun `exhausted quota resets on new day`() {
        repeat(QuotaTracker.DAILY_LIMIT) { QuotaTracker.increment() }
        assertTrue(QuotaTracker.getStatus().exhausted)

        // Advance to next day
        testClock.advanceDays(1)
        val status = QuotaTracker.getStatus()
        assertFalse(status.exhausted)
        assertEquals(0, status.used)
        assertEquals(QuotaTracker.DAILY_LIMIT, status.remaining)
    }

    @Test
    fun `multiple day transitions`() {
        // Day 1: increment 10
        repeat(10) { QuotaTracker.increment() }
        assertEquals(10, QuotaTracker.getStatus().used)

        // Day 2: reset and increment 20
        testClock.advanceDays(1)
        assertEquals(0, QuotaTracker.getStatus().used)
        repeat(20) { QuotaTracker.increment() }
        assertEquals(20, QuotaTracker.getStatus().used)

        // Day 3: reset
        testClock.advanceDays(1)
        val status = QuotaTracker.getStatus()
        assertEquals(0, status.used)
        assertEquals(QuotaTracker.DAILY_LIMIT, status.remaining)
    }
}
