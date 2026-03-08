package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.CastMember
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Backfills TMDB popularity scores for existing cast members that have a tmdb_person_id
 * but no popularity value. Calls TmdbService.fetchPersonDetails() for each unique person,
 * respecting the TMDB rate limit (2-second gap between API calls).
 */
class PopulateActorPopularityUpdater(
    private val tmdbService: TmdbService = TmdbService(),
    private val clock: Clock = SystemClock
) : SchemaUpdater {

    private val log = LoggerFactory.getLogger(PopulateActorPopularityUpdater::class.java)

    override val name = "populate_actor_popularity"
    override val version = 1

    override fun run() {
        val personIds = CastMember.findAll()
            .filter { it.popularity == null }
            .map { it.tmdb_person_id }
            .distinct()

        if (personIds.isEmpty()) {
            log.info("No actors need popularity backfill")
            return
        }

        log.info("Backfilling popularity for {} unique actors", personIds.size)
        var updated = 0
        var failed = 0

        for (personId in personIds) {
            try {
                clock.sleep(500.milliseconds) // TMDB rate limit

                val result = tmdbService.fetchPersonDetails(personId)
                if (result.found && result.popularity != null) {
                    CastMember.findAll()
                        .filter { it.tmdb_person_id == personId && it.popularity == null }
                        .forEach { cm ->
                            cm.popularity = result.popularity
                            cm.save()
                        }
                    updated++
                    log.info("Popularity for '{}' (person_id={}): {}", result.name, personId, result.popularity)
                } else if (!result.found && result.errorMessage != null) {
                    failed++
                    log.warn("API error fetching popularity for person {}: {}", personId, result.errorMessage)
                }
            } catch (e: InterruptedException) {
                log.info("Actor popularity backfill interrupted after {} updates, will resume next startup", updated)
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                failed++
                log.warn("Error fetching popularity for person {}: {}", personId, e.message)
            }
        }

        log.info("Updated popularity for {} of {} actors ({} failed)", updated, personIds.size, failed)
        if (failed > 0) {
            throw RuntimeException("Actor popularity backfill incomplete: $failed of ${personIds.size} failed, will retry next startup")
        }
    }
}
