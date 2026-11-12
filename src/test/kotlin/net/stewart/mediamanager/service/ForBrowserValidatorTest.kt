package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.*

class ForBrowserValidatorTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:forbrowsertest;DB_CLOSE_DELAY=-1"
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
    private lateinit var validator: ForBrowserValidator

    @Before
    fun setup() {
        AppConfig.deleteAll()
        testClock = TestClock(LocalDateTime.of(2025, 6, 15, 10, 0, 0))
        validator = ForBrowserValidator(testClock)
    }

    @After
    fun teardown() {
        // nothing to restore — validator is not started in these tests
    }

    @Test
    fun `isDue returns true when no config row exists`() {
        assertTrue(validator.isDue())
    }

    @Test
    fun `isDue returns true when timestamp is older than 24 hours`() {
        val oldTime = testClock.now().minusHours(25)
        AppConfig(
            config_key = "forbrowser_validator_last_run",
            config_val = oldTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ).save()

        assertTrue(validator.isDue())
    }

    @Test
    fun `isDue returns false when timestamp is less than 24 hours old`() {
        val recentTime = testClock.now().minusHours(12)
        AppConfig(
            config_key = "forbrowser_validator_last_run",
            config_val = recentTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ).save()

        assertFalse(validator.isDue())
    }

    @Test
    fun `isDue returns true when timestamp is malformed`() {
        AppConfig(
            config_key = "forbrowser_validator_last_run",
            config_val = "not-a-timestamp"
        ).save()

        assertTrue(validator.isDue())
    }

    @Test
    fun `isDue returns false when timestamp is exactly 23 hours old`() {
        val time = testClock.now().minusHours(23)
        AppConfig(
            config_key = "forbrowser_validator_last_run",
            config_val = time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ).save()

        assertFalse(validator.isDue())
    }

    @Test
    fun `isDue returns true when timestamp is exactly 24 hours old`() {
        val time = testClock.now().minusHours(24)
        AppConfig(
            config_key = "forbrowser_validator_last_run",
            config_val = time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ).save()

        assertTrue(validator.isDue())
    }

    @Test
    fun `recordCompletion creates config row when none exists`() {
        assertNull(AppConfig.findAll().firstOrNull { it.config_key == "forbrowser_validator_last_run" })

        validator.recordCompletion()

        val row = AppConfig.findAll().firstOrNull { it.config_key == "forbrowser_validator_last_run" }
        assertNotNull(row)
        assertEquals(
            testClock.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            row.config_val
        )
    }

    @Test
    fun `recordCompletion updates existing config row`() {
        AppConfig(
            config_key = "forbrowser_validator_last_run",
            config_val = "2025-01-01T00:00:00"
        ).save()

        validator.recordCompletion()

        val rows = AppConfig.findAll().filter { it.config_key == "forbrowser_validator_last_run" }
        assertEquals(1, rows.size, "Should update existing row, not create a second one")
        assertEquals(
            testClock.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            rows[0].config_val
        )
    }

    @Test
    fun `isDue returns false immediately after recordCompletion`() {
        validator.recordCompletion()
        assertFalse(validator.isDue())
    }

    @Test
    fun `isDue returns true after recordCompletion and 24h advance`() {
        validator.recordCompletion()
        assertFalse(validator.isDue())

        testClock.advanceHours(25)
        assertTrue(validator.isDue())
    }
}
