package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Backfills TMDB popularity scores for existing enriched titles that have a tmdb_id
 * but no popularity value. Calls TmdbService.getDetails() for each, respecting the
 * TMDB rate limit (2-second gap between API calls).
 */
class PopulatePopularityUpdater(
    private val tmdbService: TmdbService = TmdbService(),
    private val clock: Clock = SystemClock
) : SchemaUpdater {

    private val log = LoggerFactory.getLogger(PopulatePopularityUpdater::class.java)

    override val name = "populate_popularity"
    override val version = 2

    override fun run() {
        val titles = Title.findAll().filter { it.tmdb_id != null && it.popularity == null }
        if (titles.isEmpty()) {
            log.info("No titles need popularity backfill")
            return
        }

        log.info("Backfilling popularity for {} titles", titles.size)
        var updated = 0
        var failed = 0

        for (title in titles) {
            try {
                clock.sleep(500.milliseconds) // TMDB rate limit

                val result = tmdbService.getDetails(title.tmdbKey()!!)
                if (result.found && result.popularity != null) {
                    title.popularity = result.popularity
                    title.save()
                    updated++
                    log.info("Popularity for '{}' (tmdb_id={}): {}", title.name, title.tmdb_id, result.popularity)
                } else if (result.apiError) {
                    failed++
                    log.warn("API error fetching popularity for '{}': {}", title.name, result.errorMessage)
                }
            } catch (e: InterruptedException) {
                log.info("Popularity backfill interrupted after {} updates, will resume next startup", updated)
                Thread.currentThread().interrupt()
                throw e  // propagate so runner does NOT mark as completed
            } catch (e: Exception) {
                failed++
                log.warn("Error fetching popularity for title #{}: {}", title.id, e.message)
            }
        }

        log.info("Updated popularity for {} of {} titles ({} failed)", updated, titles.size, failed)
        if (failed > 0) {
            throw RuntimeException("Popularity backfill incomplete: $failed of ${titles.size} failed, will retry next startup")
        }
    }
}
