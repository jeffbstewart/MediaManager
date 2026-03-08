package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollection
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Backfills TMDB collection data for enriched movie titles.
 *
 * Phase 1 (v1): Fetch tmdb_collection_id/name from TMDB for movies missing it.
 * Phase 2 (v1): Auto-associate enriched titles with genre/collection tags.
 * Phase 3 (v2): Fetch full collection structure (parts + order) and generate
 *               collection-aware sort names for all collection members.
 */
class PopulateCollectionUpdater(
    private val tmdbService: TmdbService = TmdbService(),
    private val clock: Clock = SystemClock
) : SchemaUpdater {

    private val log = LoggerFactory.getLogger(PopulateCollectionUpdater::class.java)

    override val name = "populate_collections"
    override val version = 2

    override fun run() {
        // Phase 1: Backfill collection data from TMDB for movies
        val movies = Title.findAll().filter {
            it.tmdb_id != null && it.media_type == "MOVIE" && it.tmdb_collection_id == null
        }

        if (movies.isNotEmpty()) {
            log.info("Backfilling collection data for {} movies", movies.size)
            var updated = 0
            var failed = 0

            for (title in movies) {
                try {
                    clock.sleep(500.milliseconds)
                    val result = tmdbService.getDetails(title.tmdbKey()!!)
                    if (result.found && result.collectionId != null) {
                        title.tmdb_collection_id = result.collectionId
                        title.tmdb_collection_name = result.collectionName
                        title.save()
                        updated++
                        log.info("Collection for '{}': {} ({})",
                            title.name, result.collectionName, result.collectionId)
                    } else if (result.apiError) {
                        failed++
                        log.warn("API error fetching collection for '{}': {}", title.name, result.errorMessage)
                    }
                } catch (e: InterruptedException) {
                    log.info("Collection backfill interrupted after {} updates", updated)
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    failed++
                    log.warn("Error fetching collection for title #{}: {}", title.id, e.message)
                }
            }

            log.info("Updated collection data for {} of {} movies ({} failed)", updated, movies.size, failed)
            if (failed > 0) {
                throw RuntimeException("Collection backfill incomplete: $failed of ${movies.size} failed, will retry next startup")
            }
        }

        // Phase 2: Auto-associate all enriched titles with existing genre/collection tags
        val enrichedTitles = Title.findAll().filter { it.enrichment_status == "ENRICHED" }
        log.info("Auto-associating tags for {} enriched titles", enrichedTitles.size)
        var associations = 0
        for (title in enrichedTitles) {
            try {
                TagService.autoAssociateOnEnrichment(title)
                associations++
            } catch (e: Exception) {
                log.warn("Error auto-associating tags for title #{}: {}", title.id, e.message)
            }
        }
        log.info("Auto-association complete for {} titles", associations)

        // Phase 3: Fetch full collection parts and generate sort names
        val collectionIds = Title.findAll()
            .mapNotNull { it.tmdb_collection_id }
            .distinct()

        val alreadyStored = TmdbCollection.findAll().map { it.tmdb_collection_id }.toSet()
        val toFetch = collectionIds.filter { it !in alreadyStored }

        if (toFetch.isNotEmpty()) {
            log.info("Fetching {} collection structures ({} already stored)", toFetch.size, alreadyStored.size)
            var fetched = 0
            var fetchFailed = 0

            for (colId in toFetch) {
                try {
                    clock.sleep(500.milliseconds)
                    val result = tmdbService.fetchCollection(colId)
                    if (result.found) {
                        CollectionService.storeCollection(result)
                        fetched++
                    } else {
                        fetchFailed++
                        log.warn("Failed to fetch collection {}: {}", colId, result.errorMessage)
                    }
                } catch (e: InterruptedException) {
                    log.info("Collection fetch interrupted after {} fetches", fetched)
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    fetchFailed++
                    log.warn("Error fetching collection {}: {}", colId, e.message)
                }
            }

            log.info("Fetched {} collections ({} failed)", fetched, fetchFailed)
            if (fetchFailed > 0) {
                throw RuntimeException("Collection fetch incomplete: $fetchFailed failed, will retry next startup")
            }
        }

        // Update sort names for all collections (including previously stored ones)
        for (colId in collectionIds) {
            CollectionService.updateSortNamesForCollection(colId)
        }
        log.info("Updated sort names for {} collections", collectionIds.size)
    }
}
