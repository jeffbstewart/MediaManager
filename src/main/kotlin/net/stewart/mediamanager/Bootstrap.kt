package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.HiddenTitleCleaner
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.PopulateActorPopularityUpdater
import net.stewart.mediamanager.service.PopulateBackdropUpdater
import net.stewart.mediamanager.service.PopulateCastUpdater
import net.stewart.mediamanager.service.PopulateContentRatingUpdater
import net.stewart.mediamanager.service.PopulatePopularityUpdater
import net.stewart.mediamanager.service.PopulateFileModifiedUpdater
import net.stewart.mediamanager.service.PopulateCollectionUpdater
import net.stewart.mediamanager.service.PopulateProductNameUpdater
import net.stewart.mediamanager.service.BackfillFirstPartyImageSidecars
import net.stewart.mediamanager.service.BackfillTrackId3TagsUpdater
import net.stewart.mediamanager.service.RebuildBookAuthorsUpdater
import net.stewart.mediamanager.service.RetryMadmomFailuresUpdater
import net.stewart.mediamanager.service.BackfillInternetImageSidecars
import net.stewart.mediamanager.service.CopyFirstPartyImagesToNewLayout
import net.stewart.mediamanager.service.MigrateInternetImageCachesUpdater
import net.stewart.mediamanager.service.BulkTagUpdater
import net.stewart.mediamanager.service.ManagedDirectoryService
import net.stewart.mediamanager.service.MigrateOwnershipPhotosUpdater
import net.stewart.mediamanager.service.MigrateSeasonDataUpdater
import net.stewart.mediamanager.service.ClearOlImageCacheUpdater
import net.stewart.mediamanager.service.ClearAllAlbumsUpdater
import net.stewart.mediamanager.service.ClearUnmatchedAudioUpdater
import net.stewart.mediamanager.service.RepairFulfilledWishesUpdater
import net.stewart.mediamanager.service.RetryUnmatchedBooksUpdater
import net.stewart.mediamanager.service.PopulateSeasonsUpdater
import net.stewart.mediamanager.service.MigrateAuxFilesUpdater
import net.stewart.mediamanager.service.SchemaUpdaterRunner
import net.stewart.mediamanager.service.UnlinkMovieEpisodesUpdater
import net.stewart.logging.BinnacleExporter
import net.stewart.logging.BinnacleExporter.Status
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.io.File

object Bootstrap {
    private val log = LoggerFactory.getLogger(Bootstrap::class.java)

    // app_config marker that records the one-time SHUTDOWN DEFRAG compaction
    // performed after migration V100 dropped the 6 GiB price_lookup.raw_json column.
    private const val COMPACT_MARKER = "db_compacted_drop_raw_json"

