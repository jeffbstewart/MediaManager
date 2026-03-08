package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TvSeason
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Backfills TMDB season data for existing enriched TV titles.
 * For each TV title with a tmdb_id that has no tv_season rows,
 * fetches the detail response and stores the season list.
 */
class PopulateSeasonsUpdater(
    private val tmdbService: TmdbService = TmdbService(),
    private val clock: Clock = SystemClock
) : SchemaUpdater {

    private val log = LoggerFactory.getLogger(PopulateSeasonsUpdater::class.java)

    override val name = "populate_seasons"
    override val version = 1

    override fun run() {
        val existingTitleIds = TvSeason.findAll().map { it.title_id }.toSet()

        val titles = Title.findAll().filter {
            it.tmdb_id != null && it.media_type == "TV" && it.id !in existingTitleIds
        }
        if (titles.isEmpty()) {
            log.info("No TV titles need season backfill")
            return
        }

        log.info("Backfilling TMDB seasons for {} TV titles", titles.size)
        var updated = 0
        var failed = 0

        for (title in titles) {
            try {
                clock.sleep(500.milliseconds) // TMDB rate limit
                val result = tmdbService.getDetails(title.tmdbKey()!!)
                if (result.found && result.seasons != null && result.seasons.isNotEmpty()) {
                    MissingSeasonService.storeSeasons(title.id!!, result.seasons)
                    updated++
                    log.info("Stored {} seasons for '{}' (tmdb_id={})",
                        result.seasons.size, title.name, title.tmdb_id)
                } else if (result.apiError) {
                    failed++
                    log.warn("API error fetching seasons for '{}': {}", title.name, result.errorMessage)
                }
            } catch (e: InterruptedException) {
                log.info("Season backfill interrupted after {} updates, will resume next startup", updated)
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                failed++
                log.warn("Error fetching seasons for title #{}: {}", title.id, e.message)
            }
        }

        log.info("Season backfill: {} updated, {} failed (of {} total)", updated, failed, titles.size)

        // Refresh ownership based on existing transcodes
        MissingSeasonService.refreshOwnership()

        if (failed > 0) {
            throw RuntimeException("Season backfill incomplete: $failed of ${titles.size} failed, will retry next startup")
        }
    }
}
