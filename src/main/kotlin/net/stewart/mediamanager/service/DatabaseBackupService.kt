package net.stewart.mediamanager.service

import com.zaxxer.hikari.HikariDataSource
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager
import java.time.DayOfWeek
import java.time.LocalDate

object DatabaseBackupService {
    private val log = LoggerFactory.getLogger(DatabaseBackupService::class.java)

    private const val DAILY_SLOTS = 6
    private const val WEEKLY_SLOTS = 4
    private val backupDir = File("./data/backups")

    fun runBackup() {
        try {
            if (!backupDir.exists()) {
                backupDir.mkdirs()
                log.info("Created backup directory: {}", backupDir.absolutePath)
            }

            val today = LocalDate.now()
            val filePassword = System.getProperty("H2_FILE_PASSWORD")
                ?: System.getenv("H2_FILE_PASSWORD")
                ?: ""
            val encrypted = filePassword.isNotBlank()

            // Daily backup
            val dailySlot = today.dayOfYear % DAILY_SLOTS
            val ext = if (encrypted) "sql.enc" else "sql.gz"
            val dailyFile = File(backupDir, "daily-$dailySlot.$ext")
            writeBackup(dailyFile, filePassword, encrypted)
            log.info("Daily backup written to {} ({} bytes, slot {}/{}{})",
                dailyFile.name, dailyFile.length(), dailySlot, DAILY_SLOTS,
                if (encrypted) ", AES-encrypted" else "")

            // Weekly backup on Sundays
            if (today.dayOfWeek == DayOfWeek.SUNDAY) {
                val weeklySlot = (today.dayOfYear / 7) % WEEKLY_SLOTS
                val weeklyFile = File(backupDir, "weekly-$weeklySlot.$ext")
                writeBackup(weeklyFile, filePassword, encrypted)
                log.info("Weekly backup written to {} ({} bytes, slot {}/{}{})",
                    weeklyFile.name, weeklyFile.length(), weeklySlot, WEEKLY_SLOTS,
                    if (encrypted) ", AES-encrypted" else "")
            }
        } catch (e: Exception) {
            log.warn("Database backup failed: {}", e.message, e)
        }
    }

    private fun writeBackup(file: File, filePassword: String, encrypted: Boolean) {
        // Use a direct JDBC connection instead of the HikariCP pool. SCRIPT TO can
        // take minutes for a large database; holding a pooled connection that long
        // starves request-handling threads and triggers health check failures.
        val hikari = JdbiOrm.getDataSource() as HikariDataSource
        DriverManager.getConnection(hikari.jdbcUrl, hikari.username, hikari.password).use { conn ->
            val path = file.absolutePath.replace("\\", "/").replace("'", "''")
            val sql = if (encrypted) {
                val escapedPw = filePassword.replace("'", "''")
                "SCRIPT TO '$path' CIPHER AES PASSWORD '$escapedPw'"
            } else {
                "SCRIPT TO '$path' COMPRESSION GZIP"
            }
            conn.createStatement().use { stmt -> stmt.execute(sql) }
        }
    }
}
