package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A cached HLS segment with its relay-assigned sequence number and data.
 */
data class CachedSegment(
    val sequenceNumber: Long,
    val data: ByteArray,
    val durationSeconds: Double
)

/**
 * Per-camera HLS relay that maintains a persistent connection to go2rtc's HLS endpoint
 * and serves segments from a server-side ring buffer.
 *
 * The relay:
 * 1. Fetches the master playlist to get a session ID
 * 2. Polls the variant playlist every ~500ms to discover new segments
 * 3. Fetches each new segment and adds it to the ring buffer
 * 4. Restarts from step 1 if the session dies (404/502)
 *
 * Clients request a generated m3u8 playlist and segments from the ring buffer,
 * decoupled from go2rtc's ephemeral session lifecycle.
 */
class HlsCameraRelay(
    val cameraId: Long,
    private val go2rtcStreamName: String,
    private val apiPort: Int
) {
    private val log = LoggerFactory.getLogger(HlsCameraRelay::class.java)

    companion object {
        private const val RING_BUFFER_SIZE = 10
        private const val POLL_INTERVAL_MS = 500L
        private const val DEFAULT_SEGMENT_DURATION = 0.5
    }

    private val running = AtomicBoolean(false)
    private val lastAccessTime = AtomicLong(System.currentTimeMillis())
    private val nextSequenceNumber = AtomicLong(0)

    // Ring buffer protected by read-write lock for concurrent client reads
    private val bufferLock = ReentrantReadWriteLock()
    private val segments = ArrayDeque<CachedSegment>(RING_BUFFER_SIZE + 1)

    // Track which go2rtc segment numbers we have already fetched to avoid duplicates
    private val fetchedGo2rtcSegments = mutableSetOf<String>()

    @Volatile
    private var pollingThread: Thread? = null

    @Volatile
    private var targetDuration: Double = DEFAULT_SEGMENT_DURATION

    /** Mark that a client accessed this relay (resets idle timeout). */
    fun touch() {
        lastAccessTime.set(System.currentTimeMillis())
    }

    /** Returns millis since last client access. */
    fun idleMillis(): Long = System.currentTimeMillis() - lastAccessTime.get()

    /** Start the polling loop in a background thread. */
    fun start() {
        if (running.getAndSet(true)) return
        log.info("Starting HLS relay for camera {} (stream '{}')", cameraId, go2rtcStreamName)

        val thread = Thread({
            pollLoop()
        }, "hls-relay-cam-$cameraId")
        thread.isDaemon = true
        thread.start()
        pollingThread = thread
    }

    /** Stop the relay and clean up. */
    fun stop() {
        if (!running.getAndSet(false)) return
        log.info("Stopping HLS relay for camera {}", cameraId)
        pollingThread?.interrupt()
        pollingThread = null
        bufferLock.write {
            segments.clear()
        }
        fetchedGo2rtcSegments.clear()
    }

    fun isRunning(): Boolean = running.get()

    /**
     * Generate an m3u8 playlist from the current ring buffer contents.
     * Returns null if no segments are available yet.
     */
    fun generatePlaylist(baseUrl: String, keyParam: String): String? {
        touch()
        val currentSegments = bufferLock.read {
            if (segments.isEmpty()) return null
            segments.toList()
        }

        val oldestSeq = currentSegments.first().sequenceNumber
        val td = Math.ceil(targetDuration).toInt().coerceAtLeast(1)

        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:3")
        sb.appendLine("#EXT-X-TARGETDURATION:$td")
        sb.appendLine("#EXT-X-MEDIA-SEQUENCE:$oldestSeq")

        for (seg in currentSegments) {
            sb.appendLine("#EXTINF:${String.format("%.3f", seg.durationSeconds)},")
            val separator = if (keyParam.isNotEmpty()) {
                val cleanKey = keyParam.removePrefix("?")
                "?$cleanKey"
            } else {
                ""
            }
            sb.appendLine("$baseUrl/cam/$cameraId/hls/segment/${seg.sequenceNumber}$separator")
        }

        return sb.toString()
    }

    /**
     * Get a segment by its relay sequence number.
     * Returns null if the segment has been evicted or doesn't exist yet.
     */
    fun getSegment(sequenceNumber: Long): ByteArray? {
        touch()
        return bufferLock.read {
            segments.firstOrNull { it.sequenceNumber == sequenceNumber }?.data
        }
    }

    // -- Private polling loop --

    private fun pollLoop() {
        while (running.get()) {
            try {
                runSession()
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                log.warn(
                    "HLS relay session error for camera {} (stream '{}'): {}",
                    cameraId, go2rtcStreamName, UriCredentialRedactor.redactAll(e.message ?: "")
                )
            }

            // If still running, wait before retrying with a new session
            if (running.get()) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        log.info("HLS relay polling loop exited for camera {}", cameraId)
    }

    /**
     * Run a single go2rtc HLS session: fetch master playlist, extract session ID,
     * then poll variant playlist for new segments until the session dies.
     */
    private fun runSession() {
        // Step 1: Fetch master playlist to get session ID
        val masterUrl = "http://127.0.0.1:$apiPort/api/stream.m3u8?src=$go2rtcStreamName"
        val masterBody = fetchText(masterUrl) ?: return

        val variantRelUrl = masterBody.lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }

        if (variantRelUrl == null) {
            log.warn("go2rtc master playlist has no variant URL for camera {}", cameraId)
            return
        }

        val variantUrl = "http://127.0.0.1:$apiPort/api/$variantRelUrl"
        log.info("HLS relay for camera {}: started session (variant: {})", cameraId, variantRelUrl)

        // Reset fetched segment tracking for new session
        fetchedGo2rtcSegments.clear()

        // Step 2: Poll variant playlist for new segments
        var consecutiveErrors = 0
        while (running.get()) {
            val variantBody = fetchText(variantUrl)
            if (variantBody == null) {
                consecutiveErrors++
                if (consecutiveErrors >= 3) {
                    log.info("HLS relay for camera {}: session expired after {} errors, restarting", cameraId, consecutiveErrors)
                    return
                }
                Thread.sleep(POLL_INTERVAL_MS)
                continue
            }
            consecutiveErrors = 0

            // Parse the variant playlist
            parseAndFetchSegments(variantBody, variantRelUrl)

            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /**
     * Parse a variant playlist body and fetch any new segments we haven't seen.
     */
    private fun parseAndFetchSegments(variantBody: String, variantRelUrl: String) {
        // Derive base path for resolving relative segment URLs
        // e.g., "hls/playlist.m3u8?id=xxx" -> base path is "hls/"
        val basePath = variantRelUrl.substringBeforeLast("/", "").let {
            if (it.isNotEmpty()) "$it/" else ""
        }

        val lines = variantBody.lines()
        var currentDuration = DEFAULT_SEGMENT_DURATION

        // Parse TARGETDURATION for our generated playlist
        for (line in lines) {
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                val td = line.substringAfter(":").trim().toDoubleOrNull()
                if (td != null) targetDuration = td
            }
        }

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXTINF:")) {
                currentDuration = line.substringAfter(":").substringBefore(",").trim().toDoubleOrNull()
                    ?: DEFAULT_SEGMENT_DURATION
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                // This is a segment reference line (e.g., "segment.ts?id=xxx&n=0")
                val segmentKey = line.trim()
                if (segmentKey in fetchedGo2rtcSegments) continue

                // Resolve the segment URL
                val segmentUrl = if (line.startsWith("http")) {
                    line.trim()
                } else {
                    "http://127.0.0.1:$apiPort/api/$basePath${line.trim()}"
                }

                // Fetch the segment data
                val data = fetchBinary(segmentUrl)
                if (data != null) {
                    val seqNum = nextSequenceNumber.getAndIncrement()
                    val cached = CachedSegment(seqNum, data, currentDuration)

                    bufferLock.write {
                        segments.addLast(cached)
                        while (segments.size > RING_BUFFER_SIZE) {
                            segments.removeFirst()
                        }
                    }

                    fetchedGo2rtcSegments.add(segmentKey)
                    MetricsRegistry.countCameraStreamBytes("hls-relay-fetch", data.size.toLong())
                }
            }
        }
    }

    private fun fetchText(url: String): String? {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            try {
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            log.debug(
                "HLS relay fetch failed for camera {}: {}",
                cameraId, UriCredentialRedactor.redactAll(e.message ?: "")
            )
            null
        }
    }

    private fun fetchBinary(url: String): ByteArray? {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            try {
                conn.inputStream.readBytes()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            log.debug(
                "HLS relay segment fetch failed for camera {}: {}",
                cameraId, UriCredentialRedactor.redactAll(e.message ?: "")
            )
            null
        }
    }
}


