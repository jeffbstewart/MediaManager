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
                    // Nothing to do — sleep longer so we're not
                    // scanning the track table every 2s when idle.
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

    /** Pick the next eligible track. Ordered oldest-id-first for stable progress. */
    private fun nextCandidate(): Track? = Track.findAll()
        .asSequence()
        .filter { needsAnalysis(it) }
        .sortedBy { it.id ?: Long.MAX_VALUE }
        .firstOrNull()

    private fun needsAnalysis(t: Track): Boolean =
        !t.file_path.isNullOrBlank() &&
        t.bpm_source != "ESSENTIA" &&
        t.bpm_source != "MANUAL"

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
            // Couldn't analyze. Don't flip the source — next cycle
            // retries. Rate-limit by tracking consecutive failures? For
            // now, the POLL_INTERVAL sleep is enough.
            log.warn(
                "EssentiaAgent: analysis failed for track {} \"{}\" ({}); will retry later",
                track.id, track.name, path
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
