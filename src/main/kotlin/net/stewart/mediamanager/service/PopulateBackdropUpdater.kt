package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Backfills TMDB backdrop paths for existing enriched titles that have a tmdb_id
 * but no backdrop_path. Calls TmdbService.getDetails() for each, respecting the
 * TMDB rate limit (500ms gap between API calls).
 */
class PopulateBackdropUpdater(
    private val tmdbService: TmdbService = TmdbService(),
    private val clock: Clock = SystemClock
) : SchemaUpdater {

    private val log = LoggerFactory.getLogger(PopulateBackdropUpdater::class.java)

    override val name = "populate_backdrop"
    override val version = 1

    override fun run() {
        val titles = Title.findAll().filter { it.tmdb_id != null && it.backdrop_path == null }
        if (titles.isEmpty()) {
            log.info("No titles need backdrop backfill")
            return
        }

        log.info("Backfilling backdrop_path for {} titles", titles.size)
        var updated = 0
        var failed = 0

        for (title in titles) {
            try {
                clock.sleep(500.milliseconds) // TMDB rate limit

                val result = tmdbService.getDetails(title.tmdbKey()!!)
                if (result.found && result.backdropPath != null) {
                    title.backdrop_path = result.backdropPath
                    title.save()
                    updated++
                    log.info("Backdrop for '{}' (tmdb_id={}): {}", title.name, title.tmdb_id, result.backdropPath)
                } else if (result.apiError) {
                    failed++
                    log.warn("API error fetching backdrop for '{}': {}", title.name, result.errorMessage)
                }
            } catch (e: InterruptedException) {
                log.info("Backdrop backfill interrupted after {} updates, will resume next startup", updated)
                Thread.currentThread().interrupt()
                throw e  // propagate so runner does NOT mark as completed
            } catch (e: Exception) {
                failed++
                log.warn("Error fetching backdrop for title #{}: {}", title.id, e.message)
            }
        }

        log.info("Updated backdrop_path for {} of {} titles ({} failed)", updated, titles.size, failed)
        if (failed > 0) {
            throw RuntimeException("Backdrop backfill incomplete: $failed of ${titles.size} failed, will retry next startup")
        }
    }
}
