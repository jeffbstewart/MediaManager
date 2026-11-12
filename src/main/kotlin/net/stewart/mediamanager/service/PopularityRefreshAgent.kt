package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Background daemon that gradually refreshes TMDB popularity scores for titles
 * and cast members. Designed to cycle through ~1% of the catalog per day so a
 * full refresh completes every few months.
 *
 * On each wake cycle (every 4 hours = 6 times/day):
 * - Picks a small batch of titles with the oldest (or null) popularity_refreshed_at
 * - Fetches current popularity from TMDB and updates the score
 * - Then does the same for distinct cast members (by tmdb_person_id)
 *
 * Batch size is computed dynamically: ceil(totalCount / 600) per cycle, so
 * ~1% per day (6 cycles × batch ≈ 1% of catalog). Minimum batch of 1.
 */
class PopularityRefreshAgent(
    private val tmdbService: TmdbService = TmdbService(),
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(PopularityRefreshAgent::class.java)
    internal val running = AtomicBoolean(false)
    private var thread: Thread? = null

    companion object {
        private val CYCLE_INTERVAL = 4.hours
        private val API_GAP = 500.milliseconds  // TMDB rate limit
        private val STARTUP_DELAY = 10.minutes   // Let other agents settle first
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("PopularityRefreshAgent started (cycle every {}h, startup delay {}min)",
                CYCLE_INTERVAL.inWholeHours, STARTUP_DELAY.inWholeMinutes)
            try {
                clock.sleep(STARTUP_DELAY)
            } catch (_: InterruptedException) {
                log.info("PopularityRefreshAgent interrupted during startup delay")
                return@Thread
            }
            while (running.get()) {
                try {
                    refreshTitles()
                    refreshCastMembers()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("PopularityRefreshAgent error: {}", e.message, e)
                }
                try {
                    clock.sleep(CYCLE_INTERVAL)
                } catch (_: InterruptedException) {
                    break
                }
            }
            log.info("PopularityRefreshAgent stopped")
        }, "popularity-refresh").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    internal fun refreshTitles() {
        val enrichedTitles = Title.findAll().filter {
            it.tmdb_id != null && it.media_type != MediaType.PERSONAL.name
        }
        if (enrichedTitles.isEmpty()) return

        val batchSize = computeBatchSize(enrichedTitles.size)
        // Pick titles with oldest (or null) popularity_refreshed_at
        val batch = enrichedTitles
            .sortedWith(compareBy(nullsFirst()) { it.popularity_refreshed_at })
            .take(batchSize)

        log.info("Refreshing popularity for {} of {} titles", batch.size, enrichedTitles.size)
        var updated = 0

        for (title in batch) {
            if (!running.get()) break
            try {
                clock.sleep(API_GAP)
                val result = tmdbService.getDetails(title.tmdbKey()!!)
                if (result.found && result.popularity != null) {
                    val oldPop = title.popularity
                    title.popularity = result.popularity
                    title.popularity_refreshed_at = clock.now()
                    title.save()
                    updated++
                    if (oldPop != null && oldPop != result.popularity) {
                        log.debug("Title '{}' popularity: {} -> {}", title.name, oldPop, result.popularity)
                    }
                } else if (result.apiError) {
                    log.warn("API error refreshing '{}': {}", title.name, result.errorMessage)
                    break  // Stop batch on API errors (rate limit, etc.)
                } else {
                    // Title not found on TMDB anymore — just mark as refreshed
                    title.popularity_refreshed_at = clock.now()
                    title.save()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedException()
            } catch (e: Exception) {
                log.warn("Error refreshing title #{}: {}", title.id, e.message)
            }
        }
        if (updated > 0) {
            log.info("Updated popularity for {}/{} titles", updated, batch.size)
        }
    }

    internal fun refreshCastMembers() {
        val allCast = CastMember.findAll().filter { it.tmdb_person_id > 0 }
        if (allCast.isEmpty()) return

        // Group by person ID, pick the record with the oldest refresh time per person
        val byPerson = allCast.groupBy { it.tmdb_person_id }
        val personEntries = byPerson.map { (personId, members) ->
            val oldest = members.minByOrNull { it.popularity_refreshed_at ?: LocalDateTime.MIN }!!
            personId to oldest.popularity_refreshed_at
        }

        val batchSize = computeBatchSize(personEntries.size)
        val batch = personEntries
            .sortedWith(compareBy(nullsFirst()) { it.second })
            .take(batchSize)

        if (batch.isEmpty()) return
        log.info("Refreshing popularity for {} of {} distinct cast members", batch.size, personEntries.size)
        var updated = 0

        for ((personId, _) in batch) {
            if (!running.get()) break
            try {
                clock.sleep(API_GAP)
                val result = tmdbService.fetchPersonDetails(personId)
                if (result.found && result.popularity != null) {
                    val members = byPerson[personId] ?: continue
                    val now = clock.now()
                    for (member in members) {
                        member.popularity = result.popularity
                        member.popularity_refreshed_at = now
                        member.save()
                    }
                    updated++
                } else if (result.errorMessage?.contains("HTTP") == true) {
                    log.warn("API error refreshing person {}: {}", personId, result.errorMessage)
                    break  // Stop on API errors
                } else {
                    // Person not found — mark as refreshed
                    val members = byPerson[personId] ?: continue
                    val now = clock.now()
                    for (member in members) {
                        member.popularity_refreshed_at = now
                        member.save()
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedException()
            } catch (e: Exception) {
                log.warn("Error refreshing person {}: {}", personId, e.message)
            }
        }
        if (updated > 0) {
            log.info("Updated popularity for {}/{} distinct cast members", updated, batch.size)
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
