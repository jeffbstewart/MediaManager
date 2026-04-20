package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Track
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Background daemon that fills in per-track time signatures via the
 * madmom sidecar. Mirrors the structure of [EssentiaAgent] — same
 * startup probe, per-track logging, backlog heartbeat, failure-mtime
 * auto-requeue pattern — just with a different external process and
 * a different column target.
 *
 * Queue shape:
 *   - `file_path` must be non-null (nothing to analyze otherwise).
 *   - `bpm_source` must be `ESSENTIA` — we only trust Essentia-validated
 *     tracks. Skips silent / corrupt rips that Essentia already marked
 *     FAILED. Broaden to TAG-sourced tracks later if desired.
 *   - `time_signature_source` must be `TAG` (not yet analyzed) or
 *     `MADMOM_FAILED` with an outdated failure mtime (file was
 *     replaced — sweep requeues it).
 *
 * MANUAL rows are sacred; we never overwrite them. Madmom results
 * supersede TAG values (which are rare for time-sig anyway).
 */
class TimeSignatureAgent(
    private val clock: Clock = SystemClock
) {

    private val log = LoggerFactory.getLogger(TimeSignatureAgent::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    companion object {
        private val POLL_INTERVAL = 2.seconds
        private val BACKLOG_LOG_INTERVAL = 60.seconds
        private val IDLE_INTERVAL = 10.minutes
        /**
         * Retry delays for the initial availability probe. Sized so the
         * total covers the plausible startup race: MM and the sidecar
         * both launched by `docker-compose up` can see each other come
         * alive within seconds, but first-time image pulls or pip-installs
         * on a cold sidecar push the window to a minute or two. Six
         * attempts, total ~135 s of waiting.
         */
        private val STARTUP_RETRY_DELAYS = listOf(
            5.seconds, 10.seconds, 15.seconds, 30.seconds, 60.seconds, 60.seconds
        )
        /** How often the main loop re-probes when the sidecar is absent. */
        private val ABSENT_RECHECK_INTERVAL = 5.minutes
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("TimeSignatureAgent starting; probing madmom sidecar")
            val reachedOnStartup = probeWithRetries()
            if (!reachedOnStartup) {
                log.warn(
                    "TimeSignatureAgent: madmom sidecar still unreachable after {} " +
                    "startup attempts — will re-check every {}min while running. " +
                    "Deploy the mediamanager-madmom container (see docker-compose.yml) " +
                    "or set madmom_sidecar_url in Settings to fix.",
                    STARTUP_RETRY_DELAYS.size, ABSENT_RECHECK_INTERVAL.inWholeMinutes
                )
            } else {
                log.info("TimeSignatureAgent started — polling for tracks needing analysis")
            }
            // Enter the loop either way: a sidecar that wasn't ready
            // at boot may come up shortly after (image still pulling,
            // host still bringing dependencies online) and we want to
            // recover without requiring a MM restart.
            runLoop(initiallyAvailable = reachedOnStartup)
            log.info("TimeSignatureAgent stopped")
        }, "time-signature-agent").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Probe the sidecar repeatedly with the configured backoff. Any
     * single success short-circuits. Returns false if every attempt
     * failed or the agent was stopped mid-wait.
     */
    private fun probeWithRetries(): Boolean {
        for ((idx, delay) in STARTUP_RETRY_DELAYS.withIndex()) {
            if (!running.get()) return false
            if (MadmomClient.isAvailable()) return true
            log.info(
                "TimeSignatureAgent probe {}/{} failed; sleeping {}s before retry",
                idx + 1, STARTUP_RETRY_DELAYS.size, delay.inWholeSeconds
            )
            try {
                clock.sleep(delay)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun runLoop(initiallyAvailable: Boolean) {
        var sidecarReady = initiallyAvailable
        var lastBacklogLogAtMs = 0L
        var lastAbsentRecheckMs = 0L
        while (running.get()) {
            try {
                // If the sidecar was missing at startup, re-probe
                // periodically — a human deploying the sidecar late
                // shouldn't need to restart MM for analysis to begin.
                if (!sidecarReady) {
                    val now = clock.currentTimeMillis()
                    if (now - lastAbsentRecheckMs >= ABSENT_RECHECK_INTERVAL.inWholeMilliseconds) {
                        lastAbsentRecheckMs = now
                        if (MadmomClient.isAvailable()) {
                            log.info("TimeSignatureAgent: madmom sidecar reachable on re-probe; enabling analysis")
                            sidecarReady = true
                        }
                    }
                    if (!sidecarReady) {
                        clock.sleep(ABSENT_RECHECK_INTERVAL)
                        continue
                    }
                }

                val backlog = remainingCount()
                val now = clock.currentTimeMillis()
                if (backlog > 0 && now - lastBacklogLogAtMs >= BACKLOG_LOG_INTERVAL.inWholeMilliseconds) {
                    log.info("TimeSignatureAgent backlog: {} track(s) pending", backlog)
                    lastBacklogLogAtMs = now
                }

                val next = nextCandidate()
                if (next == null) {
                    val recovered = sweepRecoveredFailures()
                    if (recovered > 0) {
                        log.info(
                            "TimeSignatureAgent: {} previously-failed track(s) have " +
                            "newer files; requeued for analysis.", recovered
                        )
                        continue
                    }
                    clock.sleep(IDLE_INTERVAL)
                    continue
                }

                analyzeOne(next)
                clock.sleep(POLL_INTERVAL)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                log.error("TimeSignatureAgent cycle error", e)
                try { clock.sleep(POLL_INTERVAL) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun remainingCount(): Int = Track.findAll().count { needsAnalysis(it) }

    private fun nextCandidate(): Track? = Track.findAll()
        .asSequence()
        .filter { needsAnalysis(it) }
        .sortedBy { it.id ?: Long.MAX_VALUE }
        .firstOrNull()

    private fun needsAnalysis(t: Track): Boolean =
        !t.file_path.isNullOrBlank() &&
        t.bpm_source == "ESSENTIA" &&
        t.time_signature_source != "MADMOM" &&
        t.time_signature_source != "MANUAL" &&
        t.time_signature_source != "MADMOM_FAILED"

    private fun analyzeOne(track: Track) {
        val path = track.file_path ?: return
        val file = File(path)
        if (!file.isFile) {
            log.warn(
                "TimeSignatureAgent: track {} \"{}\" file_path {} missing on disk, " +
                "marking time_signature_source=MANUAL to drop from queue",
                track.id, track.name, path
            )
            track.time_signature_source = "MANUAL"
            track.save()
            return
        }

        val before = "time_sig=${track.time_signature} source=${track.time_signature_source}"
        val started = clock.currentTimeMillis()
        val rhythm = MadmomClient.analyze(path)
        val elapsedMs = clock.currentTimeMillis() - started

        if (rhythm == null || rhythm.timeSignature == null) {
            // Record the failure + file mtime so the sweep can auto-
            // requeue if the file is later replaced.
            track.time_signature_source = "MADMOM_FAILED"
            // Reuse the existing bpm_analysis_failed_mtime column —
            // adding a second mtime column for this would be premature;
            // the file either changed or it didn't, regardless of which
            // analyzer last failed on it.
            track.bpm_analysis_failed_mtime = file.lastModified() / 1000
            track.updated_at = clock.now()
            track.save()
            log.warn(
                "TimeSignatureAgent: analysis FAILED for track {} \"{}\" ({}); " +
                "marked MADMOM_FAILED at mtime={}. {} ms",
                track.id, track.name, path, track.bpm_analysis_failed_mtime, elapsedMs
            )
            return
        }

        track.time_signature = rhythm.timeSignature
        track.time_signature_source = "MADMOM"
        track.updated_at = clock.now()
        track.save()

        log.info(
            "TimeSignatureAgent: track id={} \"{}\" before=[{}] -> time_sig={} " +
            "confidence={} beats={} madmom_bpm={} ({} ms)",
            track.id, track.name, before, rhythm.timeSignature,
            rhythm.downbeatConfidence?.let { "%.2f".format(it) } ?: "(none)",
            rhythm.beatCount ?: "(none)",
            rhythm.bpm ?: "(none)",
            elapsedMs
        )
    }

    /**
     * Flip MADMOM_FAILED rows back to TAG when the file has been
     * replaced since failure. Mirrors the EssentiaAgent sweep; the two
     * agents share the `bpm_analysis_failed_mtime` column so a single
     * file repair requeues both analyses automatically.
     */
    private fun sweepRecoveredFailures(): Int {
        val failed = Track.findAll().filter {
            it.time_signature_source == "MADMOM_FAILED" && !it.file_path.isNullOrBlank()
        }
        if (failed.isEmpty()) return 0
        var recovered = 0
        for (track in failed) {
            val file = File(track.file_path!!)
            if (!file.isFile) continue
            val currentMtime = file.lastModified() / 1000
            val storedMtime = track.bpm_analysis_failed_mtime ?: 0L
            if (currentMtime > storedMtime) {
                track.time_signature_source = "TAG"
                track.updated_at = clock.now()
                track.save()
                recovered++
                log.info(
                    "TimeSignatureAgent: track {} \"{}\" file mtime advanced ({} -> {}); " +
                    "flipped time_signature_source back to TAG for re-analysis.",
                    track.id, track.name, storedMtime, currentMtime
                )
            }
        }
        return recovered
    }
}
