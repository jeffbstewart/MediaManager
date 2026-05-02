package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Camera
import net.stewart.transcode.StreamingProcess
import net.stewart.transcode.Subprocesses
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
 * Streams are configured via a temp YAML config file written at startup and deleted on shutdown.
 * All process stdout/stderr is filtered through [UriCredentialRedactor.redactAll] before logging.
 */
class Go2rtcAgent(
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(Go2rtcAgent::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    /** Live process handle; tests reach into this to assert lifecycle. */
    @Volatile internal var currentProcess: StreamingProcess? = null
    @Volatile var apiPort: Int = 1984

    companion object {
        private const val DEFAULT_API_PORT = 1984
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
            log.warn("go2rtc binary not found, agent not starting. Set app_config key 'go2rtc_path' or install at {}", DEFAULT_GO2RTC_PATH)
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

    /** Reconfigure streams after camera CRUD. Restarts the process with a new config file. */
    fun reconfigure() {
        val cameras = Camera.findAll().filter { it.enabled }
        if (cameras.isEmpty()) {
            log.info("No enabled cameras, stopping go2rtc")
            stop()
            return
        }

        log.info("Reconfiguring go2rtc ({} cameras), restarting process", cameras.size)
        stop()
        start()
    }

    private fun runProcess(go2rtcPath: String, cameras: List<Camera>) {
        // Write a temp YAML config with API settings + stream definitions.
        // go2rtc's inline JSON config (-c '{...}') disables the config file endpoint,
        // so we must use a file for streams to be recognized.
        val configFile = File.createTempFile("go2rtc-", ".yaml")
        try {
            // Locate FFmpeg for H.264→MJPEG transcoding (required for MJPEG/snapshot endpoints)
            val ffmpegPath = findFfmpegPath()
            configFile.writeText(buildString {
                appendLine("api:")
                appendLine("  listen: \"127.0.0.1:$apiPort\"")
                if (ffmpegPath != null) {
                    appendLine("ffmpeg:")
                    appendLine("  bin: \"$ffmpegPath\"")
                }
                appendLine("streams:")
                for (cam in cameras) {
                    appendLine("  ${cam.go2rtc_name}:")
                    appendLine("    - ${cam.rtsp_url}")
                    if (ffmpegPath != null) {
                        // Add FFmpeg source to decode H.264→MJPEG for browser/snapshot use
                        appendLine("    - \"ffmpeg:${cam.go2rtc_name}#video=mjpeg\"")
                    }
                }
            })
            log.info("Wrote go2rtc config to {} ({} streams): {}", configFile.absolutePath, cameras.size,
                UriCredentialRedactor.redactAll(configFile.readText().replace("\n", " | ")))

            val command = listOf(go2rtcPath, "-c", configFile.absolutePath)

            log.info("Starting go2rtc: {} ({} cameras)", go2rtcPath, cameras.size)
            val process = Subprocesses.current.start(command, redirectErrorStream = true)
            currentProcess = process

            // Read stdout/stderr in a separate thread, filtering credentials
            val outputThread = Thread({
                try {
                    BufferedReader(InputStreamReader(process.stdout)).use { reader ->
                        reader.forEachLine { line ->
                            val redacted = UriCredentialRedactor.redactAll(line)
                            log.info("go2rtc: {}", redacted)
                        }
                    }
                } catch (_: Exception) {
                    // Process destroyed
                }
            }, "go2rtc-output").apply {
                isDaemon = true
                start()
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
        } finally {
            // Always delete the credential-bearing config file
            if (configFile.exists()) {
                configFile.delete()
                log.info("Deleted go2rtc config file {}", configFile.absolutePath)
            }
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
        if (!configured.isNullOrBlank()) {
            if (File(configured).exists()) {
                log.info("Using configured go2rtc path: {}", configured)
                return configured
            }
            log.warn("Configured go2rtc path '{}' does not exist on disk", configured)
        }
        val defaultFile = File(DEFAULT_GO2RTC_PATH)
        if (defaultFile.exists()) {
            log.info("Using default go2rtc path: {} (executable={})", DEFAULT_GO2RTC_PATH, defaultFile.canExecute())
            return DEFAULT_GO2RTC_PATH
        }
        log.warn("go2rtc binary not found at default path '{}' and no app_config key 'go2rtc_path' set", DEFAULT_GO2RTC_PATH)
        return null
    }

    private fun readApiPort(): Int {
        return AppConfig.findAll()
            .firstOrNull { it.config_key == "go2rtc_api_port" }
            ?.config_val?.toIntOrNull() ?: DEFAULT_API_PORT
    }

    /** Find FFmpeg binary — check app_config, then common paths. */
    private fun findFfmpegPath(): String? {
        val configured = AppConfig.findAll().firstOrNull { it.config_key == "ffmpeg_path" }?.config_val
        if (!configured.isNullOrBlank() && File(configured).exists()) {
            log.info("Using configured FFmpeg path for go2rtc: {}", configured)
            return configured
        }
        for (path in listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg")) {
            if (File(path).exists()) {
                log.info("Found FFmpeg for go2rtc at: {}", path)
                return path
            }
        }
        log.warn("FFmpeg not found — go2rtc MJPEG/snapshot will not work for H.264 streams")
        return null
    }
}
