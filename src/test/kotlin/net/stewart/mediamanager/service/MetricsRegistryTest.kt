package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.entity.LoginAttempt
import net.stewart.mediamanager.entity.SessionToken
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [MetricsRegistry] — the Prometheus meter registry singleton.
 *
 * The object is initialized once per JVM, so JVM-binder side effects
 * (memory / GC / thread metrics) are exercised by the load alone.
 * The counter / gauge surfaces are verified by reading values back
 * through the Micrometer API. Counter assertions use deltas because
 * other tests in the suite may have already nudged the counters.
 */
class MetricsRegistryTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:metricstest;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    private fun counterValue(name: String, vararg tags: Pair<String, String>): Double {
        val t = Tags.of(*tags.map { Tag.of(it.first, it.second) }.toTypedArray())
        return MetricsRegistry.registry.find(name).tags(t).counter()?.count() ?: 0.0
    }

    private fun gaugeValue(name: String, vararg tags: Pair<String, String>): Double {
        val t = Tags.of(*tags.map { Tag.of(it.first, it.second) }.toTypedArray())
        return MetricsRegistry.registry.find(name).tags(t).gauge()?.value() ?: Double.NaN
    }

    // ---------------------- counter helpers ----------------------

    @Test
    fun `countHttpResponse increments per servlet+status counter`() {
        val before = counterValue("mm_http_responses_total", "servlet" to "test", "status" to "200")
        MetricsRegistry.countHttpResponse("test", 200)
        MetricsRegistry.countHttpResponse("test", 200)
        assertEquals(before + 2.0, counterValue("mm_http_responses_total",
            "servlet" to "test", "status" to "200"))
        // Different status tag is a different counter.
        val before500 = counterValue("mm_http_responses_total", "servlet" to "test", "status" to "500")
        MetricsRegistry.countHttpResponse("test", 500)
        assertEquals(before500 + 1.0, counterValue("mm_http_responses_total",
            "servlet" to "test", "status" to "500"))
    }

    @Test
    fun `countCameraStreamBytes accumulates by type`() {
        val before = counterValue("mm_camera_stream_bytes_total", "type" to "mjpeg")
        MetricsRegistry.countCameraStreamBytes("mjpeg", 1024)
        MetricsRegistry.countCameraStreamBytes("mjpeg", 2048)
        assertEquals(before + 3072.0,
            counterValue("mm_camera_stream_bytes_total", "type" to "mjpeg"))
    }

    @Test
    fun `countDownloadBytes and countDownloadFile use cached counters per quality and status`() {
        val beforeBytes = counterValue("mm_download_bytes_total", "quality" to "mp4")
        MetricsRegistry.countDownloadBytes("mp4", 500)
        MetricsRegistry.countDownloadBytes("mp4", 250)
        assertEquals(beforeBytes + 750.0,
            counterValue("mm_download_bytes_total", "quality" to "mp4"))

        val beforeFiles = counterValue("mm_download_files_total", "status" to "completed")
        MetricsRegistry.countDownloadFile("completed")
        MetricsRegistry.countDownloadFile("completed")
        assertEquals(beforeFiles + 2.0,
            counterValue("mm_download_files_total", "status" to "completed"))

        // Different status uses a separate cached counter.
        val beforeError = counterValue("mm_download_files_total", "status" to "errored")
        MetricsRegistry.countDownloadFile("errored")
        assertEquals(beforeError + 1.0,
            counterValue("mm_download_files_total", "status" to "errored"))
    }

    @Test
    fun `simple no-tag counters increment`() {
        val pairs = listOf(
            "mm_go2rtc_restarts_total" to MetricsRegistry::countGo2rtcRestart,
            "mm_live_tv_stream_starts_total" to MetricsRegistry::countLiveTvStreamStart,
            "mm_live_tv_ffmpeg_failures_total" to MetricsRegistry::countLiveTvFfmpegFailure,
            "mm_live_tv_tuner_busy_total" to MetricsRegistry::countLiveTvTunerBusy,
        )
        for ((name, fn) in pairs) {
            val before = counterValue(name)
            fn.invoke()
            assertEquals(before + 1.0, counterValue(name), "counter $name did not increment")
        }
    }

    @Test
    fun `countLiveTvStreamBytes accumulates by type`() {
        val before = counterValue("mm_live_tv_stream_bytes_total", "type" to "hls")
        MetricsRegistry.countLiveTvStreamBytes("hls", 4096)
        assertEquals(before + 4096.0,
            counterValue("mm_live_tv_stream_bytes_total", "type" to "hls"))
    }

    // ---------------------- active-stream gauges ----------------------

    @Test
    fun `mjpeg start and stop adjust the active gauge symmetrically`() {
        val before = gaugeValue("mm_camera_active_streams", "type" to "mjpeg")
        MetricsRegistry.mjpegStreamStarted()
        MetricsRegistry.mjpegStreamStarted()
        assertEquals(before + 2.0, gaugeValue("mm_camera_active_streams", "type" to "mjpeg"))
        MetricsRegistry.mjpegStreamStopped()
        MetricsRegistry.mjpegStreamStopped()
        assertEquals(before, gaugeValue("mm_camera_active_streams", "type" to "mjpeg"))
    }

    @Test
    fun `hls start and stop adjust the active gauge symmetrically`() {
        val before = gaugeValue("mm_camera_active_streams", "type" to "hls")
        MetricsRegistry.hlsStreamStarted()
        assertEquals(before + 1.0, gaugeValue("mm_camera_active_streams", "type" to "hls"))
        MetricsRegistry.hlsStreamStopped()
        assertEquals(before, gaugeValue("mm_camera_active_streams", "type" to "hls"))
    }

    @Test
    fun `trackImageStream uses Closeable to decrement on close`() {
        val before = gaugeValue("mm_grpc_active_image_streams")
        val handle1 = MetricsRegistry.trackImageStream()
        val handle2 = MetricsRegistry.trackImageStream()
        assertEquals(before + 2.0, gaugeValue("mm_grpc_active_image_streams"))
        handle1.close()
        handle2.close()
        assertEquals(before, gaugeValue("mm_grpc_active_image_streams"))
    }

    @Test
    fun `trackVideoStream tracks active streams via Closeable`() {
        val before = gaugeValue("mm_video_active_streams")
        MetricsRegistry.trackVideoStream().use {
            assertEquals(before + 1.0, gaugeValue("mm_video_active_streams"))
        }
        assertEquals(before, gaugeValue("mm_video_active_streams"),
            "use { } must decrement on scope exit")
    }

    @Test
    fun `countWatchdogFailure increments the watchdog gauge`() {
        val before = gaugeValue("mm_watchdog_failures_total")
        MetricsRegistry.countWatchdogFailure()
        MetricsRegistry.countWatchdogFailure()
        assertEquals(before + 2.0, gaugeValue("mm_watchdog_failures_total"))
    }

    // ---------------------- registerEntityGauges ----------------------

    @Test
    fun `registerEntityGauges wires DB-backed gauges that read live values`() {
        // Seed two non-expired sessions, one expired session, one locked
        // user, and one camera + tuner + channel so the gauges have
        // concrete numbers to read.
        SessionToken.deleteAll()
        AppUser.deleteAll()
        Camera.deleteAll()
        LiveTvChannel.deleteAll()
        LiveTvTuner.deleteAll()
        LoginAttempt.deleteAll()

        val u1 = AppUser(username = "u1", display_name = "U1", password_hash = "x").apply { save() }
        val u2 = AppUser(username = "u2", display_name = "U2", password_hash = "x",
            locked = true).apply { save() }
        SessionToken(user_id = u1.id!!, token_hash = "t1", user_agent = "ua",
            expires_at = LocalDateTime.now().plusDays(1)).save()
        SessionToken(user_id = u1.id!!, token_hash = "t2", user_agent = "ua",
            expires_at = LocalDateTime.now().plusDays(2)).save()
        SessionToken(user_id = u2.id!!, token_hash = "t3", user_agent = "ua",
            expires_at = LocalDateTime.now().minusDays(1)).save()

        Camera(name = "Front Door", rtsp_url = "rtsp://example.invalid/", enabled = true).save()
        Camera(name = "Disabled", rtsp_url = "rtsp://example.invalid/", enabled = false).save()

        val tuner = LiveTvTuner(name = "Tuner", ip_address = "203.0.113.50",
            tuner_count = 2, enabled = true).apply { save() }
        LiveTvChannel(tuner_id = tuner.id!!, guide_number = "1.1",
            guide_name = "Channel 1", stream_url = "http://example.invalid/",
            enabled = true).save()

        // Register the gauges (idempotent — replaces any prior registrations
        // for the same name+tags).
        MetricsRegistry.registerEntityGauges()

        // Active sessions: 2 non-expired.
        assertEquals(2.0, gaugeValue("mm_active_sessions"))
        // Cameras: 1 enabled.
        assertEquals(1.0, gaugeValue("mm_cameras_configured"))
        // Tuners: 1 enabled.
        assertEquals(1.0, gaugeValue("mm_live_tv_tuners_configured"))
        // Channels: 1 enabled.
        assertEquals(1.0, gaugeValue("mm_live_tv_channels_configured"))
        // Locked accounts: 1.
        assertEquals(1.0, gaugeValue("mm_auth_locked_accounts"))
        // Rate-limited IPs: 0 (no failed attempts seeded).
        assertEquals(0.0, gaugeValue("mm_auth_rate_limited_ips"))
        // go2rtc gauge resolves to 0 when no agent instance.
        assertEquals(0.0, gaugeValue("mm_go2rtc_running"))
    }

    @Test
    fun `JVM binder gauges are present after load`() {
        // ProcessorMetrics, JvmMemoryMetrics etc. attach standard
        // Prometheus metrics on registry init. Spot-check a couple
        // to confirm the binders ran.
        assertNotNull(MetricsRegistry.registry.find("jvm.memory.used").gauge(),
            "JvmMemoryMetrics binder did not register jvm.memory.used")
        assertNotNull(MetricsRegistry.registry.find("system.cpu.count").gauge(),
            "ProcessorMetrics binder did not register system.cpu.count")
        assertTrue(MetricsRegistry.registry.meters.isNotEmpty(),
            "registry should not be empty after init")
    }
}
