package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.LeaseType
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.Title
import net.stewart.transcode.EncoderProfile
import net.stewart.transcode.TranscodeCommand
import net.stewart.transcode.VideoProbeResult
import net.stewart.transcode.ThumbnailSpriteGenerator
import net.stewart.transcode.probeForBrowser
import net.stewart.transcode.probeVideo as sharedProbeVideo
import net.stewart.transcode.probeVideoCodec as sharedProbeVideoCodec
import net.stewart.transcode.isBrowserSafeCodec as sharedIsBrowserSafeCodec
import net.stewart.transcode.probeDuration as sharedProbeDuration
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.Counter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Background daemon that pre-transcodes MKV/AVI files to browser-compatible MP4.
 *
 * Transcoded files mirror the source directory structure under `{nas_root}/ForBrowser/`.
 * Processes one file at a time, prioritized by TMDB popularity (most popular first).
 *
 * Follows the same daemon pattern as [UpcLookupAgent] and [TmdbEnrichmentAgent].
 */
class TranscoderAgent(
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(TranscoderAgent::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Measured throughput from current/recent transcode (bytes per second). */
    @Volatile private var measuredBytesPerSec: Double = 0.0

    /** When the current transcode started (System.nanoTime). */
    private var transcodeStartNanos: Long = 0L

    /** File size of the file currently being transcoded. */
    private var currentFileSize: Long = 0L

    /** Live FFmpeg process reference for metrics. */
    @Volatile var currentProcess: Process? = null

    /** Pending transcode count for gauge. */
    private val pendingCount = AtomicInteger(0)

    // Metrics
    private val completedCounter: Counter = MetricsRegistry.registry.counter(
        "mm_transcodes_completed_total", "type", "for_browser")
    private val failedCounter: Counter = MetricsRegistry.registry.counter(
        "mm_transcodes_failed_total", "type", "for_browser")
    private val bytesCounter: Counter = MetricsRegistry.registry.counter(
        "mm_transcoded_bytes_total", "type", "for_browser")

    init {
        val reg = MetricsRegistry.registry
        reg.gauge("mm_transcoder_running", listOf(io.micrometer.core.instrument.Tag.of("type", "for_browser")), this) {
            if (currentProcess?.isAlive == true) 1.0 else 0.0
        }
        reg.gauge("mm_ffmpeg_rss_bytes", listOf(io.micrometer.core.instrument.Tag.of("type", "for_browser")), this) {
            readFfmpegRss()
        }
        reg.gauge("mm_transcodes_pending", listOf(io.micrometer.core.instrument.Tag.of("type", "for_browser")), pendingCount) {
            it.get().toDouble()
        }
    }

    /**
     * Reads the RSS (resident set size) of the current FFmpeg process.
     * On Linux, reads /proc/{pid}/statm (resident pages * 4096).
     * Returns 0.0 on Windows or when idle.
     */
    private fun readFfmpegRss(): Double {
        val proc = currentProcess ?: return 0.0
        if (!proc.isAlive) return 0.0
        return try {
            val pid = proc.pid()
            val statm = File("/proc/$pid/statm").readText().trim().split(" ")
            if (statm.size >= 2) statm[1].toLong() * 4096.0 else 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
        private val DEFAULT_FFMPEG_PATH: String = if (System.getProperty("os.name").lowercase().contains("win"))
            """C:\ffmpeg\bin\ffmpeg.exe"""
        else
            "/usr/bin/ffmpeg"
        private const val FOR_BROWSER_DIR = "ForBrowser"
        private val DIRECT_EXTENSIONS = setOf("mp4", "m4v")
        private val TRANSCODE_EXTENSIONS = setOf("mkv", "avi")

        /** Conservative default: ~30 MB/s (copy video + transcode audio over NAS). */
        private const val DEFAULT_BYTES_PER_SEC = 30_000_000.0

        /**
         * Computes the ForBrowser mirrored path for a source file.
         * E.g., `{nasRoot}/BLURAY/Movie.mkv` → `{nasRoot}/ForBrowser/BLURAY/Movie.mp4`
         */
        fun getForBrowserPath(nasRoot: String, sourceFilePath: String): File {
            val sourceFile = File(sourceFilePath)
            val nasRootFile = File(nasRoot)
            val relativePath = nasRootFile.toPath().relativize(sourceFile.toPath()).toString()
            val mp4Name = File(relativePath).nameWithoutExtension + ".mp4"
            val relativeDir = File(relativePath).parent ?: ""
            return File(nasRoot, FOR_BROWSER_DIR)
                .resolve(relativeDir)
                .resolve(mp4Name)
        }

        /**
         * Returns true if a ForBrowser MP4 already exists for this source file.
         */
        fun isTranscoded(nasRoot: String, sourceFilePath: String): Boolean {
            return getForBrowserPath(nasRoot, sourceFilePath).exists()
        }

        /**
         * Returns true if the file extension requires transcoding (MKV, AVI).
         */
        fun needsTranscoding(filePath: String): Boolean {
            return File(filePath).extension.lowercase() in TRANSCODE_EXTENSIONS
        }


        /**
         * Returns the OS-specific default FFmpeg path (used as placeholder in settings).
         */
        fun getDefaultFfmpegPath(): String = DEFAULT_FFMPEG_PATH

        /**
         * Returns the configured FFmpeg path, falling back to the platform default
         * if the configured path doesn't exist (e.g., Windows path in a Linux container).
         */
        fun getFfmpegPath(): String {
            val configured = AppConfig.findAll()
                .firstOrNull { it.config_key == "ffmpeg_path" }
                ?.config_val
            if (configured != null && File(configured).exists()) return configured
            return DEFAULT_FFMPEG_PATH
        }

        /**
         * Returns the configured NAS root path, or null if not configured.
         */
        fun getNasRoot(): String? {
            return AppConfig.findAll()
                .firstOrNull { it.config_key == "nas_root_path" }
                ?.config_val
        }

        // Delegations to transcode-common shared module
        fun probeVideo(ffmpegPath: String, sourceFile: File) = sharedProbeVideo(ffmpegPath, sourceFile)
        fun probeVideoCodec(ffmpegPath: String, sourceFile: File) = sharedProbeVideoCodec(ffmpegPath, sourceFile)
        fun isBrowserSafeCodec(codec: String?) = sharedIsBrowserSafeCodec(codec)
        fun probeDuration(ffmpegPath: String, sourceFile: File) = sharedProbeDuration(ffmpegPath, sourceFile)

        /**
         * Probes a media file for its audio channel count using FFmpeg.
         * Returns the number of channels, or null if it cannot be determined.
         * Used to detect multichannel audio (>2ch) that Roku can't play.
         */
        fun probeAudioChannels(ffmpegPath: String, file: File): Int? {
            val log = LoggerFactory.getLogger(TranscoderAgent::class.java)
            return try {
                val process = ProcessBuilder(ffmpegPath, "-i", file.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()

                // Match audio stream line, e.g.:
                // Stream #0:1(eng): Audio: aac (LC), 48000 Hz, 5.1, fltp, 394 kb/s
                // Stream #0:1: Audio: aac (LC), 48000 Hz, stereo, fltp, 192 kb/s
                val match = Regex("""Stream #\d+:\d+.*?: Audio: \w+.*?, \d+ Hz, ([^,]+)""").find(output)
                val channelDesc = match?.groupValues?.get(1)?.trim()
                when {
                    channelDesc == null -> null
                    channelDesc == "mono" -> 1
                    channelDesc == "stereo" -> 2
                    channelDesc.contains("5.1") -> 6
                    channelDesc.contains("7.1") -> 8
                    channelDesc.contains("quad") -> 4
                    else -> null // unknown layout, leave alone
                }
            } catch (e: Exception) {
                log.warn("Failed to probe audio channels: {}", e.message)
                null
            }
        }

        /**
         * Probes a media file for total stream count.
         * A clean ForBrowser MP4 should have exactly 2 streams (video + audio).
         * Extra streams (data, subtitle, attachment) cause Roku playback failures.
         */
        fun probeStreamCount(ffmpegPath: String, file: File): Int {
            val log = LoggerFactory.getLogger(TranscoderAgent::class.java)
            return try {
                val process = ProcessBuilder(ffmpegPath, "-i", file.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                Regex("""Stream #\d+:\d+""").findAll(output).count()
            } catch (e: Exception) {
                log.warn("Failed to probe stream count: {}", e.message)
                0
            }
        }

    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("Transcoder Agent started")
            while (running.get()) {
                try {
                    processNext()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("Transcoder agent error", e)
                }
                try {
                    clock.sleep(POLL_INTERVAL_MS.milliseconds)
                } catch (e: InterruptedException) {
                    break
                }
            }
            log.info("Transcoder Agent stopped")
        }, "transcoder-agent").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun processNext() {
        val nasRoot = getNasRoot() ?: return
        val ffmpegPath = getFfmpegPath()
        if (!File(ffmpegPath).exists()) {
            log.warn("FFmpeg not found at '{}', skipping transcode cycle", ffmpegPath)
            return
        }

        // Handle re-transcode requests before claiming work
        handleRetranscodeRequests(nasRoot)

        // Compute summary stats for broadcast (total needing, completed)
        val transcodes = Transcode.findAll().filter { tc ->
            tc.file_path != null && needsTranscoding(tc.file_path!!) &&
                tc.title_id !in Title.findAll().filter { it.hidden }.mapNotNull { it.id }.toSet()
        }

        var totalCompleted = 0
        var needingCount = 0
        for (tc in transcodes) {
            val forBrowserPath = getForBrowserPath(nasRoot, tc.file_path!!)
            if (forBrowserPath.exists()) {
                totalCompleted++
            } else if (File(tc.file_path!!).exists()) {
                needingCount++
            }
        }
        pendingCount.set(needingCount)

        // Claim work through the unified lease system
        val lease = TranscodeLeaseService.claimWork("local")
        if (lease == null) {
            broadcastStatus(TranscoderProgressEvent(
                status = TranscoderStatus.IDLE,
                currentFile = null,
                currentPercent = 0,
                totalNeeding = transcodes.size,
                totalCompleted = totalCompleted,
                recentCompletions = emptyList(),
                estimatedSecondsLeft = null
            ))
            return
        }

        // Dispatch based on lease type
        if (lease.lease_type == LeaseType.THUMBNAILS.name) {
            processThumbnails(lease, nasRoot, ffmpegPath)
            return
        }
        if (lease.lease_type == LeaseType.SUBTITLES.name) {
            processSubtitles(lease, nasRoot)
            return
        }

        val next = Transcode.findById(lease.transcode_id) ?: return
        val sourceFile = File(next.file_path!!)
        val mp4File = getForBrowserPath(nasRoot, next.file_path!!)
        val relativePath = lease.relative_path

        // Sum remaining bytes for ETA: use needingCount files
        val remainingBytes = transcodes
            .filter { tc -> !getForBrowserPath(nasRoot, tc.file_path!!).exists() && File(tc.file_path!!).exists() }
            .sumOf { it.file_size_bytes ?: 0L }

        // Ensure parent directories exist
        mp4File.parentFile?.mkdirs()

        val tmpFile = File(mp4File.parentFile, mp4File.nameWithoutExtension + ".tmp")

        log.info("Transcoding: {} -> {}", sourceFile.absolutePath, mp4File.absolutePath)

        // Start timing for throughput measurement
        currentFileSize = next.file_size_bytes ?: sourceFile.length()
        transcodeStartNanos = System.nanoTime()

        broadcastStatus(TranscoderProgressEvent(
            status = TranscoderStatus.TRANSCODING,
            currentFile = relativePath,
            currentPercent = 0,
            totalNeeding = transcodes.size,
            totalCompleted = totalCompleted,
            recentCompletions = emptyList(),
            estimatedSecondsLeft = estimateSecondsLeft(remainingBytes, 0)
        ))

        try {
            val probe = probeVideo(ffmpegPath, sourceFile)
            val durationSecs = probe.durationSecs

            // Log codec decision
            if (probe.browserSafe && !probe.needsVideoFilter) {
                log.info("Source codec '{}' is browser-safe (no filters needed), copying video", probe.codec)
            } else if (probe.browserSafe) {
                log.info("Source codec '{}' is H.264 but needs re-encode for Roku (SAR={}/{}  fps={}  interlaced={})",
                    probe.codec, probe.sarNum, probe.sarDen, probe.fps, probe.interlaced)
            } else {
                log.info("Source codec '{}' needs re-encoding to H.264 (interlaced={})", probe.codec, probe.interlaced)
            }

            val (command, encoderName) = TranscodeCommand.build(
                ffmpegPath, sourceFile, tmpFile, probe, EncoderProfile.CPU
            )

            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            currentProcess = process

            val timeRegex = Regex("""time=(\d+):(\d+):(\d+)\.(\d+)""")
            val reader = process.inputStream.bufferedReader()
            val outputBuilder = StringBuilder()

            reader.forEachLine { line ->
                if (!running.get()) {
                    process.destroyForcibly()
                    return@forEachLine
                }
                outputBuilder.appendLine(line)
                if (durationSecs != null && durationSecs > 0) {
                    val match = timeRegex.find(line)
                    if (match != null) {
                        val h = match.groupValues[1].toInt()
                        val m = match.groupValues[2].toInt()
                        val s = match.groupValues[3].toInt()
                        val frac = "0.${match.groupValues[4]}".toDouble()
                        val currentSecs = h * 3600.0 + m * 60.0 + s + frac
                        val percent = ((currentSecs / durationSecs) * 95).toInt().coerceIn(0, 95)

                        // Update throughput measurement
                        val elapsedNanos = System.nanoTime() - transcodeStartNanos
                        val elapsedSecs = elapsedNanos / 1_000_000_000.0
                        if (elapsedSecs > 2.0 && percent > 0) {
                            val bytesProcessed = (currentFileSize * percent / 100.0)
                            measuredBytesPerSec = bytesProcessed / elapsedSecs
                        }

                        // Report progress to lease service (keeps lease alive)
                        TranscodeLeaseService.reportProgress(lease.id!!, percent, encoderName)

                        broadcastStatus(TranscoderProgressEvent(
                            status = TranscoderStatus.TRANSCODING,
                            currentFile = relativePath,
                            currentPercent = percent,
                            totalNeeding = transcodes.size,
                            totalCompleted = totalCompleted,
                            recentCompletions = emptyList(),
                            estimatedSecondsLeft = estimateSecondsLeft(remainingBytes, percent)
                        ))
                    }
                }
            }

            process.waitFor()
            currentProcess = null

            if (!running.get()) {
                tmpFile.delete()
                return
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                log.error("FFmpeg failed for {} (exit {}): {}",
                    relativePath, exitCode, outputBuilder.toString().takeLast(2000))
                tmpFile.delete()
                failedCounter.increment()
                TranscodeLeaseService.reportFailure(lease.id!!, "FFmpeg exit code $exitCode")
                broadcastStatus(TranscoderProgressEvent(
                    status = TranscoderStatus.ERROR,
                    currentFile = relativePath,
                    currentPercent = 0,
                    totalNeeding = transcodes.size,
                    totalCompleted = totalCompleted,
                    recentCompletions = emptyList(),
                    estimatedSecondsLeft = null
                ))
                return
            }

            // Rename .tmp -> .mp4
            if (!tmpFile.renameTo(mp4File)) {
                log.error("Failed to rename {} -> {}", tmpFile.absolutePath, mp4File.absolutePath)
                tmpFile.delete()
                TranscodeLeaseService.reportFailure(lease.id!!, "Failed to rename .tmp to .mp4")
                return
            }

            log.info("Transcode complete: {} (size={})", mp4File.absolutePath, mp4File.length())
            completedCounter.increment()
            bytesCounter.increment(currentFileSize.toDouble())

            // Report completion through lease service (handles wish fulfillment)
            TranscodeLeaseService.reportComplete(lease.id!!, encoderName)

            // Capture probe data for the ForBrowser MP4
            try {
                val outputRelativePath = File(nasRoot).toPath()
                    .relativize(mp4File.toPath()).toString().replace('\\', '/')
                    .removePrefix("ForBrowser/")
                val probeResult = probeForBrowser(ffmpegPath, mp4File)
                ForBrowserProbeService.recordProbe(
                    transcodeId = next.id!!,
                    relativePath = outputRelativePath,
                    probeResult = probeResult,
                    encoder = encoderName,
                    fileSize = mp4File.length()
                )
            } catch (e: Exception) {
                log.warn("Failed to probe ForBrowser output {}: {}", mp4File.name, e.message)
            }

            totalCompleted++

            // Remaining bytes after this file completed
            val backlogBytes = remainingBytes - currentFileSize

            broadcastStatus(TranscoderProgressEvent(
                status = TranscoderStatus.IDLE,
                currentFile = null,
                currentPercent = 0,
                totalNeeding = transcodes.size,
                totalCompleted = totalCompleted,
                recentCompletions = listOf(relativePath.replace('\\', '/')),
                estimatedSecondsLeft = if (backlogBytes > 0) estimateSecondsLeft(backlogBytes, 100) else null
            ))

        } catch (e: Exception) {
            currentProcess = null
            log.error("Transcode error for {}: {}", relativePath, e.message)
            tmpFile.delete()
            failedCounter.increment()
            TranscodeLeaseService.reportFailure(lease.id!!, e.message ?: "Unknown error")
            broadcastStatus(TranscoderProgressEvent(
                status = TranscoderStatus.ERROR,
                currentFile = relativePath,
                currentPercent = 0,
                totalNeeding = transcodes.size,
                totalCompleted = totalCompleted,
                recentCompletions = emptyList(),
                estimatedSecondsLeft = null
            ))
        }
    }

    /**
     * Processes a THUMBNAILS lease: generates sprite sheets + VTT for a ForBrowser MP4.
     */
    private fun processThumbnails(
        lease: net.stewart.mediamanager.entity.TranscodeLease,
        nasRoot: String,
        ffmpegPath: String
    ) {
        val tc = Transcode.findById(lease.transcode_id)
        if (tc == null || tc.file_path == null) {
            TranscodeLeaseService.reportFailure(lease.id!!, "Transcode not found")
            return
        }

        val filePath = tc.file_path!!
        val mp4File = if (needsTranscoding(filePath)) {
            getForBrowserPath(nasRoot, filePath)
        } else {
            File(filePath)
        }

        if (!mp4File.exists()) {
            TranscodeLeaseService.reportFailure(lease.id!!, "MP4 file not found: ${mp4File.name}")
            return
        }

        log.info("Generating thumbnails for: {}", mp4File.name)
        TranscodeLeaseService.reportProgress(lease.id!!, 10, null)

        val success = ThumbnailSpriteGenerator.generate(ffmpegPath, mp4File)

        if (success) {
            log.info("Thumbnails complete for: {}", mp4File.name)

            // Copy sprites to source directory alongside the original file
            try {
                val sourceFile = File(filePath)
                if (sourceFile.exists() && sourceFile.parentFile != mp4File.parentFile) {
                    val copied = ThumbnailSpriteGenerator.copySpritesToDirectory(
                        mp4File.nameWithoutExtension, mp4File.parentFile, sourceFile.parentFile
                    )
                    if (copied > 0) {
                        log.info("Copied {} sprite files to source: {}", copied, sourceFile.parentFile)
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to copy sprites to source directory: {}", e.message)
            }

            TranscodeLeaseService.reportComplete(lease.id!!, null)
        } else {
            log.warn("Thumbnail generation failed for: {}", mp4File.name)
            TranscodeLeaseService.reportFailure(lease.id!!, "FFmpeg thumbnail generation failed")
        }
    }

    /**
     * Processes a SUBTITLES lease: generates SRT subtitles via Whisper.
     * On the Docker server (local buddy), Whisper is typically not available,
     * so this just reports failure. Remote buddies with whisper_path configured
     * handle subtitles via TranscodeWorker.processSubtitles().
     */
    private fun processSubtitles(
        lease: net.stewart.mediamanager.entity.TranscodeLease,
        nasRoot: String
    ) {
        // The local transcoder agent (Docker) doesn't have Whisper installed.
        // Subtitle generation is handled by remote buddy workers with GPUs.
        // If we're the only worker, skip gracefully.
        log.info("Skipping SUBTITLES lease {} (local agent has no Whisper)", lease.id)
        TranscodeLeaseService.reportFailure(lease.id!!, "Local agent does not have Whisper installed")
    }

    /**
     * Estimates seconds remaining for all backlogged transcodes.
     * Uses measured throughput if available, otherwise a conservative default.
     *
     * @param totalRemainingBytes total bytes across all files still needing transcode
     * @param currentPercent progress on the current file (0-100); used to subtract already-done portion
     */
    private fun estimateSecondsLeft(totalRemainingBytes: Long, currentPercent: Int): Long? {
        if (totalRemainingBytes <= 0) return null
        val rate = if (measuredBytesPerSec > 0) measuredBytesPerSec else DEFAULT_BYTES_PER_SEC

        // Subtract the portion of the current file already processed
        val currentDoneBytes = (currentFileSize * currentPercent / 100.0).toLong()
        val effectiveRemaining = (totalRemainingBytes - currentDoneBytes).coerceAtLeast(0)

        return (effectiveRemaining / rate).toLong()
    }

    /**
     * Handles re-transcode requests: if the source MKV was replaced, deletes
     * the ForBrowser copy so it gets re-queued for transcoding.
     */
    private fun handleRetranscodeRequests(nasRoot: String) {
        val transcodes = Transcode.findAll().filter { it.retranscode_requested && it.file_path != null }
        for (tc in transcodes) {
            val sourceFile = File(tc.file_path!!)
            val forBrowserPath = getForBrowserPath(nasRoot, tc.file_path!!)
            if (!forBrowserPath.exists() || !sourceFile.exists()) continue

            val currentSize = sourceFile.length()
            val currentModified = readFileModifiedAt(sourceFile)
            val sizeChanged = tc.file_size_bytes != null && tc.file_size_bytes != currentSize
            val modifiedChanged = tc.file_modified_at != null && currentModified != null &&
                tc.file_modified_at != currentModified

            if (sizeChanged || modifiedChanged) {
                log.info("Re-transcode: MKV replaced for {} (size {}->{}), deleting ForBrowser copy",
                    sourceFile.name, tc.file_size_bytes, currentSize)
                forBrowserPath.delete()
                tc.file_size_bytes = currentSize
                tc.file_modified_at = currentModified
                tc.retranscode_requested = false
                tc.save()
            } else {
                log.debug("Re-transcode requested but MKV not yet replaced: {}", sourceFile.name)
            }
        }
    }

    private fun readFileModifiedAt(file: File): java.time.LocalDateTime? {
        return try {
            val instant = java.nio.file.Files.getLastModifiedTime(file.toPath()).toInstant()
            java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        } catch (_: Exception) {
            null
        }
    }

    private fun broadcastStatus(event: TranscoderProgressEvent) {
        Broadcaster.broadcastTranscoderProgress(event)
    }
}

data class TranscoderProgressEvent(
    val status: TranscoderStatus,
    val currentFile: String?,
    val currentPercent: Int,
    val totalNeeding: Int,
    val totalCompleted: Int,
    val recentCompletions: List<String>,
    /** Rough estimate of seconds remaining for all backlogged transcodes, or null if not applicable. */
    val estimatedSecondsLeft: Long? = null
)

enum class TranscoderStatus { IDLE, TRANSCODING, ERROR }
