package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import io.micrometer.core.instrument.Counter
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

    // Cached download counters — getOrPut lambda runs only on first call per key,
    // subsequent calls hit the ConcurrentHashMap directly (no registry lookup).
    private val downloadBytesCounters = java.util.concurrent.ConcurrentHashMap<String, Counter>()
    private val downloadFileCounters = java.util.concurrent.ConcurrentHashMap<String, Counter>()

    /** Increment bytes streamed for a gRPC file download. */
    fun countDownloadBytes(quality: String, bytes: Long) {
        downloadBytesCounters.getOrPut(quality) {
            registry.counter("mm_download_bytes_total", "quality", quality)
        }.increment(bytes.toDouble())
    }

    /** Increment completed/cancelled/errored download file counter. */
    fun countDownloadFile(status: String) {
        downloadFileCounters.getOrPut(status) {
            registry.counter("mm_download_files_total", "status", status)
        }.increment()
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

    // Live TV metrics
    fun countLiveTvStreamStart() {
        registry.counter("mm_live_tv_stream_starts_total").increment()
    }

    fun countLiveTvStreamBytes(type: String, bytes: Long) {
        registry.counter("mm_live_tv_stream_bytes_total", "type", type).increment(bytes.toDouble())
    }

    fun countLiveTvFfmpegFailure() {
        registry.counter("mm_live_tv_ffmpeg_failures_total").increment()
    }

    fun countLiveTvTunerBusy() {
        registry.counter("mm_live_tv_tuner_busy_total").increment()
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

        // Live TV gauges
        registry.gauge("mm_live_tv_tuners_configured", this) {
            try {
                JdbiOrm.jdbi().withHandle<Double, Exception> { handle ->
                    handle.createQuery("SELECT COUNT(*) FROM live_tv_tuner WHERE enabled = TRUE")
                        .mapTo(Int::class.java)
                        .one()
                        .toDouble()
                }
            } catch (_: Exception) { 0.0 }
        }

        registry.gauge("mm_live_tv_channels_configured", this) {
            try {
                JdbiOrm.jdbi().withHandle<Double, Exception> { handle ->
                    handle.createQuery("SELECT COUNT(*) FROM live_tv_channel WHERE enabled = TRUE")
                        .mapTo(Int::class.java)
                        .one()
                        .toDouble()
                }
            } catch (_: Exception) { 0.0 }
        }

        registry.gauge("mm_live_tv_active_streams", this) {
            LiveTvStreamManager.activeStreamCount().toDouble()
        }
    }
}
