package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

object MetricsRegistry {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    // Camera stream active connection tracking
    private val activeMjpegStreams = AtomicInteger(0)
    private val activeHlsStreams = AtomicInteger(0)

    init {
        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ClassLoaderMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)

        // Camera active stream gauges
        registry.gauge("mm_camera_active_streams", listOf(io.micrometer.core.instrument.Tag.of("type", "mjpeg")), activeMjpegStreams) { it.toDouble() }
        registry.gauge("mm_camera_active_streams", listOf(io.micrometer.core.instrument.Tag.of("type", "hls")), activeHlsStreams) { it.toDouble() }
    }

    fun countHttpResponse(servlet: String, status: Int) {
        registry.counter("mm_http_responses_total", "servlet", servlet, "status", status.toString()).increment()
    }

    /** Increment bytes proxied for a camera stream type (mjpeg, hls, snapshot). */
    fun countCameraStreamBytes(type: String, bytes: Long) {
        registry.counter("mm_camera_stream_bytes_total", "type", type).increment(bytes.toDouble())
    }

    /** Track active MJPEG stream count (long-lived connections). */
    fun mjpegStreamStarted(): Unit { activeMjpegStreams.incrementAndGet().let {} }
    fun mjpegStreamStopped(): Unit { activeMjpegStreams.decrementAndGet().let {} }

    /** Track active HLS stream count. */
    fun hlsStreamStarted(): Unit { activeHlsStreams.incrementAndGet().let {} }
    fun hlsStreamStopped(): Unit { activeHlsStreams.decrementAndGet().let {} }

    /** Increment go2rtc restart counter. */
    fun countGo2rtcRestart() {
        registry.counter("mm_go2rtc_restarts_total").increment()
    }

    fun registerEntityGauges() {
        registry.gauge("mm_active_sessions", this) {
            try {
                JdbiOrm.jdbi().withHandle<Double, Exception> { handle ->
                    handle.createQuery("SELECT COUNT(*) FROM session_token WHERE expires_at > :now")
                        .bind("now", LocalDateTime.now())
                        .mapTo(Int::class.java)
                        .one()
                        .toDouble()
                }
            } catch (_: Exception) { 0.0 }
        }

        // Camera gauges (queried on each Prometheus scrape)
        registry.gauge("mm_cameras_configured", this) {
            try {
                JdbiOrm.jdbi().withHandle<Double, Exception> { handle ->
                    handle.createQuery("SELECT COUNT(*) FROM camera WHERE enabled = TRUE")
                        .mapTo(Int::class.java)
                        .one()
                        .toDouble()
                }
            } catch (_: Exception) { 0.0 }
        }

        registry.gauge("mm_go2rtc_running", this) {
            if (Go2rtcAgent.instance?.currentProcess?.isAlive == true) 1.0 else 0.0
        }
    }
}
