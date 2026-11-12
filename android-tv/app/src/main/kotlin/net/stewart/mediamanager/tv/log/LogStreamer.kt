package net.stewart.mediamanager.tv.log

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.LogSeverity
import net.stewart.mediamanager.grpc.logRecord
import net.stewart.mediamanager.grpc.timestamp
import net.stewart.mediamanager.tv.BuildConfig
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient

/**
 * Ships records from [TvLog.channel] to the server's ObservabilityService
 * over a long-lived client-streaming RPC. Reconnects with backoff if the
 * stream drops; records stay queued (drop-oldest) while offline.
 *
 * Call [start] once the user is authenticated, [stop] on sign-out /
 * process exit. Safe to call [start] again after [stop].
 */
class LogStreamer(
    private val grpcClient: GrpcClient,
    private val authManager: AuthManager
) {
    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive) {
                // Only attempt while there's an active user — tokens are needed.
                if (authManager.activeUsername == null || authManager.accessToken == null) {
                    delay(1_000)
                    continue
                }
                try {
                    streamOnce()
                    backoffMs = INITIAL_BACKOFF_MS
                } catch (e: Exception) {
                    // Can't log via TvLog here — would deadlock back onto the same channel.
                    Log.w("[MM]", "LogStreamer connection lost (${e.javaClass.simpleName}); backing off ${backoffMs}ms")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Tears down the scope — call from Activity.onDestroy on the final instance. */
    fun shutdown() {
        scope.cancel()
    }

    private suspend fun streamOnce() {
        val flow = flow<net.stewart.mediamanager.grpc.LogRecord> {
            // Don't use Channel.consumeEach — it cancels the channel on
            // completion, which would destroy the singleton on reconnect.
            while (currentCoroutineContext().isActive) {
                val record = TvLog.channel.receive()
                emit(record.toProto())
            }
        }
        grpcClient.withAuth {
            grpcClient.observabilityService().streamLogs(flow)
        }
    }

    private fun TvLogRecord.toProto(): net.stewart.mediamanager.grpc.LogRecord =
        logRecord {
            serviceName = SERVICE_NAME
            serviceVersion = "${BuildConfig.VERSION_NAME} (${Build.MODEL}, Android ${Build.VERSION.RELEASE})"
            timestamp = timestamp { secondsSinceEpoch = this@toProto.timestampEpochMs / 1000 }
            severity = when (this@toProto.severity) {
                Severity.DEBUG -> LogSeverity.LOG_SEVERITY_DEBUG
                Severity.INFO -> LogSeverity.LOG_SEVERITY_INFO
                Severity.WARN -> LogSeverity.LOG_SEVERITY_WARN
                Severity.ERROR -> LogSeverity.LOG_SEVERITY_ERROR
            }
            loggerName = this@toProto.logger
            message = this@toProto.message
            this@toProto.exceptionType?.let { exceptionType = it }
            this@toProto.exceptionMessage?.let { exceptionMessage = it }
            this@toProto.exceptionStackTrace?.let { exceptionStacktrace = it }
            attributes.putAll(this@toProto.attributes)
        }

    companion object {
        private const val SERVICE_NAME = "mediamanager-android-tv"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