    fun init() {
        loadEnvFile()
        loadEnvironmentVariables()
        initBinnacle()
        log.info("Bootstrap.init starting")

        log.info("Initializing database")
        val h2Password = System.getProperty("H2_PASSWORD") ?: System.getenv("H2_PASSWORD")
        if (h2Password.isNullOrBlank()) {
            throw RuntimeException("H2_PASSWORD must be set in .env or environment. The database cannot start without a password.")
        }
        val h2PriorPassword = System.getProperty("H2_PRIOR_PASSWORD") ?: System.getenv("H2_PRIOR_PASSWORD") ?: ""
        val h2FilePassword = System.getProperty("H2_FILE_PASSWORD") ?: System.getenv("H2_FILE_PASSWORD") ?: ""
        if (h2FilePassword.isBlank()) {
            throw RuntimeException("H2_FILE_PASSWORD must be set in .env or environment. The database file is AES-encrypted at rest and requires this key.")
        }

        val placeholders = setOf(
            "change_me_to_a_strong_password",
            "change_me_to_another_strong_password",
            "your-database-password",
            "your-file-encryption-password"
        )
        if (h2Password in placeholders) {
            throw RuntimeException("H2_PASSWORD is still set to a placeholder value. Change it to a real password.")
        }
        if (h2FilePassword in placeholders) {
            throw RuntimeException("H2_FILE_PASSWORD is still set to a placeholder value. Change it to a real password.")
        }

        val basePath = "./data/mediamanager"
        log.info("Database encryption: ENABLED (H2_FILE_PASSWORD set)")

        // Check for restore sentinel before any DB operations
        val restoreSentinel = File("./data/restore.sql")
        if (restoreSentinel.exists()) {
            restoreFromBackup(basePath, restoreSentinel, h2Password, h2FilePassword)
        }

        // If DB exists unencrypted, migrate it to encrypted format
        if (File("${basePath}.mv.db").exists()) {
            migrateToEncryptedDb(basePath, h2Password, h2PriorPassword, h2FilePassword)
        }

        val dbUrl = "jdbc:h2:file:$basePath;CIPHER=AES"
        val dbPassword = "$h2FilePassword $h2Password"
        log.info("JDBC URL: {}", dbUrl)

        // Rotate H2_PASSWORD if H2_PRIOR_PASSWORD is set
        if (h2PriorPassword.isNotBlank()) {
            migrateH2Password(dbUrl, h2Password, h2PriorPassword, h2FilePassword)
        }

        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = dbUrl
            username = "sa"
            password = dbPassword
            maximumPoolSize = 25
            poolName = "mediamanager"
            connectionTimeout = 5000          // Fail fast (5s) instead of silently queueing for 30s
            leakDetectionThreshold = 10000    // Log WARNING + stack trace if connection held >10s
            metricRegistry = MetricsRegistry.registry  // Expose pool stats via Prometheus /metrics
        })

        JdbiOrm.setDataSource(ds)
        log.info("JdbiOrm DataSource registered")

        val flyway = Flyway.configure()
            .dataSource(ds)
            .load()

        flyway.migrate()

        log.info("Database migrations applied")

        compactDatabaseOnce(ds, basePath)
        reportTableSizes(ds, basePath)

        syncAppConfigFromEnv()
        rebaseFilePaths()

        SchemaUpdaterRunner.register(PopulatePopularityUpdater())
        SchemaUpdaterRunner.register(PopulateCastUpdater())
        SchemaUpdaterRunner.register(PopulateActorPopularityUpdater())
        SchemaUpdaterRunner.register(PopulateProductNameUpdater())
        SchemaUpdaterRunner.register(PopulateFileModifiedUpdater())
        SchemaUpdaterRunner.register(PopulateContentRatingUpdater())
        SchemaUpdaterRunner.register(PopulateBackdropUpdater())
        SchemaUpdaterRunner.register(UnlinkMovieEpisodesUpdater())
        SchemaUpdaterRunner.register(PopulateSeasonsUpdater())
        SchemaUpdaterRunner.register(BulkTagUpdater())
        SchemaUpdaterRunner.register(MigrateAuxFilesUpdater())
        SchemaUpdaterRunner.register(PopulateCollectionUpdater())
        SchemaUpdaterRunner.register(MigrateSeasonDataUpdater())
        SchemaUpdaterRunner.register(RepairFulfilledWishesUpdater())
        SchemaUpdaterRunner.register(MigrateOwnershipPhotosUpdater())
        SchemaUpdaterRunner.register(RetryUnmatchedBooksUpdater())
        SchemaUpdaterRunner.register(ClearOlImageCacheUpdater())
        SchemaUpdaterRunner.register(ClearUnmatchedAudioUpdater())
        SchemaUpdaterRunner.register(ClearAllAlbumsUpdater())
        SchemaUpdaterRunner.register(BackfillFirstPartyImageSidecars())
        SchemaUpdaterRunner.register(BackfillInternetImageSidecars())
        SchemaUpdaterRunner.register(MigrateInternetImageCachesUpdater())
        SchemaUpdaterRunner.register(CopyFirstPartyImagesToNewLayout())
        SchemaUpdaterRunner.register(BackfillTrackId3TagsUpdater())
        SchemaUpdaterRunner.register(RetryMadmomFailuresUpdater())
        SchemaUpdaterRunner.register(RebuildBookAuthorsUpdater())
        SchemaUpdaterRunner.runAll()

        ManagedDirectoryService.ensureManagedDirectories()

        AuthService.cleanupExpiredTokens()
        AuthService.cleanupOldAttempts()

        HiddenTitleCleaner.clean()

        SearchIndexService.rebuild()
    }

    /**
     * One-time database compaction to reclaim disk space freed by dropping the
     * ~6 GiB `price_lookup.raw_json` column (migration V100). H2's MVStore frees
     * pages logically on DROP COLUMN but never returns them to the filesystem;
     * only `SHUTDOWN DEFRAG` rewrites and shrinks the `.mv.db` file.
     *
     * Gated by an `app_config` marker so it runs exactly once. The marker is
     * written only after a successful compaction, so a failure part-way through
     * simply retries on the next startup (re-defragging an already-small database
     * is cheap and harmless).
     *
     * Must run AFTER Flyway migrations (so the column is already gone). `SHUTDOWN`
     * closes the database for ALL connections, so afterward we reopen the file and
     * evict the now-stale pooled connections before normal startup continues.
     * Failures are non-fatal — a slightly bloated file must never block startup.
     */
    private fun compactDatabaseOnce(ds: HikariDataSource, basePath: String) {
        try {
            if (AppConfig.findAll().any { it.config_key == COMPACT_MARKER }) return

            val dbFile = File("${basePath}.mv.db")
            val before = dbFile.length()
            log.info("=== ONE-TIME DATABASE COMPACTION (SHUTDOWN DEFRAG) ===")
            log.info("File before compaction: {}", humanBytes(before))
            val t0 = System.currentTimeMillis()

            // SHUTDOWN DEFRAG compacts and closes the database for every connection.
            // Run it on a dedicated connection, never a pooled one.
            try {
                DriverManager.getConnection(ds.jdbcUrl, ds.username, ds.password).use { conn ->
                    conn.createStatement().use { it.execute("SHUTDOWN DEFRAG") }
                }
            } catch (e: Exception) {
                // SHUTDOWN drops the connection as it closes the DB — expected, not an error.
                log.info("SHUTDOWN DEFRAG connection closed (expected): {}", e.message)
            }

            // Reopen the now-compacted file and discard stale pooled connections so
            // the remainder of startup borrows fresh connections to the reopened DB.
            DriverManager.getConnection(ds.jdbcUrl, ds.username, ds.password).use { conn ->
                conn.createStatement().use { it.execute("SELECT 1") }
            }
            ds.hikariPoolMXBean?.softEvictConnections()

            val after = dbFile.length()
            log.info("File after compaction: {} (reclaimed {}) in {} ms",
                humanBytes(after), humanBytes(before - after), System.currentTimeMillis() - t0)

            // Record the marker only on success, so a failed run retries next startup.
            AppConfig(config_key = COMPACT_MARKER, config_val = "done").save()
            log.info("=== COMPACTION COMPLETE ===")
        } catch (e: Exception) {
            log.warn("One-time database compaction failed (non-fatal, will retry next startup): {}", e.message, e)
        }
    }

    /**
     * Logs a rough size breakdown of every base table at startup so database
     * bloat can be traced to a specific table (or to LOB storage).
     *
     * For each table we report row count and H2's `DISK_SPACE_USED` — the
     * approximate bytes occupied by that table's own MVStore map. Two caveats
     * the output deliberately surfaces:
     *
     *  - `DISK_SPACE_USED` does NOT count BLOB/CLOB payloads, which H2 keeps in
     *    a separate LOB area of the same file. So if the summed table sizes are
     *    far smaller than the actual `.mv.db` file, the bloat is LOB data (or
     *    free space awaiting compaction). We log that gap explicitly.
     *  - To help pin down LOB bloat, we also list every LOB / large-varchar /
     *    varbinary column as a suspect.
     *
     * Failures here are non-fatal — diagnostics must never block startup.
     */
    private fun reportTableSizes(ds: HikariDataSource, basePath: String) {
        val t0 = System.currentTimeMillis()
        try {
            ds.connection.use { conn ->
                data class TableInfo(val name: String, val rows: Long, val bytes: Long)

                val tableNames = mutableListOf<String>()
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                        WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'
                        ORDER BY TABLE_NAME
                        """.trimIndent()
                    ).use { rs ->
                        while (rs.next()) tableNames.add(rs.getString(1))
                    }
                }

                val infos = mutableListOf<TableInfo>()
                for (name in tableNames) {
                    val quoted = "\"" + name.replace("\"", "\"\"") + "\""
                    val rows = conn.createStatement().use { st ->
                        st.executeQuery("SELECT COUNT(*) FROM $quoted").use { rs ->
                            if (rs.next()) rs.getLong(1) else 0L
                        }
                    }
                    val bytes = try {
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT DISK_SPACE_USED('$name')").use { rs ->
                                if (rs.next()) rs.getLong(1) else 0L
                            }
                        }
                    } catch (e: Exception) {
                        -1L  // DISK_SPACE_USED unavailable for this table
                    }
                    infos.add(TableInfo(name, rows, bytes))
                }

                infos.sortByDescending { it.bytes }
                val accounted = infos.filter { it.bytes > 0 }.sumOf { it.bytes }
                val fileBytes = File("${basePath}.mv.db").length()

                log.info("=== DATABASE SIZE REPORT ===")
                log.info("On-disk file: {} ({})", "${basePath}.mv.db", humanBytes(fileBytes))
                log.info("Sum of table data (excl. LOB/free space): {}", humanBytes(accounted))
                log.info("Unaccounted (LOB payloads + free space + indexes overhead): {}",
                    humanBytes(fileBytes - accounted))
                log.info(String.format("%14s  %12s  %s", "DISK_SPACE", "ROWS", "TABLE"))
                for (info in infos) {
                    val sizeStr = if (info.bytes < 0) "n/a" else humanBytes(info.bytes)
                    log.info(String.format("%14s  %12d  %s", sizeStr, info.rows, info.name))
                }

                // Suspect columns for LOB bloat (the "unaccounted" gap above).
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_SCHEMA = 'PUBLIC'
                          AND (DATA_TYPE IN ('CHARACTER LARGE OBJECT', 'BINARY LARGE OBJECT', 'CLOB', 'BLOB')
                               OR CHARACTER_MAXIMUM_LENGTH >= 1000000)
                        ORDER BY TABLE_NAME, COLUMN_NAME
                        """.trimIndent()
                    ).use { rs ->
                        val suspects = mutableListOf<String>()
                        while (rs.next()) {
                            suspects.add("${rs.getString(1)}.${rs.getString(2)} (${rs.getString(3)})")
                        }
                        if (suspects.isNotEmpty()) {
                            log.info("LOB / large columns (candidates for unaccounted bloat): {}",
                                suspects.joinToString(", "))
                        }
                    }
                }
                log.info("=== END DATABASE SIZE REPORT ({} ms) ===", System.currentTimeMillis() - t0)
            }
        } catch (e: Exception) {
            log.warn("Database size report failed (non-fatal): {}", e.message)
        }
    }

    private fun humanBytes(bytes: Long): String {
        if (bytes < 0) return "n/a"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }

    fun destroy() {
        JdbiOrm.destroy()
        log.info("JdbiOrm destroyed")
    }

    /**
     * Migrates the H2 database user password from H2_PRIOR_PASSWORD to H2_PASSWORD.
     * Works with encrypted (CIPHER=AES) databases using compound passwords.
     *
     * To change the password: move current H2_PASSWORD to H2_PRIOR_PASSWORD, set new H2_PASSWORD.
     * After one successful startup, H2_PRIOR_PASSWORD can be removed.
     */
    private fun migrateH2Password(dbUrl: String, targetPassword: String, priorPassword: String, filePassword: String) {
        // New database — will be created with the target password
        if (!File("./data/mediamanager.mv.db").exists()) return

        // Try connecting with the target password — if it works, no migration needed
        try {
            DriverManager.getConnection(dbUrl, "sa", "$filePassword $targetPassword").use { it.createStatement().execute("SELECT 1") }
            return
        } catch (_: Exception) {
            // Target password didn't work — need to migrate from prior password
        }

        log.info("H2_PASSWORD does not match database — attempting migration from H2_PRIOR_PASSWORD")
        try {
            DriverManager.getConnection(dbUrl, "sa", "$filePassword $priorPassword").use { conn ->
                val escaped = targetPassword.replace("'", "''")
                conn.createStatement().execute("ALTER USER sa SET PASSWORD '$escaped'")
                log.info("H2 database password changed successfully")
            }
        } catch (e: Exception) {
            log.error("Failed to migrate H2 password — neither H2_PASSWORD nor H2_PRIOR_PASSWORD can connect. " +
                "Set H2_PRIOR_PASSWORD to the current database password and H2_PASSWORD to the desired new password.", e)
            throw RuntimeException("H2 password migration failed", e)
        }
    }

    /**
     * Migrates an unencrypted H2 database to AES-encrypted format.
     *
     * Steps:
     * 1. Connect to the existing unencrypted DB (tries target password, then prior password)
     * 2. Export all data to a SQL script via SCRIPT TO
     * 3. Close the connection
     * 4. Back up the original .mv.db file (renamed to .mv.db.pre-encryption)
     * 5. Create a new encrypted DB and import the SQL script via RUNSCRIPT FROM
     * 6. Delete the SQL script (contains plaintext data)
     *
     * If anything fails after the backup rename, the original file is restored.
     */
    private fun migrateToEncryptedDb(
        basePath: String,
        h2Password: String,
        h2PriorPassword: String,
        h2FilePassword: String
    ) {
        val dbFile = File("${basePath}.mv.db")
        val encryptedUrl = "jdbc:h2:file:$basePath;CIPHER=AES"
        val compoundPassword = "$h2FilePassword $h2Password"

        log.info("H2_FILE_PASSWORD is set — checking if database is already encrypted")
        log.info("Database file: {} ({} bytes)", dbFile.absolutePath, dbFile.length())

        // Check if DB is already encrypted by trying to connect with compound password
        try {
            DriverManager.getConnection(encryptedUrl, "sa", compoundPassword).use {
                it.createStatement().execute("SELECT 1")
            }
            log.info("Database is already encrypted — no migration needed")
            return
        } catch (e: Exception) {
            log.info("Database is not encrypted (expected on first migration): {}", e.message)
        }

        log.info("=== ENCRYPTION MIGRATION STARTING ===")
        log.info("Step 1/5: Resolving unencrypted database password")

        // Step 1: Connect to unencrypted DB
        val unencryptedUrl = "jdbc:h2:file:$basePath"
        val unencryptedPassword = resolveWorkingPassword(unencryptedUrl, h2Password, h2PriorPassword)
            ?: throw RuntimeException(
                "Cannot connect to unencrypted database with either H2_PASSWORD or H2_PRIOR_PASSWORD. " +
                "Fix credentials before enabling encryption."
            )
        log.info("Step 1/5: Connected to unencrypted database successfully")

        // Step 2: Export to SQL script
        log.info("Step 2/5: Exporting database to SQL script")
        val scriptFile = File("${basePath}-export.sql")
        try {
            DriverManager.getConnection(unencryptedUrl, "sa", unencryptedPassword).use { conn ->
                val escaped = scriptFile.absolutePath.replace("'", "''")
                conn.createStatement().execute("SCRIPT TO '$escaped'")
            }
            log.info("Step 2/5: Exported database to {} ({} bytes)", scriptFile.absolutePath, scriptFile.length())
        } catch (e: Exception) {
            log.error("Step 2/5: FAILED to export database — aborting migration, database unchanged", e)
            scriptFile.delete()
            throw RuntimeException("Failed to export database for encryption migration", e)
        }

        // Step 3: Back up original file
        log.info("Step 3/5: Backing up original database file")
        val backupFile = File("${basePath}.mv.db.pre-encryption")
        if (backupFile.exists()) {
            log.info("Removing previous backup at {}", backupFile.name)
            backupFile.delete()
        }
        if (!dbFile.renameTo(backupFile)) {
            log.error("Step 3/5: FAILED to rename {} to {} — aborting migration, database unchanged",
                dbFile.name, backupFile.name)
            scriptFile.delete()
            throw RuntimeException("Failed to rename ${dbFile.name} to ${backupFile.name}")
        }
        log.info("Step 3/5: Backed up unencrypted database to {} ({} bytes)", backupFile.name, backupFile.length())

        // Step 4: Create encrypted DB and import
        log.info("Step 4/5: Creating encrypted database and importing data")
        try {
            DriverManager.getConnection(encryptedUrl, "sa", compoundPassword).use { conn ->
                val escaped = scriptFile.absolutePath.replace("'", "''")
                conn.createStatement().execute("RUNSCRIPT FROM '$escaped'")
            }
            val newDbFile = File("${basePath}.mv.db")
            log.info("Step 4/5: Imported data into encrypted database ({} bytes)", newDbFile.length())
        } catch (e: Exception) {
            log.error("Step 4/5: FAILED to import into encrypted database — restoring backup", e)
            val newDbFile = File("${basePath}.mv.db")
            if (newDbFile.exists()) {
                log.info("Deleting partially-created encrypted file ({} bytes)", newDbFile.length())
                newDbFile.delete()
            }
            val restored = backupFile.renameTo(dbFile)
            log.info("Restore of original database: {}", if (restored) "SUCCESS" else "FAILED")
            scriptFile.delete()
            throw RuntimeException("Encryption migration failed during import — original database restored", e)
        }

        // Step 5: Clean up the SQL script (contains plaintext data)
        log.info("Step 5/5: Deleting SQL export script (contains plaintext data)")
        scriptFile.delete()
        log.info("=== ENCRYPTION MIGRATION COMPLETE ===")
        log.info("Encrypted database is live. Backup at {} can be deleted after verification.", backupFile.name)
    }

    /**
     * Tries connecting with the target password first, then the prior password.
     * Returns whichever password works, or null if neither does.
     */
    private fun resolveWorkingPassword(dbUrl: String, targetPassword: String, priorPassword: String): String? {
        try {
            DriverManager.getConnection(dbUrl, "sa", targetPassword).use {
                it.createStatement().execute("SELECT 1")
            }
            return targetPassword
        } catch (_: Exception) {}

        if (priorPassword.isNotBlank()) {
            try {
                DriverManager.getConnection(dbUrl, "sa", priorPassword).use {
                    it.createStatement().execute("SELECT 1")
                }
                return priorPassword
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Restores the database from a backup sentinel file (`data/restore.sql`).
     *
     * Steps:
     * 1. Back up the current database to .mv.db.pre-restore
     * 2. Delete the current database file
     * 3. Create a fresh database and import the backup via RUNSCRIPT FROM
     * 4. Delete the sentinel file
     *
     * The sentinel file must be a CIPHER AES backup matching the current
     * H2_FILE_PASSWORD.
     *
     * To restore: copy a backup file to `data/restore.sql` and restart the server.
     */
    private fun restoreFromBackup(
        basePath: String,
        sentinelFile: File,
        h2Password: String,
        h2FilePassword: String
    ) {
        log.warn("=== DATABASE RESTORE DETECTED ===")
        log.warn("Sentinel file: {} ({} bytes)", sentinelFile.absolutePath, sentinelFile.length())

        val dbFile = File("${basePath}.mv.db")

        // Step 1: Back up current database
        if (dbFile.exists()) {
            val backupFile = File("${basePath}.mv.db.pre-restore")
            if (backupFile.exists()) backupFile.delete()
            if (!dbFile.renameTo(backupFile)) {
                throw RuntimeException("Failed to back up current database to ${backupFile.name} — restore aborted")
            }
            log.warn("Step 1/3: Backed up current database to {} ({} bytes)", backupFile.name, backupFile.length())
        } else {
            log.warn("Step 1/3: No existing database to back up")
        }

        // Step 2: Create fresh database and import
        log.warn("Step 2/3: Importing backup into fresh database")
        val dbUrl = "jdbc:h2:file:$basePath;CIPHER=AES"
        val compoundPassword = "$h2FilePassword $h2Password"
        try {
            DriverManager.getConnection(dbUrl, "sa", compoundPassword).use { conn ->
                val path = sentinelFile.absolutePath.replace("\\", "/").replace("'", "''")
                val escapedPw = h2FilePassword.replace("'", "''")
                conn.createStatement().execute("RUNSCRIPT FROM '$path' CIPHER AES PASSWORD '$escapedPw'")
            }
            val newDbFile = File("${basePath}.mv.db")
            log.warn("Step 2/3: Imported backup successfully ({} bytes)", newDbFile.length())
        } catch (e: Exception) {
            log.error("Step 2/3: FAILED to import backup — restoring previous database", e)
            val newDbFile = File("${basePath}.mv.db")
            if (newDbFile.exists()) newDbFile.delete()
            val backupFile = File("${basePath}.mv.db.pre-restore")
            if (backupFile.exists()) backupFile.renameTo(dbFile)
            sentinelFile.delete()
            throw RuntimeException("Database restore failed — previous database restored, sentinel deleted", e)
        }

        // Step 3: Delete sentinel
        sentinelFile.delete()
        log.warn("Step 3/3: Deleted sentinel file")
        log.warn("=== DATABASE RESTORE COMPLETE ===")
    }

    private fun initBinnacle() {
        // Read version from the server jar's manifest, not logging-common's.
        val version = Bootstrap::class.java.`package`?.implementationVersion ?: "dev"
        when (BinnacleExporter.init("mediamanager-server", version)) {
            Status.ENABLED -> {
                log.info("Binnacle log export enabled")
                Runtime.getRuntime().addShutdownHook(Thread { BinnacleExporter.shutdown() })
            }
            Status.NOT_CONFIGURED ->
                log.info("Binnacle not configured (set BINNACLE_ENDPOINT and BINNACLE_API_KEY in secrets/.env to enable)")
            Status.PROBE_FAILED ->
                log.error("Binnacle probe failed — log export disabled: {}", BinnacleExporter.probeError)
        }
    }

    private fun loadEnvFile() {
        val envFile = File("secrets/.env")
        if (!envFile.exists()) {
            log.info("No secrets/.env file found, skipping")
            return
        }
        var count = 0
        envFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex <= 0) return@forEach
            val key = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()
            if (value.isNotEmpty()) {
                System.setProperty(key, value)
                count++
            }
        }
        log.info("Loaded {} properties from secrets/.env", count)
    }

    /**
     * Bridges Docker environment variables to system properties.
     * Only sets a property if not already set (by .env file), so .env takes precedence.
     */
    private fun loadEnvironmentVariables() {
        val envKeys = listOf("TMDB_API_KEY", "TMDB_API_READ_ACCESS_TOKEN", "BINNACLE_ENDPOINT", "BINNACLE_API_KEY")
        var count = 0
        for (key in envKeys) {
            if (System.getProperty(key) != null) continue
            val value = System.getenv(key)
            if (!value.isNullOrBlank()) {
                System.setProperty(key, value)
                count++
            }
        }
        if (count > 0) {
            log.info("Loaded {} properties from environment variables", count)
        }
    }

    /**
     * Syncs environment variables to app_config rows.
     * MM_NAS_ROOT → nas_root_path, MM_FFMPEG_PATH → ffmpeg_path.
     * Runs every startup so env var changes take effect on restart.
     */
    private fun syncAppConfigFromEnv() {
        val mappings = mapOf(
            "MM_NAS_ROOT" to "nas_root_path",
            "MM_FFMPEG_PATH" to "ffmpeg_path"
        )
        for ((envVar, configKey) in mappings) {
            val value = System.getenv(envVar) ?: continue
            if (value.isBlank()) continue
            val existing = AppConfig.findAll().firstOrNull { it.config_key == configKey }
            if (existing != null) {
                if (existing.config_val != value) {
                    existing.config_val = value
                    existing.save()
                    log.info("Updated app_config '{}' from env var {}", configKey, envVar)
                }
            } else {
                AppConfig(config_key = configKey, config_val = value).save()
                log.info("Created app_config '{}' from env var {}", configKey, envVar)
            }
        }
    }

    /**
     * Rebases absolute file paths in transcode and discovered_file tables
     * when migrating between environments (e.g., Windows → Docker/Linux).
     * Only runs when MM_NAS_ROOT is set. No-op when paths are already correct.
     */
    private fun rebaseFilePaths() {
        val nasRoot = System.getenv("MM_NAS_ROOT") ?: return
        val normalizedRoot = nasRoot.trimEnd('/', '\\')
        var rebasedCount = 0

        for (tc in Transcode.findAll()) {
            val path = tc.file_path ?: continue
            if (path.startsWith(normalizedRoot)) continue
            val rebased = rebasePath(path, normalizedRoot) ?: continue
            tc.file_path = rebased
            tc.save()
            rebasedCount++
        }

        for (df in DiscoveredFile.findAll()) {
            if (df.file_path.startsWith(normalizedRoot)) continue
            val rebased = rebasePath(df.file_path, normalizedRoot) ?: continue
            df.file_path = rebased
            df.save()
            rebasedCount++
        }

        if (rebasedCount > 0) {
            log.info("Rebased {} file paths to NAS root '{}'", rebasedCount, normalizedRoot)
        }
    }

    private fun rebasePath(oldPath: String, newRoot: String): String? {
        val normalized = oldPath.replace('\\', '/')
        val markers = listOf("/BLURAY/", "/DVD/", "/UHD/", "/TV Series From Media/", "/ForBrowser/")
        for (marker in markers) {
            val idx = normalized.indexOf(marker)
            if (idx >= 0) {
                val relative = normalized.substring(idx + 1)  // e.g., "BLURAY/Movie.mkv"
                return "$newRoot/$relative"
            }
        }
        return null
    }
}
