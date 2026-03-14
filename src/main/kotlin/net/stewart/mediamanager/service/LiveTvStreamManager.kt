package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.transcode.sanitizeFfmpegOutput
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a single active live TV HLS stream backed by an FFmpeg process.
 */
class LiveTvStream(
    val channelId: Long,
    val userId: Long,
    private val process: Process,
    val outputDir: File,
    private val stderrThread: Thread
) {
    private val lastTouched = AtomicLong(System.currentTimeMillis())

    fun touch() { lastTouched.set(System.currentTimeMillis()) }
    fun idleMillis(): Long = System.currentTimeMillis() - lastTouched.get()
    fun isHealthy(): Boolean = process.isAlive

    fun stop() {
        try { process.destroyForcibly() } catch (_: Exception) {}
        try { stderrThread.interrupt() } catch (_: Exception) {}
        // Clean up output directory
        try {
            outputDir.listFiles()?.forEach { it.delete() }
            outputDir.delete()
        } catch (_: Exception) {}
    }

    fun getPlaylistFile(): File = File(outputDir, "playlist.m3u8")
    fun getSegmentFile(name: String): File = File(outputDir, name)
}

/**
 * Manages FFmpeg processes for live TV HLS streaming.
 * Enforces global max streams, per-tuner limits, and per-user stream replacement.
 */
object LiveTvStreamManager {
    private val log = LoggerFactory.getLogger(LiveTvStreamManager::class.java)

    // channelId → active stream
    private val streams = ConcurrentHashMap<Long, LiveTvStream>()
    // userId → channelId (for per-user replacement)
    private val userStreams = ConcurrentHashMap<Long, Long>()

    private var scheduler: ScheduledExecutorService? = null
    private val SEGMENT_PATTERN = Regex("seg_\\d+\\.ts")
    private val STREAMS_DIR = File("data/live-tv-streams")

