package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.TmdbCollection
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Background daemon that gradually refreshes TMDB collection data (parts, poster
 * paths, new entries). Follows the same pattern as PopularityRefreshAgent:
 * cycles through ~1% of stored collections per day so a full refresh completes
 * every few months.
 *
 * On each wake cycle (every 4 hours = 6 times/day):
 * - Picks a small batch of collections with the oldest (or null) fetched_at
 * - Re-fetches from TMDB and updates parts via CollectionService.storeCollection()
 * - Updates collection-aware sort names for affected collections
 *
 * Batch size: ceil(totalCollections / 600) per cycle, minimum 1.
 */
class CollectionRefreshAgent(
    private val tmdbService: TmdbService = TmdbService(),
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(CollectionRefreshAgent::class.java)
    internal val running = AtomicBoolean(false)
    private var thread: Thread? = null

    companion object {
        private val CYCLE_INTERVAL = 4.hours
        private val API_GAP = 500.milliseconds
        private val STARTUP_DELAY = 15.minutes
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("CollectionRefreshAgent started (cycle every {}h, startup delay {}min)",
                CYCLE_INTERVAL.inWholeHours, STARTUP_DELAY.inWholeMinutes)
            try {
                clock.sleep(STARTUP_DELAY)
            } catch (_: InterruptedException) {
                log.info("CollectionRefreshAgent interrupted during startup delay")
                return@Thread
            }
            while (running.get()) {
                try {
                    refreshCollections()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("CollectionRefreshAgent error: {}", e.message, e)
                }
                try {
                    clock.sleep(CYCLE_INTERVAL)
                } catch (_: InterruptedException) {
                    break
                }
            }
            log.info("CollectionRefreshAgent stopped")
        }, "collection-refresh").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    internal fun refreshCollections() {
        val allCollections = TmdbCollection.findAll()
        if (allCollections.isEmpty()) return

        val batchSize = computeBatchSize(allCollections.size)
        val batch = allCollections
            .sortedWith(compareBy(nullsFirst()) { it.fetched_at })
            .take(batchSize)

        log.info("Refreshing {} of {} collections", batch.size, allCollections.size)
        var updated = 0

        for (collection in batch) {
            if (!running.get()) break
            try {
                clock.sleep(API_GAP)
                val result = tmdbService.fetchCollection(collection.tmdb_collection_id)
                if (result.found) {
                    CollectionService.storeCollection(result)
                    CollectionService.updateSortNamesForCollection(collection.tmdb_collection_id)
                    updated++
                } else if (result.errorMessage?.contains("HTTP") == true) {
                    log.warn("API error refreshing collection '{}': {}",
                        collection.name, result.errorMessage)
                    break
                } else {
                    // Collection not found — update fetched_at so we don't retry immediately
                    collection.fetched_at = clock.now()
                    collection.save()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedException()
            } catch (e: Exception) {
                log.warn("Error refreshing collection #{}: {}", collection.id, e.message)
            }
        }
        if (updated > 0) {
            log.info("Updated {}/{} collections", updated, batch.size)
        }
    }

    /**
     * Computes batch size to achieve ~1% per day with 6 cycles/day.
     * ceil(total / 600) ensures full cycle in ~100 days. Minimum 1.
     */
    internal fun computeBatchSize(total: Int): Int {
        return maxOf(1, (total + 599) / 600)
    }
}
