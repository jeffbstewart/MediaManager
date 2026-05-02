package net.stewart.mediamanager.grpc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import net.stewart.logging.BinnacleExporter
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Instant

/**
 * gRPC service that forwards log records from iOS / Android TV / web
 * clients to Binnacle. Each client opens a long-lived client-streaming
 * RPC ([streamLogs]) and emits [LogRecord] messages as events occur.
 * The server ships each record through a per-`service.name` OTLP
 * pipeline so the different apps appear as distinct services in
 * Binnacle.
 *
 * Runs behind [AuthInterceptor] — any authenticated user can ship their
 * own client's logs. Records missing a `service_name` are counted as
 * rejected rather than failing the stream, so one malformed record does
 * not sever the connection.
 */
class ObservabilityGrpcService : ObservabilityServiceGrpcKt.ObservabilityServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(ObservabilityGrpcService::class.java)

    override suspend fun streamLogs(requests: Flow<LogRecord>): StreamLogsAck {
        var forwarded = 0L
        var rejected = 0L

        requests.collect { record ->
            if (record.serviceName.isBlank()) {
                rejected++
                return@collect
            }

            val timestamp = if (record.hasTimestamp() && record.timestamp.secondsSinceEpoch > 0)
                Instant.ofEpochSecond(record.timestamp.secondsSinceEpoch)
            else
                Instant.now()

            val accepted = BinnacleExporter.emitFor(
                serviceName = record.serviceName,
                serviceVersion = record.serviceVersion,
                timestamp = timestamp,
                level = mapSeverity(record.severity),
                loggerName = record.loggerName.ifBlank { "client" },
                message = record.message,
                exceptionType = record.exceptionType.takeIf { record.hasExceptionType() },
                exceptionMessage = record.exceptionMessage.takeIf { record.hasExceptionMessage() },
                exceptionStackTrace = record.exceptionStacktrace.takeIf { record.hasExceptionStacktrace() },
                attributes = record.attributesMap
            )
            if (accepted) forwarded++ else rejected++
        }

        // Suppress the audit line on empty streams so transient client
        // reconnects (which carry no records) don't spam the server log.
        // Only audit when the stream actually moved at least one record.
        if (forwarded > 0 || rejected > 0) {
            val user = currentUser()
            log.info("AUDIT: Client logs streamed forwarded={} rejected={} user='{}'",
                forwarded, rejected, user.username)
        }

        return streamLogsAck {
            recordsForwarded = forwarded
            recordsRejected = rejected
        }
    }

    private fun mapSeverity(severity: LogSeverity): Level = when (severity) {
        LogSeverity.LOG_SEVERITY_TRACE -> Level.TRACE
        LogSeverity.LOG_SEVERITY_DEBUG -> Level.DEBUG
        LogSeverity.LOG_SEVERITY_WARN -> Level.WARN
        LogSeverity.LOG_SEVERITY_ERROR -> Level.ERROR
        // INFO and UNKNOWN both map to INFO — safe default.
        else -> Level.INFO
    }
}
