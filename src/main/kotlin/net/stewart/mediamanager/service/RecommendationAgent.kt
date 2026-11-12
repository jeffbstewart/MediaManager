package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppUser
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Background daemon that rebuilds per-user library recommendations
 * once a day. Mirrors [MusicScannerAgent]'s lifecycle shape: daemon
 * thread, long initial delay so it doesn't compete with startup work,
 * cycle runs while [running] stays true. See docs/MUSIC.md §M8.
 *
 * Per-user runtime is bounded by [RecommendationService]'s own caps
 * (top N + capped representative-release lookups), so the whole pass
 * across a handful of users is at most a few dozen MB requests plus
 * cheap cache reads.
 */
class RecommendationAgent(
    private val clock: Clock = SystemClock,
    private val musicBrainz: MusicBrainzService = MusicBrainzHttpService()
) {

    private val log = LoggerFactory.getLogger(RecommendationAgent::class.java)
    internal val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val mutex = Any()

    init { INSTANCE = this }

    companion object {
        private val CYCLE_INTERVAL = 24.hours
        private val STARTUP_DELAY = 3.minutes

        @Volatile private var INSTANCE: RecommendationAgent? = null

        /** Manual trigger from the admin-initiated refresh endpoint. */
        fun refreshNowIfAvailable() { INSTANCE?.refreshNow() }

        /**
         * Per-user manual refresh. Delegates to the running daemon so the
         * mutex is shared with the daily pass (no double-work if both
         * fire close together). Returns false when the agent isn't up yet.
         */
        fun refreshForUserIfAvailable(userId: Long): Boolean {
            val inst = INSTANCE ?: return false
            inst.refreshForUser(userId)
            return true
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("RecommendationAgent started (cycle every {}h)", CYCLE_INTERVAL.inWholeHours)
            try { clock.sleep(STARTUP_DELAY) } catch (_: InterruptedException) { return@Thread }
            while (running.get()) {
                try {
                    synchronized(mutex) { runOnce() }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("RecommendationAgent error: {}", e.message, e)
                }
                try { clock.sleep(CYCLE_INTERVAL) } catch (_: InterruptedException) { break }
            }
            log.info("RecommendationAgent stopped")
        }, "recommendation-agent").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    fun refreshNow() {
        synchronized(mutex) {
            try { runOnce() } catch (e: Exception) {
                log.error("RecommendationAgent.refreshNow error: {}", e.message, e)
            }
        }
    }

    /** Refresh one user on demand — used by the per-user refresh endpoint. */
    fun refreshForUser(userId: Long) {
        synchronized(mutex) {
            try {
                val count = RecommendationService.recompute(userId, musicBrainz)
                log.info("RecommendationAgent: refreshed user {} → {} entries", userId, count)
            } catch (e: Exception) {
                log.error("RecommendationAgent.refreshForUser({}) error: {}", userId, e.message, e)
            }
        }
    }

    internal fun runOnce() {
        val users = AppUser.findAll().filter { it.id != null && !it.locked }
        if (users.isEmpty()) return
        log.info("RecommendationAgent: running daily pass for {} user(s)", users.size)
        for (user in users) {
            if (!running.get()) break
            val uid = user.id ?: continue
            try {
                val count = RecommendationService.recompute(uid, musicBrainz)
                log.info("RecommendationAgent: user {} → {} entries", uid, count)
            } catch (e: Exception) {
                log.warn("RecommendationAgent: user {} pass failed: {}", uid, e.message)
            }
        }
    }
}
