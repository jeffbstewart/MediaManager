package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Interface for programmatic data updates that need Kotlin code (API calls, computation).
 * Flyway handles DDL; SchemaUpdaters handle data population.
 *
 * The framework tracks which updaters have run and at what version. Bumping an updater's
 * [version] will cause it to re-run on the next startup.
 */
interface SchemaUpdater {
    /** Unique key identifying this updater (e.g., "populate_popularity"). */
    val name: String
    /** Bump to re-run; framework skips if DB version >= this. */
    val version: Int
    /** Execute the data update. */
    fun run()
}

/**
 * Runs all registered [SchemaUpdater] instances, skipping those already applied at their
 * current version. Uses raw JDBI SQL (not KEntity) since the schema_updater table has a
 * String PK (name), not the Long id that KEntity requires.
 *
 * Called from [net.stewart.mediamanager.Bootstrap.init] after Flyway migrations.
 */
object SchemaUpdaterRunner {
    private val log = LoggerFactory.getLogger(SchemaUpdaterRunner::class.java)

    private val updaters = mutableListOf<SchemaUpdater>()

    fun register(updater: SchemaUpdater) {
        updaters.add(updater)
    }

    fun runAll() {
        if (updaters.isEmpty()) return
        log.info("Running {} schema updater(s)", updaters.size)

        val handle = JdbiOrm.jdbi().open()
        try {
            for (updater in updaters) {
                val dbVersion = handle.createQuery(
                    "SELECT version FROM schema_updater WHERE name = :name"
                ).bind("name", updater.name)
                    .mapTo(Int::class.java)
                    .findOne()
                    .orElse(null)

                if (dbVersion != null && dbVersion >= updater.version) {
                    log.info("Schema updater '{}' already at version {} (need {}), skipping",
                        updater.name, dbVersion, updater.version)
                    continue
                }

                log.info("Running schema updater '{}' version {} (was {})",
                    updater.name, updater.version, dbVersion ?: "not applied")
                try {
                    updater.run()

                    handle.createUpdate(
                        "MERGE INTO schema_updater (name, version, applied_at) VALUES (:name, :version, :applied_at)"
                    ).bind("name", updater.name)
                        .bind("version", updater.version)
                        .bind("applied_at", LocalDateTime.now())
                        .execute()

                    log.info("Schema updater '{}' version {} completed", updater.name, updater.version)
                } catch (e: Exception) {
                    log.error("Schema updater '{}' failed: {}", updater.name, e.message, e)
                }
            }
        } finally {
            handle.close()
        }
    }
}