/**
 * Singleton manager for HLS camera relays. Creates relays on-demand and evicts
 * idle ones after 30 seconds of no client access.
 */
object HlsRelayManager {

    private val log = LoggerFactory.getLogger(HlsRelayManager::class.java)

    private const val IDLE_TIMEOUT_MS = 30_000L
    private const val CLEANUP_INTERVAL_MS = 5_000L
    private const val MAX_CONCURRENT_RELAYS = 6

    private val relays = ConcurrentHashMap<Long, HlsCameraRelay>()
    private val threadPool = Executors.newFixedThreadPool(MAX_CONCURRENT_RELAYS) { r ->
        Thread(r, "hls-relay-pool").also { it.isDaemon = true }
    }

    @Volatile
    private var cleanupScheduler: ScheduledExecutorService? = null

    init {
        startCleanup()
    }

    /**
     * Get or create a relay for the given camera. Starts the relay if it's new.
     * Returns null if the maximum number of concurrent relays has been reached.
     */
    fun getOrCreateRelay(cameraId: Long, go2rtcStreamName: String, apiPort: Int): HlsCameraRelay? {
        val existing = relays[cameraId]
        if (existing != null && existing.isRunning()) {
            existing.touch()
            return existing
        }

        // Check capacity before creating a new relay
        if (relays.size >= MAX_CONCURRENT_RELAYS && !relays.containsKey(cameraId)) {
            log.warn("HLS relay limit reached ({}), cannot create relay for camera {}", MAX_CONCURRENT_RELAYS, cameraId)
            return null
        }

        val relay = HlsCameraRelay(cameraId, go2rtcStreamName, apiPort)
        relays[cameraId] = relay
        threadPool.submit { relay.start() }
        return relay
    }

