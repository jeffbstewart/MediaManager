package net.stewart.transcodebuddy

import net.stewart.transcode.EncoderProfile
import net.stewart.transcode.ThumbnailSpriteGenerator
import net.stewart.transcode.TranscodeCommand
import net.stewart.transcode.probeForBrowser
import net.stewart.transcode.probeVideo
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TranscodeWorker(
    private val config: BuddyConfig,
    private val apiClient: BuddyApiClient,
    private val pathTranslator: PathTranslator,
    private val encoder: EncoderProfile,
    private val workerIndex: Int,
    private val running: AtomicBoolean
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

    override fun run() {
        log.info("Worker-{} started (encoder: {} / {})", workerIndex, encoder.name, encoder.ffmpegEncoder)
        var holdingSleep = false

        while (running.get()) {
            try {
                val didWork = processOne()
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
                    log.debug("Worker-{} no work, sleeping {}s", workerIndex, currentIntervalMs / 1000)
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
     * Claims and processes one transcode job. Returns true if work was done.
     */
    private fun processOne(): Boolean {
        val claim = apiClient.claimWork(skipTypes) ?: return false

        val leaseId = claim.leaseId ?: return false
        val relativePath = claim.relativePath ?: return false

        log.info("Claimed lease {} ({}): {}", leaseId, claim.leaseType, relativePath)

        // Dispatch based on lease type
        if (claim.leaseType == "THUMBNAILS") {
            return processThumbnails(leaseId, relativePath)
        }
        if (claim.leaseType == "SUBTITLES") {
            return processSubtitles(leaseId, relativePath)
        }

        val sourceFile = pathTranslator.sourceFile(relativePath)
        if (!sourceFile.exists()) {
            log.error("Source file not found: {}", sourceFile.absolutePath)
            apiClient.reportFailure(leaseId, "Source file not found on local NAS mount: ${sourceFile.absolutePath}")
            return true
        }

        val mp4File = pathTranslator.forBrowserPath(relativePath)
        val tmpFile = pathTranslator.tmpPath(relativePath)

        // Ensure parent directories exist
        mp4File.parentFile?.mkdirs()

        try {
            val probe = probeVideo(config.ffmpegPath, sourceFile)
            val durationSecs = probe.durationSecs

            // Log codec decision
            if (probe.browserSafe && !probe.needsVideoFilter) {
                log.info("Source codec '{}' is browser-safe (no filters needed), copying video", probe.codec)
            } else if (probe.browserSafe) {
                log.info("Source codec '{}' is H.264 but needs re-encode for Roku (SAR={}/{}  fps={}  interlaced={})",
                    probe.codec, probe.sarNum, probe.sarDen, probe.fps, probe.interlaced)
            } else {
                log.info("Source codec '{}' needs re-encoding with {} (interlaced={})", probe.codec, encoder.ffmpegEncoder, probe.interlaced)
            }

            val (command, encoderName) = TranscodeCommand.build(
                config.ffmpegPath, sourceFile, tmpFile, probe, encoder
            )

            log.info("Running: {}", command.joinToString(" "))

            val process = ProcessBuilder(command).redirectErrorStream(true).start()

            // Background heartbeat/progress thread
            val progressPercent = AtomicInteger(0)
            val heartbeatThread = Thread({
                while (running.get() && process.isAlive) {
                    try {
                        Thread.sleep(config.progressIntervalSeconds * 1000L)
                        if (process.isAlive) {
                            apiClient.reportProgress(leaseId, progressPercent.get(), encoderName)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "heartbeat-$leaseId").apply {
                isDaemon = true
                start()
            }

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

            // Atomic rename .tmp -> .mp4
            if (!tmpFile.renameTo(mp4File)) {
                log.error("Failed to rename {} -> {}", tmpFile.absolutePath, mp4File.absolutePath)
                tmpFile.delete()
                apiClient.reportFailure(leaseId, "Failed to rename .tmp to .mp4")
                return true
            }

            log.info("Transcode complete: {} (size={})", mp4File.absolutePath, mp4File.length())

            // Probe the output file for diagnostics
            val outputProbe = try {
                probeForBrowser(config.ffmpegPath, mp4File)
            } catch (e: Exception) {
                log.warn("Failed to probe output file {}: {}", mp4File.name, e.message)
                null
            }

            // Report success with retries (includes probe data)
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
     * Processes a THUMBNAILS lease: generates sprite sheets + VTT for a ForBrowser MP4.
     */
    private fun processThumbnails(leaseId: Long, relativePath: String): Boolean {
        val mp4File = pathTranslator.forBrowserPath(relativePath)
        if (!mp4File.exists()) {
            // Maybe it's a direct MP4 (not a transcode)
            val sourceFile = pathTranslator.sourceFile(relativePath)
            if (sourceFile.exists() && sourceFile.extension.lowercase() in setOf("mp4", "m4v")) {
                return doThumbnailGeneration(leaseId, sourceFile, relativePath)
            }
            log.error("MP4 file not found for thumbnails: {}", mp4File.absolutePath)
            apiClient.reportFailure(leaseId, "MP4 file not found: ${mp4File.name}")
            return true
        }
        return doThumbnailGeneration(leaseId, mp4File, relativePath)
    }

    private fun doThumbnailGeneration(leaseId: Long, mp4File: File, relativePath: String): Boolean {
        log.info("Generating thumbnails for: {}", mp4File.name)
        apiClient.reportProgress(leaseId, 10, null)

        val success = ThumbnailSpriteGenerator.generate(config.ffmpegPath, mp4File)

        if (success) {
            log.info("Thumbnails complete for: {}", mp4File.name)

            // Copy sprites to source directory alongside the original MKV
            try {
                val sourceFile = pathTranslator.sourceFile(relativePath)
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

            apiClient.reportComplete(leaseId, null)
        } else {
            log.warn("Thumbnail generation failed for: {}", mp4File.name)
            apiClient.reportFailure(leaseId, "FFmpeg thumbnail generation failed")
        }
        return true
    }

    /**
     * Processes a SUBTITLES lease: generates SRT subtitles via Whisper CLI.
     * Writes to ForBrowser as .tmp, renames on success, then copies to source directory.
     */
    private fun processSubtitles(leaseId: Long, relativePath: String): Boolean {
        val whisperPath = config.whisperPath
        if (whisperPath == null || !File(whisperPath).exists()) {
            log.warn("Whisper not configured or not found at '{}', failing subtitles lease", whisperPath)
            apiClient.reportFailure(leaseId, "Whisper not configured (whisper_path not set or not found)")
            return true
        }

        // Find the source MKV file (Whisper works best on original audio)
        val sourceFile = pathTranslator.sourceFile(relativePath)
        if (!sourceFile.exists()) {
            log.error("Source file not found for subtitles: {}", sourceFile.absolutePath)
            apiClient.reportFailure(leaseId, "Source file not found: ${sourceFile.absolutePath}")
            return true
        }

        // Determine the MP4 file location (where the SRT will live)
        val mp4File = pathTranslator.forBrowserPath(relativePath)
        val srtBaseName = mp4File.nameWithoutExtension + ".${config.whisperLanguage}.srt"
        val srtFile = File(mp4File.parentFile, srtBaseName)
        val sentinelFile = File(mp4File.parentFile, "$srtBaseName.failed")
        val tmpDir = mp4File.parentFile

        // Ensure output directory exists
        tmpDir?.mkdirs()

        log.info("Generating subtitles for: {} -> {}", sourceFile.name, srtFile.name)
        apiClient.reportProgress(leaseId, 5, null)

        try {
            // Build Whisper command
            val command = mutableListOf(
                whisperPath,
                sourceFile.absolutePath,
                "--model", config.whisperModel,
                "--language", config.whisperLanguage,
                "--output_format", "srt",
                "--output_dir", tmpDir.absolutePath,
                "--device", "cuda",
                "--compute_type", "float16"
            )
            if (config.whisperModelDir != null) {
                command.addAll(listOf("--model_dir", config.whisperModelDir!!))
            }

            log.info("Running: {}", command.joinToString(" "))
            val process = ProcessBuilder(command).redirectErrorStream(true).start()

            // Background heartbeat thread
            val heartbeatThread = Thread({
                while (running.get() && process.isAlive) {
                    try {
                        Thread.sleep(config.progressIntervalSeconds * 1000L)
                        if (process.isAlive) {
                            apiClient.reportProgress(leaseId, 50, null)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "whisper-heartbeat-$leaseId").apply {
                isDaemon = true
                start()
            }

            val outputBuilder = StringBuilder()
            process.inputStream.bufferedReader().forEachLine { line ->
                if (!running.get()) {
                    process.destroyForcibly()
                    return@forEachLine
                }
                outputBuilder.appendLine(line)
            }

            process.waitFor()
            heartbeatThread.interrupt()
            heartbeatThread.join(2000)

            if (!running.get()) return true

            // Whisper outputs the SRT with the source filename base, not the MP4 base.
            // E.g., for source "Movie.mkv" it creates "Movie.srt" in the output dir.
            val whisperOutputFile = File(tmpDir, sourceFile.nameWithoutExtension + ".srt")

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                // Whisper sometimes crashes on cleanup after writing output successfully.
                // Check for the output file before treating as failure.
                if (whisperOutputFile.exists() && whisperOutputFile.length() > 100) {
                    log.warn("Whisper exited with {} but output file exists ({}B), treating as success",
                        exitCode, whisperOutputFile.length())
                } else {
                    val errorTail = outputBuilder.toString().takeLast(500)
                    log.error("Whisper failed for {} (exit {}): {}", relativePath, exitCode, errorTail)
                    writeSentinel(sentinelFile, "Whisper exit code $exitCode: $errorTail")
                    apiClient.reportFailure(leaseId, "Whisper exit code $exitCode")
                    return true
                }
            }

            if (!whisperOutputFile.exists()) {
                log.error("Whisper produced no output file: expected {}", whisperOutputFile.absolutePath)
                writeSentinel(sentinelFile, "No output file produced")
                apiClient.reportFailure(leaseId, "Whisper produced no output file")
                return true
            }

            // Validate output quality
            val srtContent = whisperOutputFile.readText()
            val cueCount = srtContent.lines().count { it.contains(" --> ") }
            val fileDurationMinutes = estimateDurationMinutes(sourceFile)

            if (srtContent.length < 100 || (cueCount < 5 && fileDurationMinutes > 10)) {
                log.warn("Whisper output too sparse for {}: {} bytes, {} cues, ~{} min",
                    sourceFile.name, srtContent.length, cueCount, fileDurationMinutes)
                whisperOutputFile.delete()
                writeSentinel(sentinelFile, "Output too sparse: $cueCount cues for ~${fileDurationMinutes}min file")
                apiClient.reportFailure(leaseId, "Whisper output too sparse ($cueCount cues)")
                return true
            }

            // Rename Whisper output to our naming convention in ForBrowser
            if (whisperOutputFile.absolutePath != srtFile.absolutePath) {
                whisperOutputFile.renameTo(srtFile)
            }

            log.info("Subtitles complete: {} ({} cues)", srtFile.name, cueCount)

            // Copy to source directory alongside the original MKV
            try {
                val sourceSrtFile = File(sourceFile.parentFile, sourceFile.nameWithoutExtension + ".${config.whisperLanguage}.srt")
                srtFile.copyTo(sourceSrtFile, overwrite = true)
                log.info("Copied subtitles to source: {}", sourceSrtFile.absolutePath)
            } catch (e: Exception) {
                log.warn("Failed to copy subtitles to source directory: {}", e.message)
                // Non-fatal — ForBrowser copy is the primary
            }

            apiClient.reportComplete(leaseId, null)
            return true

        } catch (e: Exception) {
            log.error("Subtitle generation error for {}: {}", relativePath, e.message, e)
            writeSentinel(sentinelFile, e.message ?: "Unknown error")
            apiClient.reportFailure(leaseId, e.message ?: "Unknown error")
            return true
        }
    }

    private fun writeSentinel(sentinelFile: File, message: String) {
        try {
            sentinelFile.writeText(message)
        } catch (e: Exception) {
            log.warn("Failed to write sentinel file {}: {}", sentinelFile.name, e.message)
        }
    }

    /**
     * Rough duration estimate from file size, used for sparse-output validation.
     * Assumes ~500 KB/s for DVD, ~4 MB/s for Blu-ray. Returns minutes.
     */
    private fun estimateDurationMinutes(file: File): Int {
        val sizeBytes = file.length()
        // Assume ~2 MB/s average bitrate as a rough middle ground
        val estimatedSeconds = sizeBytes / (2.0 * 1024 * 1024)
        return (estimatedSeconds / 60).toInt().coerceAtLeast(1)
    }

}
