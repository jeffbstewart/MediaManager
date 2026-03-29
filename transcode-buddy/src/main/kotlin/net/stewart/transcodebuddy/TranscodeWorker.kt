package net.stewart.transcodebuddy

import net.stewart.transcode.EncoderProfile
import net.stewart.transcode.ThumbnailSpriteGenerator
import net.stewart.transcode.TranscodeCommand
import net.stewart.transcode.probeChapters
import net.stewart.transcode.probeForBrowser
import net.stewart.transcode.probeVideo
import net.stewart.transcode.sanitizeFfmpegOutput
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TranscodeWorker(
    private val config: BuddyConfig,
    private val apiClient: BuddyGrpcClient,
    private val pathTranslator: PathTranslator,
    private val encoder: EncoderProfile,
    private val workerIndex: Int,
    private val running: AtomicBoolean,
    private val localCache: LocalFileCache?
) : Runnable {

    private val log = LoggerFactory.getLogger("Worker-$workerIndex")

    private val baseIntervalMs = config.pollIntervalSeconds * 1000L
    private val maxIntervalMs = 3600_000L // 1 hour cap
    private var currentIntervalMs = baseIntervalMs

    /** Threshold: once backoff exceeds this, allow sleep (queue has been empty a while). */
    private val sleepAllowThresholdMs = 120_000L // 2 minutes of empty polls

    /** Lease types this worker cannot handle (told to server so it skips them). */
    private val skipTypes: Set<String> = buildSet {
        val wp = config.whisperPath
        if (wp == null || !java.io.File(wp).exists()) {
            add("SUBTITLES")
            log.info("Worker-{} skipping SUBTITLES (whisper not configured at '{}')", workerIndex, wp)
        }
    }

    /** Bundle execution order: fast operations first, then by user value. */
    private val executionOrder = mapOf(
        "CHAPTERS" to 0,
        "TRANSCODE" to 1,
        "MOBILE_TRANSCODE" to 2,
        "THUMBNAILS" to 3,
        "SUBTITLES" to 4
    )

    private val reconnectIntervalMs = 30_000L // retry reconnection every 30s

    override fun run() {
        log.info("Worker-{} started (encoder: {} / {})", workerIndex, encoder.name, encoder.ffmpegEncoder)
        var holdingSleep = false

        while (running.get()) {
            try {
                // If the gRPC stream is down (server restart, network blip), attempt
                // reconnection before entering the work polling loop. This prevents
                // the "no work" backoff from growing to 1 hour when the real problem
                // is a dead connection, not an empty queue.
                if (!apiClient.isConnected()) {
                    log.info("Worker-{} stream disconnected, attempting reconnection...", workerIndex)
                    val reconnected = apiClient.connect()
                    if (reconnected == null) {
                        log.warn("Worker-{} reconnection failed, retrying in {}s", workerIndex, reconnectIntervalMs / 1000)
                        Thread.sleep(reconnectIntervalMs)
                        continue
                    }
                    log.info("Worker-{} reconnected to server ({} pending)", workerIndex, reconnected.pendingCount)
                    currentIntervalMs = baseIntervalMs // reset backoff on reconnect
                }

                val didWork = processBundle()
                if (didWork) {
                    currentIntervalMs = baseIntervalMs // reset backoff on success
                    if (!holdingSleep) {
                        SleepInhibitor.acquire()
                        holdingSleep = true
                    }
                } else {
                    // Release sleep inhibitor once we've been idle long enough
                    if (holdingSleep && currentIntervalMs >= sleepAllowThresholdMs) {
                        SleepInhibitor.release()
                        holdingSleep = false
                        log.info("Worker-{} idle for a while, allowing system sleep", workerIndex)
                    }
                    // Binary exponential backoff when no work available
                    log.info("Worker-{} no work available, sleeping {}s (backoff)", workerIndex, currentIntervalMs / 1000)
                    Thread.sleep(currentIntervalMs)
                    currentIntervalMs = (currentIntervalMs * 2).coerceAtMost(maxIntervalMs)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                log.error("Worker-{} error: {}", workerIndex, e.message, e)
                Thread.sleep(baseIntervalMs)
            }
        }

        if (holdingSleep) SleepInhibitor.release()
        log.info("Worker-{} stopped", workerIndex)
    }

    /**
     * Claims a bundle of work, optionally stages the file locally, then processes
     * all leases in execution order. Returns true if work was done.
     */
    private fun processBundle(): Boolean {
        val cachedIds = localCache?.getCachedTranscodeIds() ?: emptySet()
        val bundle = apiClient.claimWork(skipTypes, cachedIds) ?: return false

        log.info("Claimed bundle of {} lease(s) for: {} (transcode_id={})",
            bundle.leases.size, bundle.relativePath, bundle.transcodeId)

        // Sort leases by execution order
        val sortedLeases = bundle.leases.sortedBy { executionOrder[it.leaseType] ?: 99 }
        val allLeaseIds = sortedLeases.map { it.leaseId }

        // Determine the video input file: local cache or NAS
        val sourceFile = pathTranslator.sourceFile(bundle.relativePath)
        val videoInputFile = resolveVideoInput(bundle, sourceFile, sortedLeases.size)

        try {
            for (lease in sortedLeases) {
                if (!running.get()) break

                // Heartbeat all other leases in the bundle
                val othersToHeartbeat = allLeaseIds.filter { it != lease.leaseId }
                if (othersToHeartbeat.isNotEmpty()) {
                    apiClient.heartbeatMultiple(othersToHeartbeat)
                }

                try {
                    when (lease.leaseType) {
                        "CHAPTERS" -> processChapters(lease.leaseId, bundle.relativePath, videoInputFile)
                        "TRANSCODE" -> processTranscode(lease.leaseId, bundle.relativePath, videoInputFile, allLeaseIds)
                        "MOBILE_TRANSCODE" -> processMobileTranscode(lease.leaseId, bundle.relativePath, videoInputFile, allLeaseIds)
                        "THUMBNAILS" -> processThumbnails(lease.leaseId, bundle.relativePath, videoInputFile)
                        "SUBTITLES" -> processSubtitles(lease.leaseId, bundle.relativePath, videoInputFile, allLeaseIds)
                        else -> {
                            log.warn("Unknown lease type: {}", lease.leaseType)
                            apiClient.reportFailure(lease.leaseId, "Unknown lease type: ${lease.leaseType}")
                        }
                    }
                } catch (e: Exception) {
                    log.error("Error processing {} lease {}: {}", lease.leaseType, lease.leaseId, e.message, e)
                    apiClient.reportFailure(lease.leaseId, e.message ?: "Unknown error")
                }
            }
        } finally {
            // Clean up local cache after bundle completes
            if (localCache != null && videoInputFile != sourceFile) {
                localCache.remove(bundle.transcodeId)
            }
        }

        return true
    }

    /**
     * Resolves the video input file for this bundle.
     * For bundles with 2+ leases and local cache configured, stages the file locally.
     * For single-lease bundles or no cache, streams from NAS.
     */
    private fun resolveVideoInput(bundle: BundleResponse, sourceFile: File, leaseCount: Int): File {
        if (localCache == null) return sourceFile

        // Check if already cached
        val cached = localCache.getCachedFile(bundle.transcodeId)
        if (cached != null) {
            log.info("Using cached local copy: {}", cached.name)
            return cached
        }

        // Only stage locally if 2+ leases (copy pays for itself)
        if (leaseCount < 2) return sourceFile

        // Stage the file locally
        val staged = localCache.stageFile(bundle.transcodeId, bundle.relativePath, sourceFile)
        if (staged != null) {
            return staged
        }

        // Staging failed — fall back to NAS
        log.warn("Local staging failed, falling back to NAS streaming for: {}", sourceFile.name)
        return sourceFile
    }

    /**
     * Processes a TRANSCODE lease: FFmpeg re-encode to ForBrowser MP4.
     */
    private fun processTranscode(
        leaseId: Long,
        relativePath: String,
        videoInputFile: File,
        bundleLeaseIds: List<Long>
    ): Boolean {
        if (!videoInputFile.exists()) {
            log.error("Source file not found: {}", videoInputFile.absolutePath)
            apiClient.reportFailure(leaseId, "Source file not found: ${videoInputFile.absolutePath}")
            return true
        }

        val mp4File = pathTranslator.forBrowserPath(relativePath)
        val tmpFile = pathTranslator.tmpPath(relativePath)
        mp4File.parentFile?.mkdirs()

        try {
            val probe = probeVideo(config.ffmpegPath, videoInputFile)
            val durationSecs = probe.durationSecs

            if (probe.browserSafe && !probe.needsVideoFilter) {
                log.info("Source codec '{}' is browser-safe (no filters needed), copying video", probe.codec)
            } else if (probe.browserSafe) {
                log.info("Source codec '{}' is H.264 but needs re-encode for Roku (SAR={}/{}  fps={}  interlaced={})",
                    probe.codec, probe.sarNum, probe.sarDen, probe.fps, probe.interlaced)
            } else {
                log.info("Source codec '{}' needs re-encoding with {} (interlaced={})", probe.codec, encoder.ffmpegEncoder, probe.interlaced)
            }

            val (command, encoderName) = TranscodeCommand.build(
                config.ffmpegPath, videoInputFile, tmpFile, probe, encoder
            )

            log.info("Running: {}", command.joinToString(" "))
            val process = ProcessBuilder(command).redirectErrorStream(true).start()

            // Background heartbeat/progress thread — heartbeats ALL bundle leases
            val progressPercent = AtomicInteger(0)
            val heartbeatThread = createHeartbeatThread(leaseId, progressPercent, encoderName, bundleLeaseIds, process)
            heartbeatThread.start()

            val timeRegex = Regex("""time=(\d+):(\d+):(\d+)\.(\d+)""")
            val outputBuilder = StringBuilder()

            process.inputStream.bufferedReader().forEachLine { rawLine ->
                if (!running.get()) {
                    process.destroyForcibly()
                    return@forEachLine
                }
                val line = sanitizeFfmpegOutput(rawLine)
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
                        progressPercent.set(percent)
                    }
                }
            }

            process.waitFor()
            heartbeatThread.interrupt()
            heartbeatThread.join(2000)

            if (!running.get()) {
                tmpFile.delete()
                return true
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val errorTail = outputBuilder.toString().takeLast(2000)
                log.error("FFmpeg failed for {} (exit {}): {}", relativePath, exitCode, errorTail)
                tmpFile.delete()
                apiClient.reportFailure(leaseId, "FFmpeg exit code $exitCode: ${errorTail.takeLast(500)}")
                return true
            }

            if (!tmpFile.renameTo(mp4File)) {
                log.error("Failed to rename {} -> {}", tmpFile.absolutePath, mp4File.absolutePath)
                tmpFile.delete()
                apiClient.reportFailure(leaseId, "Failed to rename .tmp to .mp4")
                return true
            }

            log.info("Transcode complete: {} (size={})", mp4File.absolutePath, mp4File.length())

            val outputProbe = try {
                probeForBrowser(config.ffmpegPath, mp4File)
            } catch (e: Exception) {
                log.warn("Failed to probe output file {}: {}", mp4File.name, e.message)
                null
            }

            var reported = false
            for (attempt in 1..3) {
                if (apiClient.reportComplete(leaseId, encoderName, outputProbe, mp4File.length())) {
                    reported = true
                    break
                }
                log.warn("reportComplete attempt {} failed, retrying...", attempt)
                Thread.sleep(5000L * attempt)
            }
            if (!reported) {
                log.error("Failed to report completion for lease {} after retries (file is on NAS)", leaseId)
            }

            return true

        } catch (e: Exception) {
            log.error("Transcode error for {}: {}", relativePath, e.message, e)
            tmpFile.delete()
            apiClient.reportFailure(leaseId, e.message ?: "Unknown error")
            return true
        }
    }

    /**
     * Processes a MOBILE_TRANSCODE lease: re-encodes to 1080p/5Mbps for mobile downloads.
     */
    private fun processMobileTranscode(
        leaseId: Long,
        relativePath: String,
        videoInputFile: File,
        bundleLeaseIds: List<Long>
    ): Boolean {
        if (!videoInputFile.exists()) {
            log.error("Source file not found: {}", videoInputFile.absolutePath)
            apiClient.reportFailure(leaseId, "Source file not found: ${videoInputFile.absolutePath}")
            return true
        }

        val mp4File = pathTranslator.forMobilePath(relativePath)
        val tmpFile = pathTranslator.forMobileTmpPath(relativePath)
        mp4File.parentFile?.mkdirs()

        try {
            val probe = probeVideo(config.ffmpegPath, videoInputFile)
            val durationSecs = probe.durationSecs

            log.info("Mobile transcode: {} ({}x{}, {})", videoInputFile.name,
                probe.width, probe.height, probe.codec)

            val (command, encoderName) = TranscodeCommand.buildMobile(
                config.ffmpegPath, videoInputFile, tmpFile, probe, encoder
            )

            log.info("Running: {}", command.joinToString(" "))
            val process = ProcessBuilder(command).redirectErrorStream(true).start()

            val progressPercent = AtomicInteger(0)
            val heartbeatThread = createHeartbeatThread(leaseId, progressPercent, encoderName, bundleLeaseIds, process)
            heartbeatThread.start()

            val timeRegex = Regex("""time=(\d+):(\d+):(\d+)\.(\d+)""")
            val outputBuilder = StringBuilder()

            process.inputStream.bufferedReader().forEachLine { rawLine ->
                if (!running.get()) { process.destroyForcibly(); return@forEachLine }
                val line = sanitizeFfmpegOutput(rawLine)
                outputBuilder.appendLine(line)
                if (durationSecs != null && durationSecs > 0) {
                    val match = timeRegex.find(line)
                    if (match != null) {
                        val h = match.groupValues[1].toInt()
                        val m = match.groupValues[2].toInt()
                        val s = match.groupValues[3].toInt()
                        val frac = "0.${match.groupValues[4]}".toDouble()
                        val currentSecs = h * 3600.0 + m * 60.0 + s + frac
                        progressPercent.set(((currentSecs / durationSecs) * 95).toInt().coerceIn(0, 95))
                    }
                }
            }

            process.waitFor()
            heartbeatThread.interrupt()
            heartbeatThread.join(2000)

            if (!running.get()) { tmpFile.delete(); return true }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val errorTail = outputBuilder.toString().takeLast(2000)
                log.error("Mobile FFmpeg failed for {} (exit {}): {}", relativePath, exitCode, errorTail)
                tmpFile.delete()
                apiClient.reportFailure(leaseId, "FFmpeg exit code $exitCode: ${errorTail.takeLast(500)}")
                return true
            }

            if (!tmpFile.renameTo(mp4File)) {
                log.error("Failed to rename {} -> {}", tmpFile.absolutePath, mp4File.absolutePath)
                tmpFile.delete()
                apiClient.reportFailure(leaseId, "Failed to rename .tmp to .mp4")
                return true
            }

            log.info("Mobile transcode complete: {} (size={})", mp4File.absolutePath, mp4File.length())

            var reported = false
            for (attempt in 1..3) {
                if (apiClient.reportComplete(leaseId, encoderName, null, mp4File.length())) {
                    reported = true; break
                }
                log.warn("reportComplete attempt {} failed, retrying...", attempt)
                Thread.sleep(5000L * attempt)
            }
            if (!reported) {
                log.error("Failed to report mobile completion for lease {} after retries", leaseId)
            }
            return true

        } catch (e: Exception) {
            log.error("Mobile transcode error for {}: {}", relativePath, e.message, e)
            tmpFile.delete()
            apiClient.reportFailure(leaseId, e.message ?: "Unknown error")
            return true
        }
    }

    /**
     * Processes a THUMBNAILS lease: generates sprite sheets + VTT alongside the source file.
     * Uses whatever video input is available (local copy or NAS source).
     */
    private fun processThumbnails(leaseId: Long, relativePath: String, videoInputFile: File): Boolean {
        if (!videoInputFile.exists()) {
            // Fall back to ForBrowser MP4 if local/source doesn't exist
            val fbFile = pathTranslator.forBrowserPath(relativePath)
            if (fbFile.exists()) {
                return generateThumbnails(leaseId, fbFile, relativePath)
            }
            val sourceFile = pathTranslator.sourceFile(relativePath)
            if (sourceFile.exists() && sourceFile.extension.lowercase() in setOf("mp4", "m4v")) {
                return generateThumbnails(leaseId, sourceFile, relativePath)
            }
            log.error("No video file found for thumbnails: {}", relativePath)
            apiClient.reportFailure(leaseId, "No video file found for thumbnails")
            return true
        }

        return generateThumbnails(leaseId, videoInputFile, relativePath)
    }

    private fun generateThumbnails(leaseId: Long, videoFile: File, relativePath: String): Boolean {
        // Write sprites alongside the source file on NAS, using the original filename
        val sourceFile = pathTranslator.sourceFile(relativePath)
        val outputDir = sourceFile.parentFile
        val outputBaseName = File(relativePath).nameWithoutExtension
        log.info("Generating thumbnails for {} -> {}", videoFile.name, outputDir)
        apiClient.reportProgress(leaseId, 10, null)

        val success = ThumbnailSpriteGenerator.generate(config.ffmpegPath, videoFile, outputDir, outputBaseName)

        if (success) {
            log.info("Thumbnails complete for: {}", videoFile.name)
            apiClient.reportComplete(leaseId, null)
        } else {
            log.warn("Thumbnail generation failed for: {}", videoFile.name)
            apiClient.reportFailure(leaseId, "FFmpeg thumbnail generation failed")
        }
        return true
    }

    /**
     * Processes a SUBTITLES lease: generates SRT subtitles via Whisper CLI
     * alongside the source file.
     */
    private fun processSubtitles(
        leaseId: Long,
        relativePath: String,
        videoInputFile: File,
        bundleLeaseIds: List<Long>
    ): Boolean {
        val whisperPath = config.whisperPath
        if (whisperPath == null || !File(whisperPath).exists()) {
            log.warn("Whisper not configured or not found at '{}', failing subtitles lease", whisperPath)
            apiClient.reportFailure(leaseId, "Whisper not configured (whisper_path not set or not found)")
            return true
        }

        if (!videoInputFile.exists()) {
            log.error("Source file not found for subtitles: {}", videoInputFile.absolutePath)
            apiClient.reportFailure(leaseId, "Source file not found: ${videoInputFile.absolutePath}")
            return true
        }

        // Write subtitles alongside the source file on NAS
        val sourceFile = pathTranslator.sourceFile(relativePath)
        val outputDir = sourceFile.parentFile
        val srtBaseName = sourceFile.nameWithoutExtension + ".${config.whisperLanguage}.srt"
        val srtFile = File(outputDir, srtBaseName)
        val sentinelFile = File(outputDir, "$srtBaseName.failed")

        log.info("Generating subtitles for: {} -> {}", videoInputFile.name, srtFile.name)
        apiClient.reportProgress(leaseId, 5, null)

        try {
            val command = mutableListOf(
                whisperPath,
                videoInputFile.absolutePath,
                "--model", config.whisperModel,
                "--language", config.whisperLanguage,
                "--output_format", "srt",
                "--output_dir", outputDir.absolutePath,
                "--device", config.whisperDevice,
                "--compute_type", config.whisperComputeType
            )
            if (config.whisperModelDir != null) {
                command.addAll(listOf("--model_dir", config.whisperModelDir))
            }

            log.info("Running: {}", command.joinToString(" "))
            val process = ProcessBuilder(command).redirectErrorStream(true).start()

            // Background heartbeat — keeps all bundle leases alive during Whisper
            val progressPercent = AtomicInteger(50)
            val heartbeatThread = createHeartbeatThread(leaseId, progressPercent, null, bundleLeaseIds, process)
            heartbeatThread.start()

            val outputBuilder = StringBuilder()
            process.inputStream.bufferedReader().forEachLine { rawLine ->
                if (!running.get()) {
                    process.destroyForcibly()
                    return@forEachLine
                }
                outputBuilder.appendLine(sanitizeFfmpegOutput(rawLine))
            }

            val finished = process.waitFor(1, java.util.concurrent.TimeUnit.HOURS)
            heartbeatThread.interrupt()
            heartbeatThread.join(2000)

            if (!finished) {
                log.error("Whisper timed out after 1 hour for {}, killing", relativePath)
                process.destroyForcibly()
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                apiClient.reportFailure(leaseId, "Whisper timed out after 1 hour")
                return true
            }

            if (!running.get()) return true

            // Whisper uses the input file's basename for output. When using a local copy,
            // the basename differs from the source file's basename.
            val whisperOutputFile = File(outputDir, videoInputFile.nameWithoutExtension + ".srt")
            val alternateOutputFile = File(outputDir, sourceFile.nameWithoutExtension + ".srt")
            val actualOutput = when {
                whisperOutputFile.exists() -> whisperOutputFile
                alternateOutputFile.exists() -> alternateOutputFile
                else -> null
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                if (actualOutput != null && actualOutput.length() > 100) {
                    log.warn("Whisper exited with {} but output file exists ({}B), treating as success",
                        exitCode, actualOutput.length())
                } else {
                    val errorTail = outputBuilder.toString().takeLast(500)
                    log.error("Whisper failed for {} (exit {}): {}", relativePath, exitCode, errorTail)
                    writeSentinel(sentinelFile, "Whisper exit code $exitCode: $errorTail")
                    apiClient.reportFailure(leaseId, "Whisper exit code $exitCode")
                    return true
                }
            }

            if (actualOutput == null) {
                log.error("Whisper produced no output file: expected {} or {}", whisperOutputFile.name, alternateOutputFile.name)
                writeSentinel(sentinelFile, "No output file produced")
                apiClient.reportFailure(leaseId, "Whisper produced no output file")
                return true
            }

            val srtContent = actualOutput.readText()
            val cueCount = srtContent.lines().count { it.contains(" --> ") }
            val fileDurationMinutes = estimateDurationMinutes(videoInputFile)

            if (srtContent.length < 100 || (cueCount < 5 && fileDurationMinutes > 10)) {
                log.warn("Whisper output too sparse for {}: {} bytes, {} cues, ~{} min",
                    videoInputFile.name, srtContent.length, cueCount, fileDurationMinutes)
                actualOutput.delete()
                writeSentinel(sentinelFile, "Output too sparse: $cueCount cues for ~${fileDurationMinutes}min file")
                apiClient.reportFailure(leaseId, "Whisper output too sparse ($cueCount cues)")
                return true
            }

            if (actualOutput.absolutePath != srtFile.absolutePath) {
                actualOutput.renameTo(srtFile)
            }

            log.info("Subtitles complete: {} ({} cues)", srtFile.name, cueCount)
            apiClient.reportComplete(leaseId, null)
            return true

        } catch (e: Exception) {
            log.error("Subtitle generation error for {}: {}", relativePath, e.message, e)
            writeSentinel(sentinelFile, e.message ?: "Unknown error")
            apiClient.reportFailure(leaseId, e.message ?: "Unknown error")
            return true
        }
    }

    /**
     * Processes a CHAPTERS lease: extracts chapter markers from the source file via FFprobe.
     */
    private fun processChapters(leaseId: Long, relativePath: String, videoInputFile: File): Boolean {
        val fileToProbe = if (videoInputFile.exists()) {
            videoInputFile
        } else {
            val sourceFile = pathTranslator.sourceFile(relativePath)
            if (!sourceFile.exists()) {
                log.error("Source file not found for chapters: {}", sourceFile.absolutePath)
                apiClient.reportFailure(leaseId, "Source file not found: ${sourceFile.absolutePath}")
                return true
            }
            sourceFile
        }

        log.info("Extracting chapters from: {}", fileToProbe.name)
        apiClient.reportProgress(leaseId, 10, null)

        val chapters = probeChapters(config.ffmpegPath, fileToProbe)

        if (chapters.isEmpty()) {
            log.info("No chapters found in: {}", fileToProbe.name)
        } else {
            log.info("Found {} chapters in: {}", chapters.size, fileToProbe.name)
        }

        apiClient.reportCompleteWithChapters(leaseId, chapters)
        return true
    }

    /**
     * Creates a heartbeat thread that periodically reports progress on the active lease
     * and heartbeats all other leases in the bundle.
     */
    private fun createHeartbeatThread(
        activeLeaseId: Long,
        progressPercent: AtomicInteger,
        encoderName: String?,
        bundleLeaseIds: List<Long>,
        process: Process
    ): Thread {
        val othersToHeartbeat = bundleLeaseIds.filter { it != activeLeaseId }
        return Thread({
            while (running.get() && process.isAlive) {
                try {
                    Thread.sleep(config.progressIntervalSeconds * 1000L)
                    if (process.isAlive) {
                        apiClient.reportProgress(activeLeaseId, progressPercent.get(), encoderName)
                        if (othersToHeartbeat.isNotEmpty()) {
                            apiClient.heartbeatMultiple(othersToHeartbeat)
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "heartbeat-$activeLeaseId").apply { isDaemon = true }
    }

    private fun writeSentinel(sentinelFile: File, message: String) {
        try {
            sentinelFile.writeText(message)
        } catch (e: Exception) {
            log.warn("Failed to write sentinel file {}: {}", sentinelFile.name, e.message)
        }
    }

    private fun estimateDurationMinutes(file: File): Int {
        val sizeBytes = file.length()
        val estimatedSeconds = sizeBytes / (2.0 * 1024 * 1024)
        return (estimatedSeconds / 60).toInt().coerceAtLeast(1)
    }
}
