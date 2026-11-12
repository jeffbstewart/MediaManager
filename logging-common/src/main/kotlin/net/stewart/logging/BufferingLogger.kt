package net.stewart.logging

import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * SLF4J Logger that writes to stderr (like slf4j-simple) and ships log
 * records to Binnacle via [BinnacleExporter].
 */
class BufferingLogger(loggerName: String) : LegacyAbstractLogger() {

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        /** Minimum level: INFO by default, DEBUG for specific loggers if needed. */
        var globalLevel: Level = Level.INFO
    }

    init {
        this.name = loggerName
    }

    private val shortName: String = loggerName.substringAfterLast('.')

    override fun isTraceEnabled(): Boolean = globalLevel.toInt() <= Level.TRACE.toInt()
    override fun isDebugEnabled(): Boolean = globalLevel.toInt() <= Level.DEBUG.toInt()
    override fun isInfoEnabled(): Boolean = globalLevel.toInt() <= Level.INFO.toInt()
    override fun isWarnEnabled(): Boolean = globalLevel.toInt() <= Level.WARN.toInt()
    override fun isErrorEnabled(): Boolean = true

    override fun isTraceEnabled(marker: Marker?): Boolean = isTraceEnabled
    override fun isDebugEnabled(marker: Marker?): Boolean = isDebugEnabled
    override fun isInfoEnabled(marker: Marker?): Boolean = isInfoEnabled
    override fun isWarnEnabled(marker: Marker?): Boolean = isWarnEnabled
    override fun isErrorEnabled(marker: Marker?): Boolean = isErrorEnabled

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?
    ) {
        val formatted = if (arguments != null && messagePattern != null) {
            MessageFormatter.arrayFormat(messagePattern, arguments, throwable).message
        } else {
            messagePattern ?: ""
        }

        // Resolve throwable from arguments if not provided directly
        val actualThrowable = throwable
            ?: (if (arguments != null && messagePattern != null)
                MessageFormatter.arrayFormat(messagePattern, arguments, throwable).throwable
            else null)

        // Write to stderr (same format as slf4j-simple)
        val timestamp = LocalDateTime.now().format(DATE_FMT)
        val threadName = Thread.currentThread().name
        System.err.println("$timestamp [$threadName] $level $shortName - $formatted")
        actualThrowable?.printStackTrace(System.err)

        // Ship to Binnacle (no-op if not configured or not yet initialized)
        BinnacleExporter.emit(level, name, formatted ?: "", actualThrowable, threadName)
    }
}
