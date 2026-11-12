package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Track
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Background daemon that computes ML-grade BPM for every [Track] that
 * has a playable `file_path` but isn't yet marked `bpm_source=ESSENTIA`.
 * One track at a time — Essentia is CPU-bound (~5-10s per track on a
 * modern core) and we don't want to saturate the NAS while the user
 * is browsing.
 *
 * Precedence rules:
 *   - TAG rows are re-analyzed. Essentia's result supersedes the tag
 *     value; we trust the ML over the tagger.
 *   - MANUAL rows are skipped forever. Admin override sticks.
 *   - ESSENTIA rows are skipped (already done).
 *
 * Logging:
 *   - Per-track at INFO: path, previous BPM + source, new BPM + confidence.
 *   - Every 30 seconds (while there's a backlog) at INFO: how many
 *     tracks remain in the queue so the admin can track progress in
 *     binnacle without polling any endpoint.
 */
class EssentiaAgent(
    private val clock: Clock = SystemClock
) {

    private val log = LoggerFactory.getLogger(EssentiaAgent::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    companion object {
        /** Sleep between tracks. Keeps CPU usage polite on a shared NAS. */
        private val POLL_INTERVAL = 2.seconds
        /** How often to emit a backlog-size heartbeat while we have work. */
        private val BACKLOG_LOG_INTERVAL = 30.seconds
        /** When the queue is empty we check again after this long. */
        private val IDLE_INTERVAL = 5.minutes
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("EssentiaAgent starting")
            if (!EssentiaService.isAvailable()) {
                log.warn(
                    "EssentiaAgent: essentia_streaming_extractor_music not on PATH — " +
                    "ML BPM analysis DISABLED for this process. The Docker image bundles " +
                    "the binary; if you're seeing this in a dev run, install essentia " +
                    "locally or skip."
                )
                running.set(false)
                return@Thread
            }
            log.info("EssentiaAgent started — polling for tracks needing BPM analysis")
            runLoop()
            log.info("EssentiaAgent stopped")
        }, "essentia-agent").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        var lastBacklogLogAtMs = 0L
        while (running.get()) {
            try {
                val backlog = remainingCount()
                val now = clock.currentTimeMillis()
                if (backlog > 0 && now - lastBacklogLogAtMs >= BACKLOG_LOG_INTERVAL.inWholeMilliseconds) {
                    log.info("EssentiaAgent backlog: {} track(s) still need BPM analysis", backlog)
                    lastBacklogLogAtMs = now
                }

                val next = nextCandidate()
                if (next == null) {
                    // Before idling, check whether any previously-failed
                    // tracks have had their files replaced (re-rip,
                    // manual repair). Any that have move back into the
                    // analysis queue automatically — the admin doesn't
                    // need to hand-reset the row.
                    val recovered = sweepRecoveredFailures()
                    if (recovered > 0) {
                        log.info(
                            "EssentiaAgent: {} previously-failed track(s) have newer " +
                            "files on disk; requeued for analysis.", recovered
                        )
                        // Skip the idle sleep — we likely have work now.
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
                log.error("EssentiaAgent cycle error", e)
                try { clock.sleep(POLL_INTERVAL) } catch (_: InterruptedException) { break }
            }
        }
    }

    /** Count of tracks still needing Essentia analysis — for the heartbeat log. */
    private fun remainingCount(): Int = Track.findAll().count { needsAnalysis(it) }

    /**
     * Look for ESSENTIA_FAILED rows whose underlying file has a newer
     * mtime than when we recorded the failure. Those get their
     * bpm_source flipped back to 'TAG' so the normal queue picks them
     * up on the next cycle. Returns the count of rows requeued.
     *
     * Runs on the idle path in runLoop — no extra timers. A row whose
     * file is missing or whose mtime hasn't moved is left alone.
     */
    private fun sweepRecoveredFailures(): Int {
        val failed = Track.findAll().filter {
            it.bpm_source == "ESSENTIA_FAILED" && !it.file_path.isNullOrBlank()
        }
        if (failed.isEmpty()) return 0
        var recovered = 0
        for (track in failed) {
            val file = File(track.file_path!!)
            if (!file.isFile) continue
            val currentMtime = file.lastModified() / 1000
            val storedMtime = track.bpm_analysis_failed_mtime ?: 0L
            if (currentMtime > storedMtime) {
                track.bpm_source = "TAG"
                track.bpm_analysis_failed_mtime = null
                track.updated_at = clock.now()
                track.save()
                recovered++
                log.info(
                    "EssentiaAgent: track {} \"{}\" file mtime advanced ({} -> {}); " +
                    "flipped bpm_source back to TAG for re-analysis.",
                    track.id, track.name, storedMtime, currentMtime
                )
            }
        }
        return recovered
    }

    /** Pick the next eligible track. Ordered oldest-id-first for stable progress. */
    private fun nextCandidate(): Track? = Track.findAll()
        .asSequence()
        .filter { needsAnalysis(it) }
        .sortedBy { it.id ?: Long.MAX_VALUE }
        .firstOrNull()

    private fun needsAnalysis(t: Track): Boolean =
        !t.file_path.isNullOrBlank() &&
        t.bpm_source != "ESSENTIA" &&
        t.bpm_source != "MANUAL" &&
        // Tried once, rejected (silent file / corrupt container / etc.).
        // Don't keep knocking on it every cycle — if the admin repairs
        // the file they can reset the row back to TAG to retry.
        t.bpm_source != "ESSENTIA_FAILED"

    /**
     * Run Essentia on one track and persist the result. Logs the
     * before/after at INFO so the progression is visible in binnacle.
     */
    private fun analyzeOne(track: Track) {
        val path = track.file_path ?: return
        val file = File(path)
        if (!file.isFile) {
            // File referenced in DB but missing on disk — rare, but
            // don't keep trying. Demote to MANUAL so this agent skips it.
            log.warn(
                "EssentiaAgent: track {} \"{}\" file_path {} is missing on disk, " +
                "marking bpm_source=MANUAL to drop it from the queue",
                track.id, track.name, path
            )
            track.bpm_source = "MANUAL"
            track.save()
            return
        }

        val before = "bpm=${track.bpm} source=${track.bpm_source}"
        val started = clock.currentTimeMillis()
        val rhythm = EssentiaService.analyzeRhythm(file)
        val elapsedMs = clock.currentTimeMillis() - started

        if (rhythm == null) {
            // Analysis failed. Capture the file's current mtime so the
            // idle-sweep can auto-requeue this row if the file is
            // re-ripped or repaired later. Preserves whatever bpm the
            // tag reader had set; only changes provenance + failure mark.
            track.bpm_source = "ESSENTIA_FAILED"
            track.bpm_analysis_failed_mtime = file.lastModified() / 1000
            track.updated_at = clock.now()
            track.save()
            log.warn(
                "EssentiaAgent: analysis FAILED for track {} \"{}\" ({}); " +
                "marked ESSENTIA_FAILED at mtime={}. The idle sweep will " +
                "auto-requeue if the file is replaced.",
                track.id, track.name, path, track.bpm_analysis_failed_mtime
            )
            return
        }

        track.bpm = rhythm.bpm
        track.bpm_source = "ESSENTIA"
        track.bpm_confidence = rhythm.confidence
        track.updated_at = clock.now()
        track.save()

        log.info(
            "EssentiaAgent: track id={} \"{}\" before=[{}] -> bpm={} confidence={} " +
            "beats={} ({} ms)",
            track.id, track.name, before,
            rhythm.bpm,
            rhythm.confidence?.let { "%.2f".format(it) } ?: "(none)",
            rhythm.beatsCount ?: "(none)",
            elapsedMs
        )
    }
}
