package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Camera
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Manages the go2rtc child process for camera stream relay.
 *
 * go2rtc provides RTSP→HLS and RTSP→MJPEG conversion, binding to 127.0.0.1:1984.
 * Streams are configured via HTTP API after process startup (no credential-bearing YAML on disk).
 * All process stdout/stderr is filtered through [UriCredentialRedactor.redactAll] before logging.
 */
class Go2rtcAgent(
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(Go2rtcAgent::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile var currentProcess: Process? = null
    @Volatile var apiPort: Int = 1984

    companion object {
        private const val DEFAULT_API_PORT = 1984
        private const val STARTUP_WAIT_MS = 3000L
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
        private const val DEFAULT_GO2RTC_PATH = "/usr/local/bin/go2rtc"

        /** Singleton for access from servlets. */
        @Volatile var instance: Go2rtcAgent? = null
    }

    fun start() {
        if (running.getAndSet(true)) return

        val cameras = Camera.findAll().filter { it.enabled }
        if (cameras.isEmpty()) {
            log.info("No cameras configured, go2rtc agent not starting")
            running.set(false)
            return
        }

        val go2rtcPath = getGo2rtcPath()
        if (go2rtcPath.isNullOrBlank()) {
            log.warn("go2rtc binary path not configured (app_config key 'go2rtc_path'), agent not starting")
            running.set(false)
            return
        }

        apiPort = readApiPort()

        thread = Thread({
            log.info("Go2rtc Agent starting")
            while (running.get()) {
                try {
                    runProcess(go2rtcPath, cameras)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("Go2rtc agent error: {}", UriCredentialRedactor.redactAll(e.message ?: ""), e)
                }
                if (running.get()) {
                    MetricsRegistry.countGo2rtcRestart()
                    log.info("go2rtc exited, restarting in 10 seconds...")
                    try {
                        clock.sleep(10.seconds)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            log.info("Go2rtc Agent stopped")
        }, "go2rtc-agent").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        currentProcess?.destroyForcibly()
        currentProcess = null
        thread?.interrupt()
        thread = null
    }

    /** Reconfigure streams after camera CRUD. Restarts the process if running. */
    fun reconfigure() {
        val cameras = Camera.findAll().filter { it.enabled }
        if (cameras.isEmpty()) {
            log.info("No enabled cameras, stopping go2rtc")
            stop()
            return
        }

        // Try to configure via API first (avoids restart)
        if (currentProcess?.isAlive == true && configureStreamsViaApi(cameras)) {
            log.info("go2rtc streams reconfigured via API ({} cameras)", cameras.size)
            return
        }

        // Restart process if API config failed or process not running
        val wasRunning = running.get()
        stop()
        if (wasRunning || cameras.isNotEmpty()) {
            start()
        }
    }

    private fun runProcess(go2rtcPath: String, cameras: List<Camera>) {
        val command = mutableListOf(go2rtcPath)
        // Disable all listeners except API (no external RTSP/WebRTC)
        command.addAll(listOf(
            "-listen", "127.0.0.1:$apiPort",
            "-rtsp", "",      // disable RTSP listener
            "-webrtc", ""     // disable WebRTC listener
        ))

        log.info("Starting go2rtc: {} ({} cameras)", go2rtcPath, cameras.size)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        currentProcess = process

        // Read stdout/stderr in a separate thread, filtering credentials
        val outputThread = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        val redacted = UriCredentialRedactor.redactAll(line)
                        log.debug("go2rtc: {}", redacted)
                    }
                }
            } catch (_: Exception) {
                // Process destroyed
            }
        }, "go2rtc-output").apply {
            isDaemon = true
            start()
        }

        // Wait for API to become available
        clock.sleep(STARTUP_WAIT_MS.milliseconds)

        // Configure streams via HTTP API
        if (!configureStreamsViaApi(cameras)) {
            log.error("Failed to configure go2rtc streams via API")
        }

        // Monitor process health
        while (running.get() && process.isAlive) {
            try {
                clock.sleep(HEALTH_CHECK_INTERVAL_MS.milliseconds)
            } catch (_: InterruptedException) {
                break
            }
        }

        if (process.isAlive) {
            process.destroyForcibly()
        }
        currentProcess = null
        outputThread.interrupt()
    }

    /** Configure go2rtc streams via its HTTP API. Returns true on success. */
    private fun configureStreamsViaApi(cameras: List<Camera>): Boolean {
        return try {
            for (camera in cameras) {
                val url = URI("http://127.0.0.1:$apiPort/api/streams?dst=${camera.go2rtc_name}&src=${java.net.URLEncoder.encode(camera.rtsp_url, "UTF-8")}").toURL()
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        log.warn("go2rtc API returned {} for camera '{}': {}", code, camera.name,
                            UriCredentialRedactor.redactAll(conn.errorStream?.bufferedReader()?.readText() ?: ""))
                        return false
                    }
                    log.info("Configured go2rtc stream '{}' for camera '{}'", camera.go2rtc_name, camera.name)
                } finally {
                    conn.disconnect()
                }
            }
            true
        } catch (e: Exception) {
            log.warn("Failed to configure go2rtc via API: {}", UriCredentialRedactor.redactAll(e.message ?: ""))
            false
        }
    }

    /** Check if go2rtc API is reachable. */
    fun isHealthy(): Boolean {
        return try {
            val url = URI("http://127.0.0.1:$apiPort/api/streams").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getGo2rtcPath(): String? {
        val configured = AppConfig.findAll().firstOrNull { it.config_key == "go2rtc_path" }?.config_val
        if (configured != null && File(configured).exists()) return configured
        if (File(DEFAULT_GO2RTC_PATH).exists()) return DEFAULT_GO2RTC_PATH
        return configured // return configured even if not found, so error message is useful
    }

    private fun readApiPort(): Int {
        return AppConfig.findAll()
            .firstOrNull { it.config_key == "go2rtc_api_port" }
            ?.config_val?.toIntOrNull() ?: DEFAULT_API_PORT
    }
}
