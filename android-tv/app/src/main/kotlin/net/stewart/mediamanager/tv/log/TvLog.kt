package net.stewart.mediamanager.tv.log

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import net.stewart.mediamanager.tv.auth.AuthManager
import java.io.PrintWriter
import java.io.StringWriter

/**
 * In-process log facility for the Android TV client.
 *
 * Each call does two things:
 * - Writes to `adb logcat` under tag `"[MM]"` so dev builds stay debuggable.
 * - Offers the record onto a bounded [Channel] drained by [LogStreamer],
 *   which ships the record to the server's Binnacle-backed
 *   ObservabilityService. Overflow drops the oldest queued record so a
 *   long offline window can never pin device memory.
 *
 * The active username is stamped onto every record as an attribute so
 * Binnacle queries can attribute navigation / playback events to the
 * viewer who produced them.
 */
object TvLog {

    private const val TAG = "[MM]"

    /** Max records buffered when the server is unreachable. */
    private const val BUFFER_CAPACITY = 1000

    /**
     * Drop-oldest bounded channel drained by [LogStreamer]. Exposed so the
     * streamer can read; callers should only write via the level helpers.
     */
    val channel: Channel<TvLogRecord> = Channel(
        capacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    private var authManager: AuthManager? = null

    /** Install the auth manager so each record auto-tags the active user. */
    fun init(authManager: AuthManager) {
        this.authManager = authManager
    }

    fun debug(logger: String, message: String, attrs: Map<String, String> = emptyMap()) =
        emit(Severity.DEBUG, logger, message, null, attrs)

    fun info(logger: String, message: String, attrs: Map<String, String> = emptyMap()) =
        emit(Severity.INFO, logger, message, null, attrs)

    fun warn(logger: String, message: String, throwable: Throwable? = null, attrs: Map<String, String> = emptyMap()) =
        emit(Severity.WARN, logger, message, throwable, attrs)

    fun error(logger: String, message: String, throwable: Throwable? = null, attrs: Map<String, String> = emptyMap()) =
        emit(Severity.ERROR, logger, message, throwable, attrs)

    private fun emit(
        severity: Severity,
        logger: String,
        message: String,
        throwable: Throwable?,
        attrs: Map<String, String>
    ) {
        // logcat mirror — always, regardless of streaming status.
        val line = "$logger - $message"
        when (severity) {
            Severity.DEBUG -> Log.d(TAG, line, throwable)
            Severity.INFO -> Log.i(TAG, line, throwable)
            Severity.WARN -> Log.w(TAG, line, throwable)
            Severity.ERROR -> Log.e(TAG, line, throwable)
        }

        val username = authManager?.activeUsername
        val enriched = if (username != null) attrs + ("user" to username) else attrs
        val record = TvLogRecord(
            timestampEpochMs = System.currentTimeMillis(),
            severity = severity,
            logger = logger,
            message = message,
            exceptionType = throwable?.javaClass?.name,
            exceptionMessage = throwable?.message,
            exceptionStackTrace = throwable?.let { stackTraceToString(it) },
            attributes = enriched
        )
        // trySend never blocks; DROP_OLDEST handles overflow for us.
        channel.trySend(record)
    }

    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}

enum class Severity { DEBUG, INFO, WARN, ERROR }

data class TvLogRecord(
    val timestampEpochMs: Long,
    val severity: Severity,
    val logger: String,
    val message: String,
    val exceptionType: String?,
    val exceptionMessage: String?,
    val exceptionStackTrace: String?,
    val attributes: Map<String, String>
)
