package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Backfills cast members for existing enriched titles that have a tmdb_id
 * but no cast_member rows. Calls TmdbService.fetchCredits() for each,
 * respecting the TMDB rate limit (2-second gap between API calls).
 */
class PopulateCastUpdater(
    private val tmdbService: TmdbService = TmdbService(),
    private val clock: Clock = SystemClock
) : SchemaUpdater {

    private val log = LoggerFactory.getLogger(PopulateCastUpdater::class.java)

    override val name = "populate_cast"
    override val version = 2

    override fun run() {
        val allCastTitleIds = CastMember.findAll().map { it.title_id }.toSet()
        val titles = Title.findAll().filter { it.tmdb_id != null && it.id !in allCastTitleIds }
        if (titles.isEmpty()) {
            log.info("No titles need cast backfill")
            return
        }

        log.info("Backfilling cast for {} titles", titles.size)
        var updated = 0
        var failed = 0

        for (title in titles) {
            try {
                clock.sleep(500.milliseconds) // TMDB rate limit

                val castResults = tmdbService.fetchCredits(title.tmdbKey()!!)
                if (castResults.isEmpty()) continue

                for (result in castResults) {
                    CastMember(
                        title_id = title.id!!,
                        tmdb_person_id = result.tmdbPersonId,
                        name = result.name,
                        character_name = result.characterName,
                        profile_path = result.profilePath,
                        cast_order = result.order
                    ).save()
                }
                updated++
                log.info("Cast for '{}' (tmdb_id={}): {} members", title.name, title.tmdb_id, castResults.size)
            } catch (e: InterruptedException) {
                log.info("Cast backfill interrupted after {} updates, will resume next startup", updated)
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                failed++
                log.warn("Error fetching cast for title #{}: {}", title.id, e.message)
            }
        }

        log.info("Populated cast for {} of {} titles ({} failed)", updated, titles.size, failed)
        if (failed > 0) {
            throw RuntimeException("Cast backfill incomplete: $failed of ${titles.size} failed, will retry next startup")
        }
    }
}
