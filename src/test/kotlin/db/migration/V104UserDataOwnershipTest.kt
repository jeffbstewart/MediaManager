package db.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V104: household data stops referencing users, everything else that
 * references app_user cascades.  Runs the real migrations on a scratch
 * in-memory database, seeding data at V103 so the data moves are covered.
 */
class V104UserDataOwnershipTest {

    companion object {
        private val counter = AtomicInteger()
    }

    private lateinit var ds: HikariDataSource

    @BeforeTest
    fun setup() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:v104-${counter.incrementAndGet()};DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
        })
    }

    @AfterTest
    fun teardown() = ds.close()

    private fun migrate(target: String? = null) {
        val config = Flyway.configure().dataSource(ds)
        if (target != null) config.target(target)
        config.load().migrate()
    }

    private fun <T> sql(block: (Connection) -> T): T = ds.connection.use(block)

    private fun Connection.exec(s: String) = createStatement().use { it.execute(s) }

    private fun Connection.longs(s: String): List<Long> = createStatement().use { st ->
        st.executeQuery(s).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
    }

    private fun Connection.strings(s: String): List<String?> = createStatement().use { st ->
        st.executeQuery(s).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun Connection.columns(table: String): Set<String> = strings(
        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = '${table.uppercase()}'"
    ).filterNotNull().toSet()

    @Test
    fun `every foreign key to app_user cascades`() {
        migrate()
        val rules = sql {
            it.createStatement().use { st ->
                st.executeQuery(
                    """SELECT kcu.TABLE_NAME || '.' || kcu.COLUMN_NAME, rc.DELETE_RULE
                         FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
                         JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS pk
                           ON pk.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME
                          AND pk.CONSTRAINT_SCHEMA = rc.UNIQUE_CONSTRAINT_SCHEMA
                         JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                           ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
                          AND kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
                        WHERE pk.TABLE_NAME = 'APP_USER' AND rc.CONSTRAINT_SCHEMA = SCHEMA()"""
                ).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
            }
        }
        assertTrue(rules.isNotEmpty())
        // A new per-user table must cascade too; a household table must not
        // reference a user at all (V104's header says why).
        assertEquals(emptyMap(), rules.filterValues { it != "CASCADE" })
    }

    @Test
    fun `household tables no longer reference a user`() {
        migrate()
        sql { c ->
            assertFalse("CREATED_BY" in c.columns("tag"))
            assertFalse("USER_ID" in c.columns("amazon_order"))
            val report = c.columns("problem_report")
            assertFalse("USER_ID" in report)
            assertFalse("RESOLVED_BY" in report)
            assertTrue("REPORTER_USERNAME" in report)
            assertTrue("RESOLVER_USERNAME" in report)
        }
    }

    @Test
    fun `before V104 a user with a wish could not be deleted`() {
        // The defect V104 fixes: the admin DeleteUser path failed for any
        // user who had ever used the app.
        migrate(target = "103")
        sql { c ->
            c.exec("""INSERT INTO app_user (id, username, display_name, password_hash, access_level)
                      VALUES (2, 'bob', 'Bob', 'x', 1)""")
            c.exec("INSERT INTO wish_list_item (user_id, wish_type, status) VALUES (2, 'MEDIA', 'ACTIVE')")
            val refused = runCatching { c.exec("DELETE FROM app_user WHERE id = 2") }.isFailure
            assertTrue(refused, "H2 should refuse: wish_list_item.user_id had no delete rule")
        }
        migrate()
        sql { c ->
            c.exec("DELETE FROM app_user WHERE id = 2")
            assertEquals(listOf(0L), c.longs("SELECT COUNT(*) FROM wish_list_item"))
        }
    }

    @Test
    fun `existing data is carried across`() {
        migrate(target = "103")
        sql { c ->
            c.exec("""INSERT INTO app_user (id, username, display_name, password_hash, access_level)
                      VALUES (1, 'alice', 'Alice', 'x', 2), (2, 'bob', 'Bob', 'x', 1)""")
            c.exec("INSERT INTO tag (id, name, created_by) VALUES (1, 'Favorites', 2)")
            c.exec("""INSERT INTO problem_report (id, user_id, description, status, resolved_by)
                      VALUES (1, 2, 'skips at 1:02', 'RESOLVED', 1), (2, 2, 'no audio', 'OPEN', NULL)""")
            // The same order imported by both admins; only bob's copy is linked.
            c.exec("INSERT INTO media_item (id, media_format) VALUES (10, 'BLURAY')")
            c.exec("""INSERT INTO amazon_order (id, user_id, order_id, asin, product_name, product_name_lower,
                                                linked_media_item_id)
                      VALUES (1, 1, '111-1', 'B1', 'Movie', 'movie', NULL),
                             (2, 2, '111-1', 'B1', 'Movie', 'movie', 10),
                             (3, 1, '222-2', 'B2', 'Other', 'other', NULL),
                             (4, 2, '222-2', 'B2', 'Other', 'other', NULL)""")
        }

        migrate()

        sql { c ->
            assertEquals(listOf("Favorites"), c.strings("SELECT name FROM tag"))
            assertEquals(listOf("bob", "bob"), c.strings("SELECT reporter_username FROM problem_report ORDER BY id"))
            assertEquals(listOf("alice", null), c.strings("SELECT resolver_username FROM problem_report ORDER BY id"))
            // Linked copy wins; otherwise the oldest.
            assertEquals(listOf(2L, 3L), c.longs("SELECT id FROM amazon_order ORDER BY id"))

            // Deleting bob now leaves the household's data alone.
            c.exec("DELETE FROM app_user WHERE id = 2")
            assertEquals(listOf(1L), c.longs("SELECT COUNT(*) FROM tag"))
            assertEquals(listOf(2L), c.longs("SELECT COUNT(*) FROM problem_report"))
            assertEquals(listOf(2L), c.longs("SELECT COUNT(*) FROM amazon_order"))
        }
    }
}
