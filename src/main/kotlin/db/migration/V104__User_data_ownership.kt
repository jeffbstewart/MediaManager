package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

/**
 * Settles who owns what, so deleting an account is a single row delete.
 *
 * Household data stops pointing at a user at all:
 * - `tag.created_by` is dropped: tags are shared, and their names are
 *   unique across the server.
 * - `amazon_order.user_id` is dropped: the order history is the household's
 *   purchase record (the sole source of purchase data since V022), not the
 *   importing admin's.  Rows imported twice by two admins collapse to one.
 * - `problem_report` keeps who filed and who resolved a report as plain
 *   username strings (`reporter_username`, `resolver_username`) with no
 *   foreign key, so a report outlives its reporter.
 *
 * Everything else that references `app_user` is the user's own data, and
 * its foreign key becomes ON DELETE CASCADE.  Inline `REFERENCES app_user`
 * clauses in the old migrations got generated constraint names, which a
 * SQL migration cannot name portably; that is why this one is Kotlin and
 * looks the constraints up in INFORMATION_SCHEMA.
 *
 * `UserDataOwnershipTest` pins the end state: every foreign key to
 * `app_user` cascades.
 */
@Suppress("ClassName", "unused") // Flyway discovers it by name.
class V104__User_data_ownership : BaseJavaMigration() {

    private data class UserFk(val table: String, val column: String, val constraint: String)

    override fun migrate(context: Context) {
        val conn = context.connection
        val fks = userForeignKeys(conn)

        // Drop every foreign key to app_user first; the survivors are
        // re-added below with ON DELETE CASCADE.
        for (fk in fks) {
            conn.exec("ALTER TABLE \"${fk.table}\" DROP CONSTRAINT \"${fk.constraint}\"")
        }

        // Household: tags.
        conn.exec("ALTER TABLE tag DROP COLUMN created_by")

        // Household: Amazon order history.  Keep one row per (order_id,
        // asin); prefer a row that is linked to a media item, then the
        // oldest.
        conn.exec(
            """DELETE FROM amazon_order a WHERE EXISTS (
                 SELECT 1 FROM amazon_order b
                  WHERE b.order_id = a.order_id AND b.asin = a.asin AND b.id <> a.id
                    AND ((b.linked_media_item_id IS NOT NULL AND a.linked_media_item_id IS NULL)
                      OR ((b.linked_media_item_id IS NULL) = (a.linked_media_item_id IS NULL)
                          AND b.id < a.id)))"""
        )
        conn.exec("DROP INDEX IF EXISTS idx_amazon_order_dedup")
        conn.exec("DROP INDEX IF EXISTS idx_amazon_order_user")
        conn.exec("ALTER TABLE amazon_order DROP COLUMN user_id")
        conn.exec("CREATE UNIQUE INDEX idx_amazon_order_dedup ON amazon_order(order_id, asin)")

        // Problem reports: who, as text.
        conn.exec("ALTER TABLE problem_report ADD COLUMN reporter_username VARCHAR(100)")
        conn.exec("ALTER TABLE problem_report ADD COLUMN resolver_username VARCHAR(100)")
        conn.exec(
            """UPDATE problem_report SET
                 reporter_username = (SELECT u.username FROM app_user u WHERE u.id = problem_report.user_id),
                 resolver_username = (SELECT u.username FROM app_user u WHERE u.id = problem_report.resolved_by)"""
        )
        conn.exec("ALTER TABLE problem_report DROP COLUMN user_id")
        conn.exec("ALTER TABLE problem_report DROP COLUMN resolved_by")

        // The user's own data: cascade.
        val dropped = setOf(
            "TAG" to "CREATED_BY",
            "AMAZON_ORDER" to "USER_ID",
            "PROBLEM_REPORT" to "USER_ID",
            "PROBLEM_REPORT" to "RESOLVED_BY",
        )
        for (fk in fks) {
            if (fk.table to fk.column in dropped) continue
            val name = "fk_${fk.table.lowercase()}_${fk.column.lowercase()}_user"
            conn.exec(
                "ALTER TABLE \"${fk.table}\" ADD CONSTRAINT $name " +
                    "FOREIGN KEY (\"${fk.column}\") REFERENCES app_user(id) ON DELETE CASCADE"
            )
        }
    }

    private fun userForeignKeys(conn: Connection): List<UserFk> {
        val sql = """
            SELECT kcu.TABLE_NAME, kcu.COLUMN_NAME, rc.CONSTRAINT_NAME
              FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
              JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS pk
                ON pk.CONSTRAINT_SCHEMA = rc.UNIQUE_CONSTRAINT_SCHEMA
               AND pk.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME
              JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
               AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
             WHERE pk.TABLE_NAME = 'APP_USER'
               AND rc.CONSTRAINT_SCHEMA = SCHEMA()
        """
        return conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add(UserFk(rs.getString(1), rs.getString(2), rs.getString(3)))
                }
            }
        }
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
