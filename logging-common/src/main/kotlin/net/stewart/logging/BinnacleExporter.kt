package net.stewart.logging

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import org.slf4j.event.Level
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Ships log records from the custom SLF4J pipeline to a Binnacle
 * instance via OTLP/HTTP. Backed by the OTel Java SDK's
 * [BatchLogRecordProcessor] so the logging path is never blocked on
 * network I/O — records are queued in memory and flushed in the
 * background.
 *
 * Opt-in: if the env vars are absent, [init] returns
 * [Status.NOT_CONFIGURED] and [emit] is a no-op.
 */
object BinnacleExporter {

    /** System property read from the environment. Base URL, e.g. http://172.16.4.12:4318. */
    private const val PROP_ENDPOINT = "BINNACLE_ENDPOINT"

    /** System property read from the environment. Shared write-path API key. */
    private const val PROP_API_KEY = "BINNACLE_API_KEY"

    private val ATTR_THREAD_NAME = AttributeKey.stringKey("thread.name")
    private val ATTR_CODE_NAMESPACE = AttributeKey.stringKey("code.namespace")
    private val ATTR_EXCEPTION_TYPE = AttributeKey.stringKey("exception.type")
    private val ATTR_EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message")
    private val ATTR_EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace")

    @Volatile
    private var loggerProvider: SdkLoggerProvider? = null

    @Volatile
    private var otelLogger: io.opentelemetry.api.logs.Logger? = null

    /** Result of [init]: why the exporter is or isn't running. */
    enum class Status {
        /** Exporter running — logs are shipping. */
        ENABLED,
        /** BINNACLE_ENDPOINT or BINNACLE_API_KEY not set. */
        NOT_CONFIGURED,
        /** Probe flush to Binnacle failed (connectivity or auth). */
        PROBE_FAILED,
    }

    /** Human-readable detail when status is [Status.PROBE_FAILED]. */
    var probeError: String? = null
        private set

    /**
     * Initializes the OTLP exporter from system properties. Builds
     * the full OTel pipeline, emits a probe record, and force-flushes
     * to verify both connectivity and auth through the real transport
     * — no duplicate HTTP code. If the flush fails, the pipeline is
     * torn down and [emit] remains a no-op.
     *
     * Safe to call more than once — subsequent calls are no-ops.
     *
     * @param serviceName OTel `service.name` resource attribute. Each
     *   app using this module passes its own identity (e.g.
     *   `mediamanager-server`, `mediamanager-buddy`, `mediamanager-tv`)
     *   so Binnacle can filter by it.
     * @param serviceVersion OTel `service.version` resource attribute.
     *   Defaults to `"dev"` if the caller doesn't have a version string.
     */
    fun init(serviceName: String, serviceVersion: String = "dev"): Status {
        if (otelLogger != null) return Status.ENABLED

        val endpoint = System.getProperty(PROP_ENDPOINT)
        val apiKey = System.getProperty(PROP_API_KEY)
        if (endpoint.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Status.NOT_CONFIGURED
        }

        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "unknown"
        }

        val resource = Resource.builder()
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .put(AttributeKey.stringKey("service.version"), serviceVersion)
            .put(AttributeKey.stringKey("service.instance.id"), hostname)
            .build()

        // Normalize: prepend http:// if the user gave a bare host:port
        // (e.g. "172.16.4.12:4318"), then append /v1/logs.
        val base = if (endpoint.startsWith("http://") || endpoint.startsWith("https://"))
            endpoint else "http://$endpoint"
        val fullEndpoint = base.trimEnd('/') + "/v1/logs"

        val exporter = OtlpHttpLogRecordExporter.builder()
            .setEndpoint(fullEndpoint)
            .addHeader("X-Logging-Api-Key", apiKey)
            .build()

        val processor = BatchLogRecordProcessor.builder(exporter).build()

        val provider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(processor)
            .build()

        // Probe: emit a test record and force-flush through the real
        // pipeline. This exercises the same HTTP transport, endpoint
        // URL, and auth header that production records will use — if
        // the key is wrong or the endpoint is unreachable, the flush
        // fails and we tear down before enabling the exporter.
        val probeLogger = provider.get("net.stewart.logging")
        probeLogger.logRecordBuilder()
            .setTimestamp(Instant.now())
            .setSeverity(Severity.INFO)
            .setSeverityText("INFO")
            .setBody("$serviceName log export starting")
            .emit()

        val result = provider.forceFlush().join(5, TimeUnit.SECONDS)
        if (!result.isSuccess) {
            provider.shutdown().join(2, TimeUnit.SECONDS)
            probeError = "flush to $fullEndpoint failed — check BINNACLE_ENDPOINT and BINNACLE_API_KEY"
            return Status.PROBE_FAILED
        }

        loggerProvider = provider
        otelLogger = probeLogger
        return Status.ENABLED
    }

    /**
     * Emits a single log record to Binnacle. No-op if [init] was never
     * called or Binnacle is not configured. Never throws — a broken
     * exporter must not break local logging.
     */
    fun emit(
        level: Level,
        loggerName: String,
        message: String,
        throwable: Throwable?,
        threadName: String
    ) {
        val logger = otelLogger ?: return
        try {
            val builder = logger.logRecordBuilder()
                .setTimestamp(Instant.now())
                .setSeverity(mapSeverity(level))
                .setSeverityText(level.name)
                .setBody(message)
                .setAttribute(ATTR_THREAD_NAME, threadName)
                .setAttribute(ATTR_CODE_NAMESPACE, loggerName)

            if (throwable != null) {
                builder.setAttribute(ATTR_EXCEPTION_TYPE, throwable.javaClass.name)
                builder.setAttribute(ATTR_EXCEPTION_MESSAGE, throwable.message ?: "")
                builder.setAttribute(ATTR_EXCEPTION_STACKTRACE, throwable.stackTraceToString())
            }

            builder.emit()
        } catch (_: Exception) {
            // Swallow: the batch processor may be shutting down or the
            // exporter might be in an unexpected state. Local logging
            // (stderr + ring buffer) already captured this record.
        }
    }

    /**
     * Flushes pending records and shuts down the exporter. Called from
     * the shutdown hook so in-flight logs land in Binnacle before the
     * process exits.
     */
    fun shutdown() {
        loggerProvider?.shutdown()?.join(5, TimeUnit.SECONDS)
    }

    private fun mapSeverity(level: Level): Severity = when (level) {
        Level.TRACE -> Severity.TRACE
        Level.DEBUG -> Severity.DEBUG
        Level.INFO -> Severity.INFO
        Level.WARN -> Severity.WARN
        Level.ERROR -> Severity.ERROR
    }
}
