package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Transcode
import net.stewart.transcode.probeForBrowser
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Background daemon that periodically validates ForBrowser MP4 files.
 *
 * Probes each file with FFmpeg and deletes any with non-browser-safe codecs,
 * multichannel audio (>2ch), or extra streams (data, subtitle, attachment tracks)
 * so they get re-transcoded cleanly by the TranscoderAgent.
 *
 * Runs once per day, tracked via `forbrowser_validator_last_run` in app_config.
 * Throttles probes with a 2-second gap to avoid hammering the NAS.
 */
class ForBrowserValidator(
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(ForBrowserValidator::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val deletedCounter = MetricsRegistry.registry.counter(
        "mm_forbrowser_validations_deleted_total"
    )

    companion object {
        private const val CONFIG_KEY = "forbrowser_validator_last_run"
        private const val FOR_BROWSER_DIR = "ForBrowser"
        private val INITIAL_DELAY = 30.seconds
        private val CHECK_INTERVAL = 1.hours
        private val PROBE_THROTTLE = 2.seconds
        private const val PROGRESS_LOG_INTERVAL = 100
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("ForBrowser Validator started")
            try {
                clock.sleep(INITIAL_DELAY)
            } catch (_: InterruptedException) {
                return@Thread
            }

            while (running.get()) {
                try {
                    if (isDue()) {
                        runValidation()
                        recordCompletion()
                    } else {
                        log.debug("ForBrowser validation not yet due, checking again in 1 hour")
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("ForBrowser validation error", e)
                }
                try {
                    clock.sleep(CHECK_INTERVAL)
                } catch (_: InterruptedException) {
                    break
                }
            }
            log.info("ForBrowser Validator stopped")
        }, "forbrowser-validator").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /**
     * Returns true if validation should run: no previous run recorded,
     * timestamp is malformed, or more than 24 hours have passed.
     */
    internal fun isDue(): Boolean {
        val lastRun = AppConfig.findAll()
            .firstOrNull { it.config_key == CONFIG_KEY }
            ?.config_val ?: return true

        return try {
            val lastTime = LocalDateTime.parse(lastRun, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val hoursSince = ChronoUnit.HOURS.between(lastTime, clock.now())
            hoursSince >= 24
        } catch (_: Exception) {
            log.warn("Malformed {} value '{}', will re-run", CONFIG_KEY, lastRun)
            true
        }
    }

    /**
     * Records the current time as the last successful validation run.
     * Creates or updates the app_config row.
     */
    internal fun recordCompletion() {
        val timestamp = clock.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val existing = AppConfig.findAll().firstOrNull { it.config_key == CONFIG_KEY }
        if (existing != null) {
            existing.config_val = timestamp
            existing.save()
        } else {
            AppConfig(
                config_key = CONFIG_KEY,
                config_val = timestamp,
                description = "Last successful ForBrowser validation run"
            ).save()
        }
    }

    private fun runValidation() {
        val nasRoot = TranscoderAgent.getNasRoot() ?: run {
            log.debug("NAS root not configured, skipping ForBrowser validation")
            return
        }
        val ffmpegPath = TranscoderAgent.getFfmpegPath()
        if (!File(ffmpegPath).exists()) {
            log.warn("FFmpeg not found at '{}', skipping ForBrowser validation", ffmpegPath)
            return
        }

        val forBrowserDir = File(nasRoot, FOR_BROWSER_DIR)
        if (!forBrowserDir.exists()) {
            log.debug("ForBrowser directory does not exist, skipping validation")
            return
        }

        val mp4Files = forBrowserDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "mp4" }
            .toList()

        if (mp4Files.isEmpty()) {
            log.info("ForBrowser validation: no MP4 files found")
            return
        }

        log.info("ForBrowser validation: checking {} files", mp4Files.size)

        var deletedCodec = 0
        var deletedAudio = 0
        var deletedStreams = 0
        var checked = 0

        for (mp4 in mp4Files) {
            if (!running.get()) {
                log.info("ForBrowser validation interrupted after checking {} files", checked)
                return
            }

            var reason: String? = null

            val codec = TranscoderAgent.probeVideoCodec(ffmpegPath, mp4)
            if (!TranscoderAgent.isBrowserSafeCodec(codec)) {
                reason = "non-browser-safe codec '$codec'"
            } else {
                val channels = TranscoderAgent.probeAudioChannels(ffmpegPath, mp4)
                if (channels != null && channels > 2) {
                    reason = "multichannel audio (${channels}ch)"
                } else {
                    val streamCount = TranscoderAgent.probeStreamCount(ffmpegPath, mp4)
                    if (streamCount > 2) {
                        reason = "extra streams (${streamCount} total, expected 2)"
                    }
                }
            }

            if (reason != null) {
                log.info("Deleting ForBrowser file ({}): {}", reason, mp4.name)
                mp4.delete()
                deletedCounter.increment()
                when {
                    reason.startsWith("non-browser") -> deletedCodec++
                    reason.startsWith("multichannel") -> deletedAudio++
                    else -> deletedStreams++
                }
            }

            checked++
            if (checked % PROGRESS_LOG_INTERVAL == 0) {
                log.info("ForBrowser validation progress: {}/{} checked", checked, mp4Files.size)
            }

            // Throttle probes to avoid hammering the NAS
            try {
                clock.sleep(PROBE_THROTTLE)
            } catch (_: InterruptedException) {
                log.info("ForBrowser validation interrupted after checking {} files", checked)
                return
            }
        }

        val totalDeleted = deletedCodec + deletedAudio + deletedStreams
        if (totalDeleted > 0) {
            log.info("ForBrowser validation complete: deleted {} of {} files ({}x bad-codec, {}x multichannel-audio, {}x extra-streams)",
                totalDeleted, mp4Files.size, deletedCodec, deletedAudio, deletedStreams)
        } else {
            log.info("ForBrowser validation complete: all {} files are clean", mp4Files.size)
        }

        // Backfill probe data for files that passed validation but have no probe row
        backfillProbes(nasRoot, ffmpegPath, forBrowserDir)

        // Clean up probe rows for files that no longer exist
        ForBrowserProbeService.cleanupStale(nasRoot)
    }

    /**
     * Backfills probe data for ForBrowser files that passed validation but have no probe row.
     * Matches ForBrowser files to transcode records via path, then probes and records.
     */
    private fun backfillProbes(nasRoot: String, ffmpegPath: String, forBrowserDir: File) {
        val nasRootFile = File(nasRoot)

        // Build map from source relative path -> transcode for matching
        val transcodes = Transcode.findAll().filter { it.file_path != null }
        val transcodeByRelPath = mutableMapOf<String, Transcode>()
        for (tc in transcodes) {
            try {
                val relPath = nasRootFile.toPath().relativize(File(tc.file_path!!).toPath())
                    .toString().replace('\\', '/')
                transcodeByRelPath[relPath] = tc
            } catch (_: Exception) { }
        }

        // Walk ForBrowser files and find ones without probes
        val mp4Files = forBrowserDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "mp4" }
            .toList()

        var backfilled = 0
        for (mp4 in mp4Files) {
            if (!running.get()) break

            // Compute relative path within ForBrowser (e.g., "BLURAY/Movie.mp4")
            val forBrowserRelPath = forBrowserDir.toPath().relativize(mp4.toPath())
                .toString().replace('\\', '/')

            // Match to source path: ForBrowser/BLURAY/Movie.mp4 -> BLURAY/Movie.mkv (or .avi)
            val sourceRelBase = forBrowserRelPath.substringBeforeLast('.') // strip .mp4
            val matchedTranscode = listOf("mkv", "avi").firstNotNullOfOrNull { ext ->
                transcodeByRelPath["$sourceRelBase.$ext"]
            }

            if (matchedTranscode == null) continue
            if (ForBrowserProbeService.hasProbe(matchedTranscode.id!!)) continue

            try {
                val probeResult = probeForBrowser(ffmpegPath, mp4)
                ForBrowserProbeService.recordProbe(
                    transcodeId = matchedTranscode.id!!,
                    relativePath = forBrowserRelPath,
                    probeResult = probeResult,
                    encoder = null,  // unknown for pre-existing files
                    fileSize = mp4.length()
                )
                backfilled++

                // Throttle probes
                clock.sleep(PROBE_THROTTLE)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                log.warn("Failed to backfill probe for {}: {}", mp4.name, e.message)
            }
        }

        if (backfilled > 0) {
            log.info("Backfilled probe data for {} ForBrowser files", backfilled)
        }
    }
}
