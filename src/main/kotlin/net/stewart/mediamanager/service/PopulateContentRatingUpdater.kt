package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Backfills content ratings for existing enriched titles that have a tmdb_id
 * but no content_rating value. Calls TmdbService.getDetails() (which uses
 * append_to_response for release_dates/content_ratings) for each title,
 * respecting the TMDB rate limit (500ms gap between API calls).
 */
class PopulateContentRatingUpdater(
    private val tmdbService: TmdbService = TmdbService(),
    private val clock: Clock = SystemClock
) : SchemaUpdater {

    private val log = LoggerFactory.getLogger(PopulateContentRatingUpdater::class.java)

    override val name = "populate_content_rating"
    override val version = 1

    override fun run() {
        val titles = Title.findAll().filter { it.tmdb_id != null && it.content_rating == null }
        if (titles.isEmpty()) {
            log.info("No titles need content rating backfill")
            return
        }

        log.info("Backfilling content ratings for {} titles", titles.size)
        var updated = 0
        var failed = 0
        var noRating = 0

        for (title in titles) {
            try {
                clock.sleep(500.milliseconds) // TMDB rate limit

                val result = tmdbService.getDetails(title.tmdbKey()!!)
                if (result.found && result.contentRating != null) {
                    title.content_rating = result.contentRating
                    title.save()
                    updated++
                    log.info("Content rating for '{}' (tmdb_id={}): {}", title.name, title.tmdb_id, result.contentRating)
                } else if (result.apiError) {
                    failed++
                    log.warn("API error fetching content rating for '{}': {}", title.name, result.errorMessage)
                } else {
                    noRating++
                }
            } catch (e: InterruptedException) {
                log.info("Content rating backfill interrupted after {} updates, will resume next startup", updated)
                Thread.currentThread().interrupt()
                throw e // propagate so runner does NOT mark as completed
            } catch (e: Exception) {
                failed++
                log.warn("Error fetching content rating for title #{}: {}", title.id, e.message)
            }
        }

        log.info("Content rating backfill: {} updated, {} no rating available, {} failed (of {} total)",
            updated, noRating, failed, titles.size)
        if (failed > 0) {
            throw RuntimeException("Content rating backfill incomplete: $failed of ${titles.size} failed, will retry next startup")
        }
    }
}
