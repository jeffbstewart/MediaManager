package net.stewart.mediamanager.service

import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * In-memory ring buffers for recent application log messages.
 * Separate buffers per severity level to retain the most relevant messages.
 *
 * Buffer sizes: ERROR=100, WARN=100, INFO=300.
 */
object AppLogBuffer {

    data class LogEntry(
        val timestamp: LocalDateTime,
        val level: String,
        val loggerName: String,
        val message: String,
        val stackTrace: String?
    )

    private const val ERROR_CAPACITY = 100
    private const val WARN_CAPACITY = 100
    private const val INFO_CAPACITY = 300

    private val errors = java.util.ArrayDeque<LogEntry>(ERROR_CAPACITY)
    private val warnings = java.util.ArrayDeque<LogEntry>(WARN_CAPACITY)
    private val infos = java.util.ArrayDeque<LogEntry>(INFO_CAPACITY)
    private val lock = Any()

    fun add(level: String, loggerName: String, message: String, throwable: Throwable?) {
        val stackTrace = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sw.toString()
        } else null

        val entry = LogEntry(
            timestamp = LocalDateTime.now(),
            level = level,
            loggerName = loggerName,
            message = message,
            stackTrace = stackTrace
        )

        synchronized(lock) {
            when (level) {
                "ERROR" -> {
                    if (errors.size >= ERROR_CAPACITY) errors.pollFirst()
                    errors.addLast(entry)
                }
                "WARN" -> {
                    if (warnings.size >= WARN_CAPACITY) warnings.pollFirst()
                    warnings.addLast(entry)
                }
                "INFO" -> {
                    if (infos.size >= INFO_CAPACITY) infos.pollFirst()
                    infos.addLast(entry)
                }
            }
        }
    }

    fun getErrors(): List<LogEntry> = synchronized(lock) { errors.toList() }
    fun getWarnings(): List<LogEntry> = synchronized(lock) { warnings.toList() }
    fun getInfos(): List<LogEntry> = synchronized(lock) { infos.toList() }

    /** All entries merged and sorted by timestamp, newest first. */
    fun getAll(): List<LogEntry> = synchronized(lock) {
        (errors + warnings + infos).sortedByDescending { it.timestamp }
    }
}