    fun start() {
        // Clean up stale dirs from previous runs
        cleanupStaleDirs()

        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "live-tv-idle-cleanup").apply { isDaemon = true }
        }
        scheduler?.scheduleAtFixedRate({
            try { cleanupIdleStreams() } catch (e: Exception) {
                log.warn("Idle stream cleanup error: {}", e.message)
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    fun stopAll() {
        scheduler?.shutdownNow()
        for (stream in streams.values) {
            try { stream.stop() } catch (_: Exception) {}
        }
        streams.clear()
        userStreams.clear()
        cleanupStaleDirs()
    }

    /**
     * Stops any existing stream for the given user.
     */
    fun stopUserStream(userId: Long) {
        val existingChannelId = userStreams.remove(userId) ?: return
        val stream = streams.remove(existingChannelId)
        if (stream != null) {
            log.info("Stopping user {}'s stream on channel {} (channel switch)", userId, existingChannelId)
            stream.stop()
            MetricsRegistry.countLiveTvStreamBytes("cleanup", 0)
        }
    }

    /**
     * Gets or creates a stream for the given channel. Enforces concurrency limits.
     * Returns the stream, or null with an error reason.
     */
    fun getOrCreateStream(channel: LiveTvChannel, userId: Long): Pair<LiveTvStream?, String?> {
        val chId = channel.id!!

        // If stream already exists and is healthy, just touch and return it
        val existing = streams[chId]
        if (existing != null && existing.isHealthy()) {
            existing.touch()
            return existing to null
        }

        // Clean up dead stream if present
        if (existing != null) {
            streams.remove(chId)
            userStreams.entries.removeIf { it.value == chId }
            existing.stop()
            MetricsRegistry.countLiveTvFfmpegFailure()
        }

        // Stop any existing stream for this user (channel switch)
        stopUserStream(userId)

        // Check global max
        val maxStreams = getConfigInt("live_tv_max_streams", 2)
        val activeCount = streams.values.count { it.isHealthy() }
        if (activeCount >= maxStreams) {
            MetricsRegistry.countLiveTvTunerBusy()
            return null to "Too many active streams ($activeCount/$maxStreams)"
        }

        // Check tuner limit
        val tuner = LiveTvTuner.findById(channel.tuner_id)
        if (tuner != null) {
            val tunerStreams = streams.values.count { stream ->
                val ch = try { LiveTvChannel.findById(stream.channelId) } catch (_: Exception) { null }
                ch?.tuner_id == tuner.id && stream.isHealthy()
            }
            if (tunerStreams >= tuner.tuner_count) {
                MetricsRegistry.countLiveTvTunerBusy()
                return null to "All tuners busy for ${tuner.name} ($tunerStreams/${tuner.tuner_count})"
            }
        }

        // Create output directory
        val outputDir = File(STREAMS_DIR, "ch-$chId")
        outputDir.mkdirs()

        // Build FFmpeg command
        val ffmpegPath = getConfigString("ffmpeg_path", "ffmpeg")
        val cmd = listOf(
            ffmpegPath, "-i", channel.stream_url,
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-crf", "23",
            "-profile:v", "high", "-level:v", "4.1", "-pix_fmt", "yuv420p",
            "-vf", "yadif,setsar=1",
            "-c:a", "aac", "-ac", "2", "-ar", "48000", "-b:a", "192k",
            "-f", "hls", "-hls_time", "4", "-hls_list_size", "5",
            "-hls_flags", "delete_segments",
            "-hls_segment_filename", File(outputDir, "seg_%d.ts").absolutePath,
            File(outputDir, "playlist.m3u8").absolutePath
        )

        return try {
            log.info("Starting live TV stream for channel {} ({}): {}", chId, channel.guide_name, channel.guide_number)
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .directory(outputDir)
                .start()

            // Consume stdin to prevent blocking
            process.outputStream.close()

            // Stderr reader thread (filtered through sanitizeFfmpegOutput)
            val stderrThread = Thread({
                try {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        val sanitized = sanitizeFfmpegOutput(line)
                        if (sanitized.isNotBlank()) {
                            log.debug("FFmpeg [ch-{}]: {}", chId, sanitized)
                        }
                    }
                } catch (_: Exception) {}
            }, "ffmpeg-stderr-ch-$chId").apply { isDaemon = true; start() }

            val stream = LiveTvStream(chId, userId, process, outputDir, stderrThread)
            streams[chId] = stream
            userStreams[userId] = chId
            MetricsRegistry.countLiveTvStreamStart()

            stream to null
        } catch (e: Exception) {
            log.error("Failed to start FFmpeg for channel {}: {}", chId, e.message)
            MetricsRegistry.countLiveTvFfmpegFailure()
            null to "Failed to start stream: ${e.message}"
        }
    }

    fun getStream(channelId: Long): LiveTvStream? = streams[channelId]

    fun activeStreamCount(): Int = streams.values.count { it.isHealthy() }

    fun isValidSegmentName(name: String): Boolean = SEGMENT_PATTERN.matches(name)

    private fun cleanupIdleStreams() {
        val timeoutMs = getConfigInt("live_tv_idle_timeout_seconds", 15) * 1000L
        val toRemove = mutableListOf<Long>()

        for ((channelId, stream) in streams) {
            if (!stream.isHealthy()) {
                log.info("FFmpeg process died for channel {}, cleaning up", channelId)
                stream.stop()
                toRemove.add(channelId)
                MetricsRegistry.countLiveTvFfmpegFailure()
            } else if (stream.idleMillis() > timeoutMs) {
                log.info("Stream for channel {} idle for {}ms, stopping", channelId, stream.idleMillis())
                stream.stop()
                toRemove.add(channelId)
            }
        }

        for (channelId in toRemove) {
            streams.remove(channelId)
            userStreams.entries.removeIf { it.value == channelId }
        }
    }

    private fun cleanupStaleDirs() {
        if (STREAMS_DIR.exists()) {
            STREAMS_DIR.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    dir.listFiles()?.forEach { it.delete() }
                    dir.delete()
                }
            }
        }
    }

    private fun getConfigInt(key: String, default: Int): Int {
        return try {
            AppConfig.findAll().firstOrNull { it.config_key == key }?.config_val?.toIntOrNull() ?: default
        } catch (_: Exception) { default }
    }

    private fun getConfigString(key: String, default: String): String {
        return try {
            AppConfig.findAll().firstOrNull { it.config_key == key }?.config_val ?: default
        } catch (_: Exception) { default }
    }
}