    /** Get an existing relay without creating one. */
    fun getRelay(cameraId: Long): HlsCameraRelay? {
        return relays[cameraId]?.also { it.touch() }
    }

    /** Stop and remove all relays. */
    fun stopAll() {
        log.info("Stopping all HLS relays")
        relays.values.forEach { it.stop() }
        relays.clear()
        cleanupScheduler?.shutdownNow()
        cleanupScheduler = null
    }

    /** Number of active relays (for metrics/diagnostics). */
    fun activeRelayCount(): Int = relays.size

    private fun startCleanup() {
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hls-relay-cleanup").also { it.isDaemon = true }
        }
        scheduler.scheduleAtFixedRate({
            try {
                evictIdleRelays()
            } catch (e: Exception) {
                log.warn("HLS relay cleanup error: {}", e.message)
            }
        }, CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS)
        cleanupScheduler = scheduler
    }

    private fun evictIdleRelays() {
        val iterator = relays.entries.iterator()
        while (iterator.hasNext()) {
            val (cameraId, relay) = iterator.next()
            if (relay.idleMillis() > IDLE_TIMEOUT_MS) {
                log.info("Evicting idle HLS relay for camera {} (idle {}ms)", cameraId, relay.idleMillis())
                relay.stop()
                iterator.remove()
            }
        }
    }
}
