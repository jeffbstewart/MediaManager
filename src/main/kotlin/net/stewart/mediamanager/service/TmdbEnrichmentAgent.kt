package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.TmdbId
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Background daemon that enriches Title records with cleaned names and TMDB metadata.
 *
 * Runs as a separate phase from UPC lookup — the UPC agent creates Titles with raw marketing
 * names (enrichment_status=PENDING), and this agent picks them up independently.
 *
 * Two processing flows:
 *
 *   Flow A (PENDING / FAILED): Initial enrichment
 *     1. Clean the raw UPCitemdb title via TitleCleanerService
 *     2. Search TMDB by cleaned title (movie first, then TV if no movie match)
 *     3. Update Title with TMDB data (or cleaned-only data if no match / no API key)
 *     4. Set status to ENRICHED, SKIPPED, or FAILED
 *
 *   Flow B (REASSIGNMENT_REQUESTED): User override
 *     1. Fetch TMDB details by the manually-set tmdb_id (not a search)
 *     2. Repopulate Title fields from TMDB response
 *     3. Set status to ENRICHED
 *
 * Poll priority: PENDING and REASSIGNMENT_REQUESTED are always processed first.
 * FAILED titles are only retried when no higher-priority work exists, and only
 * after their retry_after timestamp has passed.
 *
 * Retry strategy: exponential backoff starting at 30 minutes, doubling each attempt,
 * clamped to 1 week maximum. After 11 consecutive failures, the title is ABANDONED.
 * Backoff progression: 30m → 1h → 2h → 4h → 8h → 16h → 32h → 64h → 128h → 168h → 168h → abandoned
 *
 * Lifecycle: started in Main.kt alongside UpcLookupAgent; stopped via shutdown hook.
 */
class TmdbEnrichmentAgent(
    private val tmdbService: TmdbService = TmdbService(),
    private val titleCleaner: TitleCleanerService = TitleCleanerService,
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(TmdbEnrichmentAgent::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastApiCallTime = 0L
    private var lastDelayedLogTime = 0L

    private fun countEnrichment(result: String) {
        MetricsRegistry.registry.counter("mm_enrichments_total", "result", result).increment()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L       // How often to check for work
        private const val MIN_API_GAP_MS = 500L          // Minimum gap between TMDB API calls
        private const val BASE_BACKOFF_MINUTES = 30L     // Initial retry delay after first failure
        private const val MAX_BACKOFF_HOURS = 168L       // 1 week — maximum retry delay
        private const val MAX_RETRIES = 11               // Consecutive failures before ABANDONED
        private const val DELAYED_LOG_INTERVAL_MS = 60_000L // Log "waiting for retry" once per minute
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("TMDB Enrichment Agent started")
            while (running.get()) {
                try {
                    processNext()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("Enrichment agent error", e)
                }
                try {
                    clock.sleep(POLL_INTERVAL_MS.milliseconds)
                } catch (e: InterruptedException) {
                    break
                }
            }
            log.info("TMDB Enrichment Agent stopped")
        }, "tmdb-enrichment-agent").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    internal fun processNext() {
        // Priority: PENDING and REASSIGNMENT_REQUESTED first
        val title = findHighPriority() ?: findRetryable()
        if (title == null) {
            logDelayedRetryStatus()
            return
        }

        when (title.enrichment_status) {
            EnrichmentStatus.PENDING.name, EnrichmentStatus.FAILED.name -> processInitialEnrichment(title)
            EnrichmentStatus.REASSIGNMENT_REQUESTED.name -> processReassignment(title)
        }
    }

    private fun processInitialEnrichment(title: Title) {
        val rawTitle = title.raw_upc_title ?: title.name
        val customSort = hasCustomSortName(title)
        val cleaned = titleCleaner.clean(rawTitle)
        val now = clock.now()

        log.info("ENRICH #{}: raw=\"{}\"", title.id, rawTitle)
        log.info("ENRICH #{}: cleaned=\"{}\" sort=\"{}\"", title.id, cleaned.displayName, cleaned.sortName)

        // Always update to cleaned name
        title.name = cleaned.displayName.ifEmpty { title.name }
        if (!customSort) {
            title.sort_name = cleaned.sortName.ifEmpty { title.sort_name }
        }
        title.updated_at = now

        // Try TMDB lookup
        val apiKey = System.getProperty("TMDB_API_KEY")
        if (apiKey.isNullOrBlank()) {
            title.enrichment_status = EnrichmentStatus.SKIPPED.name
            title.save()
            logAttempt(title.id!!, true, null)
            countEnrichment("skipped")
            log.info("ENRICH #{}: SKIPPED (no TMDB key) -> \"{}\"", title.id, title.name)
            broadcastUpdate(title)
            return
        }

        throttleApiCall()

        // Search movie first, then TV
        log.info("ENRICH #{}: searching TMDB movies for \"{}\"", title.id, cleaned.displayName)
        var result = tmdbService.searchMovie(cleaned.displayName)
        if (!result.found && !result.apiError) {
            throttleApiCall()
            log.info("ENRICH #{}: no movie match, searching TMDB TV for \"{}\"", title.id, cleaned.displayName)
            result = tmdbService.searchTv(cleaned.displayName)
        }

        if (result.apiError) {
            handleFailure(title, result.errorMessage ?: "Unknown API error")
            // Still save the cleaned name
            title.save()
            countEnrichment("failed")
            log.warn("ENRICH #{}: FAILED ({}), name saved as \"{}\"",
                title.id, result.errorMessage, title.name)
            broadcastUpdate(title)
            return
        }

        if (result.found) {
            applyTmdbResult(title, result, preserveSortName = customSort)

            // Fetch content rating + seasons via getDetails() — search endpoints don't include them
            if (title.tmdbKey() != null && (title.content_rating == null || title.media_type == "TV")) {
                throttleApiCall()
                val details = tmdbService.getDetails(title.tmdbKey()!!)
                if (details.found) {
                    if (details.contentRating != null && title.content_rating == null) {
                        title.content_rating = details.contentRating
                        log.info("ENRICH #{}: content rating = {}", title.id, details.contentRating)
                    }
                    if (title.media_type == "TV" && details.seasons != null && details.seasons.isNotEmpty()) {
                        MissingSeasonService.storeSeasons(title.id!!, details.seasons)
                        log.info("ENRICH #{}: stored {} seasons", title.id, details.seasons.size)
                    }
                }
            }

            // Dedup: if another Title already has this tmdb_id + media_type, merge into it
            val tmdbKey = title.tmdbKey()
            if (tmdbKey != null) {
                val existing = findExistingByTmdbId(tmdbKey, title.id!!)
                if (existing != null) {
                    log.info("ENRICH #{}: DEDUP — tmdb_id={} already exists on title #{}, merging",
                        title.id, tmdbKey, existing.id)
                    mergeInto(title, existing)
                    countEnrichment("deduped")
                    broadcastUpdate(existing)
                    return
                }
            }

            title.enrichment_status = EnrichmentStatus.ENRICHED.name
            title.retry_after = null
            title.save()
            WishListService.syncPhysicalOwnership(title.id!!)
            logAttempt(title.id!!, true, null)
            countEnrichment("enriched")
            if (tmdbKey != null) WishListService.fulfillMediaWishes(tmdbKey)
            TagService.autoAssociateOnEnrichment(title)
            fetchAndStoreCollection(title)
            log.info("ENRICH #{}: ENRICHED tmdb_id={} type={} year={} name=\"{}\" sort=\"{}\" poster={}{}",
                title.id, title.tmdb_id, title.media_type, title.release_year,
                title.name, title.sort_name, title.poster_path,
                if (customSort) " (custom sort key preserved)" else "")
            throttleApiCall()
            fetchAndStoreCast(title)
        } else {
            title.enrichment_status = EnrichmentStatus.SKIPPED.name
            title.retry_after = null
            title.save()
            logAttempt(title.id!!, true, null)
            countEnrichment("skipped")
            log.info("ENRICH #{}: SKIPPED (no TMDB match) -> \"{}\"", title.id, cleaned.displayName)
        }

        broadcastUpdate(title)
    }

    // Flow B: User has manually set a different tmdb_id and requested re-enrichment.
    // Fetch TMDB details by ID (not a search) and repopulate title fields.
    private fun processReassignment(title: Title) {
        val tmdbKey = title.tmdbKey()
        if (tmdbKey == null) {
            log.warn("REASSIGN #{}: tmdb_id is null, skipping", title.id)
            title.enrichment_status = EnrichmentStatus.SKIPPED.name
            title.save()
            return
        }

        val customSort = hasCustomSortName(title)
        log.info("REASSIGN #{}: fetching TMDB {} #{}", title.id, title.media_type, tmdbKey.id)
        throttleApiCall()

        val result = tmdbService.getDetails(tmdbKey)
        val now = clock.now()

        if (result.apiError) {
            handleFailure(title, result.errorMessage ?: "Unknown API error")
            title.save()
            countEnrichment("failed")
            log.warn("REASSIGN #{}: FAILED ({})", title.id, result.errorMessage)
            broadcastUpdate(title)
            return
        }

        if (result.found) {
            // Clear poster cache so the new poster gets fetched
            title.poster_cache_id = null
            applyTmdbResult(title, result, preserveSortName = customSort)
            if (!customSort) {
                val cleaned = titleCleaner.clean(title.name)
                title.sort_name = cleaned.sortName
            }

            // Store seasons for TV titles
            if (title.media_type == "TV" && result.seasons != null && result.seasons.isNotEmpty()) {
                MissingSeasonService.storeSeasons(title.id!!, result.seasons)
                log.info("REASSIGN #{}: stored {} seasons", title.id, result.seasons.size)
            }

            // Dedup: if another Title already has this tmdb_id + media_type, merge into it
            val existing = findExistingByTmdbId(tmdbKey, title.id!!)
            if (existing != null) {
                log.info("REASSIGN #{}: DEDUP — tmdb_id={} already exists on title #{}, merging",
                    title.id, tmdbKey, existing.id)
                mergeInto(title, existing)
                countEnrichment("deduped")
                broadcastUpdate(existing)
                return
            }

            title.enrichment_status = EnrichmentStatus.ENRICHED.name
            title.retry_after = null
            title.updated_at = now
            title.save()
            WishListService.syncPhysicalOwnership(title.id!!)
            logAttempt(title.id!!, true, null)
            countEnrichment("enriched")
            WishListService.fulfillMediaWishes(tmdbKey)
            TagService.autoAssociateOnEnrichment(title)
            fetchAndStoreCollection(title)
            log.info("REASSIGN #{}: ENRICHED tmdb_id={} type={} year={} name=\"{}\" sort=\"{}\" poster={}{}",
                title.id, title.tmdb_id, title.media_type, title.release_year,
                title.name, title.sort_name, title.poster_path,
                if (customSort) " (custom sort key preserved)" else "")
            throttleApiCall()
            fetchAndStoreCast(title)
        } else {
            title.enrichment_status = EnrichmentStatus.SKIPPED.name
            title.retry_after = null
            title.updated_at = now
            title.save()
            logAttempt(title.id!!, true, null)
            countEnrichment("skipped")
            log.warn("REASSIGN #{}: TMDB ID {} not found, skipping", title.id, tmdbKey)
        }

        broadcastUpdate(title)
    }

    private fun hasCustomSortName(title: Title): Boolean {
        val autoGenerated = titleCleaner.clean(title.name).sortName
        return title.sort_name != null && title.sort_name != autoGenerated
    }

    private fun applyTmdbResult(title: Title, result: TmdbSearchResult, preserveSortName: Boolean = false) {
        if (result.title != null) title.name = result.title
        if (result.tmdbId != null) title.tmdb_id = result.tmdbId
        if (result.releaseYear != null) title.release_year = result.releaseYear
        if (result.overview != null) title.description = result.overview
        if (result.posterPath != null) title.poster_path = result.posterPath
        if (result.backdropPath != null) title.backdrop_path = result.backdropPath
        if (result.mediaType != null) title.media_type = result.mediaType
        if (result.popularity != null) title.popularity = result.popularity
        if (result.contentRating != null) title.content_rating = result.contentRating
        title.tmdb_collection_id = result.collectionId
        title.tmdb_collection_name = result.collectionName

        if (!preserveSortName) {
            val cleaned = titleCleaner.clean(title.name)
            title.sort_name = cleaned.sortName
        }
    }

    // Exponential backoff: 30min * 2^(n-1), clamped to 1 week.
    // After MAX_RETRIES consecutive failures, mark as ABANDONED.
    private fun handleFailure(title: Title, errorMessage: String) {
        val now = clock.now()
        logAttempt(title.id!!, false, errorMessage)

        val consecutiveFailures = countConsecutiveFailures(title.id!!)
        if (consecutiveFailures >= MAX_RETRIES) {
            title.enrichment_status = EnrichmentStatus.ABANDONED.name
            title.retry_after = null
            countEnrichment("abandoned")
            log.warn("Abandoned enrichment for title #{} after {} failures", title.id, consecutiveFailures)
        } else {
            val backoffMinutes = BASE_BACKOFF_MINUTES * (1L shl (consecutiveFailures - 1).coerceAtLeast(0))
            val backoffHours = (backoffMinutes / 60).coerceAtMost(MAX_BACKOFF_HOURS)
            val actualMinutes = (backoffHours * 60).coerceAtMost(MAX_BACKOFF_HOURS * 60)
            // Use the smaller of calculated minutes or the clamped hours converted back
            val finalMinutes = backoffMinutes.coerceAtMost(MAX_BACKOFF_HOURS * 60)

            title.enrichment_status = EnrichmentStatus.FAILED.name
            title.retry_after = now.plusMinutes(finalMinutes)
            log.info("Enrichment failed for title #{}, retry after {} minutes ({} consecutive failures)",
                title.id, finalMinutes, consecutiveFailures)
        }
        title.updated_at = now
    }

    // Count consecutive failed attempts (most recent first) until we hit a success or run out.
    // Used to determine the backoff exponent for the next retry.
    private fun countConsecutiveFailures(titleId: Long): Int {
        val attempts = EnrichmentAttempt.findAll()
            .filter { it.title_id == titleId }
            .sortedByDescending { it.attempted_at }

        var count = 0
        for (attempt in attempts) {
            if (attempt.succeeded) break
            count++
        }
        return count
    }

    private fun logAttempt(titleId: Long, succeeded: Boolean, errorMessage: String?) {
        EnrichmentAttempt(
            title_id = titleId,
            attempted_at = clock.now(),
            succeeded = succeeded,
            error_message = errorMessage
        ).save()
    }

    private fun throttleApiCall() {
        val elapsed = clock.currentTimeMillis() - lastApiCallTime
        val wait = MIN_API_GAP_MS - elapsed
        if (wait > 0) {
            clock.sleep(wait.milliseconds)
        }
        lastApiCallTime = clock.currentTimeMillis()
    }

    private fun findExistingByTmdbId(tmdbId: TmdbId, excludeTitleId: Long): Title? {
        return Title.findAll().firstOrNull {
            it.tmdb_id == tmdbId.id && it.id != excludeTitleId && it.media_type == tmdbId.typeString
        }
    }

    private fun relinkMediaItems(fromTitleId: Long, toTitleId: Long) {
        val joins = MediaItemTitle.findAll().filter { it.title_id == fromTitleId }
        val existingMediaItemIds = MediaItemTitle.findAll()
            .filter { it.title_id == toTitleId }
            .map { it.media_item_id }
            .toSet()

        for (join in joins) {
            if (join.media_item_id in existingMediaItemIds) {
                // Already linked — delete the duplicate join
                join.delete()
            } else {
                join.title_id = toTitleId
                join.save()
            }
        }
    }

    /**
     * Merges [fromTitle] into [intoTitle] by relinking all FK children, then deleting [fromTitle].
     * Used when two titles resolve to the same TMDB ID — keeps the existing enriched title and
     * moves the duplicate's MediaItems, Transcodes, Episodes, etc. to it.
     */
    private fun mergeInto(fromTitle: Title, intoTitle: Title) {
        val fromId = fromTitle.id!!
        val toId = intoTitle.id!!

        // 1. Relink media_item_title joins (already dedup-safe)
        relinkMediaItems(fromId, toId)

        // 2. Relink transcodes
        val transcodes = Transcode.findAll().filter { it.title_id == fromId }
        for (t in transcodes) {
            t.title_id = toId
            t.save()
        }
        if (transcodes.isNotEmpty()) {
            log.info("DEDUP #{} -> #{}: relinked {} transcode(s)", fromId, toId, transcodes.size)
        }

        // 3. Relink episodes (skip if target already has same season+episode)
        val fromEpisodes = Episode.findAll().filter { it.title_id == fromId }
        val existingEpisodeKeys = Episode.findAll()
            .filter { it.title_id == toId }
            .map { it.season_number to it.episode_number }
            .toSet()
        var episodesMoved = 0
        for (ep in fromEpisodes) {
            val key = ep.season_number to ep.episode_number
            if (key in existingEpisodeKeys) {
                // Target already has this episode — update any transcodes pointing to the dup episode,
                // then delete it
                val targetEp = Episode.findAll().first {
                    it.title_id == toId && it.season_number == ep.season_number
                        && it.episode_number == ep.episode_number
                }
                Transcode.findAll().filter { it.episode_id == ep.id }.forEach { t ->
                    t.episode_id = targetEp.id
                    t.save()
                }
                DiscoveredFile.findAll().filter { it.matched_episode_id == ep.id }.forEach { df ->
                    df.matched_episode_id = targetEp.id
                    df.save()
                }
                ep.delete()
            } else {
                ep.title_id = toId
                ep.save()
                episodesMoved++
            }
        }
        if (fromEpisodes.isNotEmpty()) {
            log.info("DEDUP #{} -> #{}: moved {} episode(s), {} duplicate(s) merged",
                fromId, toId, episodesMoved, fromEpisodes.size - episodesMoved)
        }

        // 4. Update discovered_files
        val discoveredFiles = DiscoveredFile.findAll().filter { it.matched_title_id == fromId }
        for (df in discoveredFiles) {
            df.matched_title_id = toId
            df.save()
        }
        if (discoveredFiles.isNotEmpty()) {
            log.info("DEDUP #{} -> #{}: relinked {} discovered file(s)", fromId, toId, discoveredFiles.size)
        }

        // 5. Merge title_genre (skip duplicates)
        val existingGenreIds = TitleGenre.findAll()
            .filter { it.title_id == toId }
            .map { it.genre_id }
            .toSet()
        val fromGenres = TitleGenre.findAll().filter { it.title_id == fromId }
        for (tg in fromGenres) {
            if (tg.genre_id in existingGenreIds) {
                tg.delete()
            } else {
                tg.title_id = toId
                tg.save()
            }
        }

        // 6. Delete cast_members for the duplicate (surviving title keeps its own)
        CastMember.findAll()
            .filter { it.title_id == fromId }
            .forEach { it.delete() }

        // 7. Delete enrichment_attempts for the duplicate
        EnrichmentAttempt.findAll()
            .filter { it.title_id == fromId }
            .forEach { it.delete() }

        // Log success on the surviving title
        logAttempt(toId, true, null)

        // 8. Delete the duplicate title
        fromTitle.delete()
        log.info("DEDUP #{} -> #{}: deleted duplicate title, all children relinked", fromId, toId)
    }

    private fun fetchAndStoreCollection(title: Title) {
        val collectionId = title.tmdb_collection_id ?: return

        // If collection already stored, try to assign sort name from cached data
        val existing = CollectionService.findByTmdbId(collectionId)
        if (existing != null) {
            val sortName = CollectionService.collectionSortName(title)
            if (sortName != null) {
                // Found in stored parts — just update sort name
                if (title.sort_name != sortName) {
                    title.sort_name = sortName
                    title.save()
                    log.info("COLLECTION #{}: sort name = \"{}\"", title.id, sortName)
                }
                return
            }
            // Title not found in stored parts — collection may have been updated on TMDB.
            // Fall through to re-fetch.
            log.info("COLLECTION #{}: tmdb_id={} not found in stored parts for collection {}, re-fetching",
                title.id, title.tmdb_id, collectionId)
        }

        // Fetch from TMDB
        throttleApiCall()
        val result = tmdbService.fetchCollection(collectionId)
        if (result.found) {
            CollectionService.storeCollection(result)
            CollectionService.updateSortNamesForCollection(collectionId)
            log.info("COLLECTION #{}: stored \"{}\" with {} parts",
                title.id, result.name, result.parts.size)
        } else {
            log.warn("COLLECTION #{}: failed to fetch collection {}: {}",
                title.id, collectionId, result.errorMessage)
        }
    }

    internal fun fetchAndStoreCast(title: Title) {
        val tmdbKey = title.tmdbKey() ?: return

        try {
            val castResults = tmdbService.fetchCredits(tmdbKey)
            if (castResults.isEmpty()) return

            // Delete existing cast for this title (safe for re-enrichment)
            CastMember.findAll()
                .filter { it.title_id == title.id }
                .forEach { it.delete() }

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
            log.info("CAST #{}: saved {} cast member(s)", title.id, castResults.size)

            // Fetch popularity for each unique person
            fetchAndStorePersonPopularity(title.id!!, castResults)
        } catch (e: Exception) {
            log.warn("CAST #{}: failed to fetch/store cast: {}", title.id, e.message)
        }
    }

    /**
     * Fetches TMDB popularity for each unique person in the cast results.
     * Skips persons that already have popularity from a previous title's CastMember row.
     */
    private fun fetchAndStorePersonPopularity(titleId: Long, castResults: List<TmdbCastResult>) {
        val distinctPersonIds = castResults.map { it.tmdbPersonId }.distinct()

        // Check which persons already have popularity from any existing CastMember row
        val allCastMembers = CastMember.findAll()
        val knownPopularity = allCastMembers
            .filter { it.popularity != null }
            .associate { it.tmdb_person_id to it.popularity!! }

        for (personId in distinctPersonIds) {
            val existing = knownPopularity[personId]
            if (existing != null) {
                // Use cached popularity from another title
                CastMember.findAll()
                    .filter { it.title_id == titleId && it.tmdb_person_id == personId && it.popularity == null }
                    .forEach { cm ->
                        cm.popularity = existing
                        cm.save()
                    }
                continue
            }

            // Fetch from TMDB
            try {
                throttleApiCall()
                val personResult = tmdbService.fetchPersonDetails(personId)
                if (personResult.found && personResult.popularity != null) {
                    CastMember.findAll()
                        .filter { it.title_id == titleId && it.tmdb_person_id == personId }
                        .forEach { cm ->
                            cm.popularity = personResult.popularity
                            cm.save()
                        }
                }
            } catch (e: Exception) {
                log.warn("CAST #{}: failed to fetch popularity for person {}: {}", titleId, personId, e.message)
            }
        }
    }

    private fun broadcastUpdate(title: Title) {
        SearchIndexService.onTitleChanged(title.id!!)
        Broadcaster.broadcastTitleUpdate(TitleUpdateEvent(
            titleId = title.id!!,
            name = title.name,
            enrichmentStatus = title.enrichment_status
        ))
    }

    private fun logDelayedRetryStatus() {
        val now = clock.currentTimeMillis()
        if (now - lastDelayedLogTime < DELAYED_LOG_INTERVAL_MS) return

        val nextRetry = findNextRetryTime() ?: return

        val wait = Duration.between(clock.now(), nextRetry)
        if (wait.isNegative || wait.isZero) return

        val totalSeconds = wait.seconds
        val description = when {
            totalSeconds < 120 -> "%.0f seconds".format(totalSeconds.toDouble())
            totalSeconds < 7200 -> "%.1f minutes".format(totalSeconds / 60.0)
            else -> "%.1f hours".format(totalSeconds / 3600.0)
        }

        val failedCount = Title.findAll().count { it.enrichment_status == EnrichmentStatus.FAILED.name }
        log.info("Next enrichment delayed for retry backoff for {} ({} failed title{} waiting)",
            description, failedCount, if (failedCount == 1) "" else "s")
        lastDelayedLogTime = now
    }

    private fun findNextRetryTime(): LocalDateTime? {
        return Title.findAll()
            .filter { it.enrichment_status == EnrichmentStatus.FAILED.name && it.retry_after != null }
            .minByOrNull { it.retry_after!! }
            ?.retry_after
    }

    private fun findHighPriority(): Title? {
        return Title.findAll()
            .filter {
                it.enrichment_status == EnrichmentStatus.PENDING.name ||
                it.enrichment_status == EnrichmentStatus.REASSIGNMENT_REQUESTED.name
            }
            .minByOrNull { it.created_at ?: LocalDateTime.MAX }
    }

    private fun findRetryable(): Title? {
        val now = clock.now()
        return Title.findAll()
            .filter {
                it.enrichment_status == EnrichmentStatus.FAILED.name &&
                (it.retry_after == null || it.retry_after!! <= now)
            }
            .minByOrNull { it.retry_after ?: LocalDateTime.MIN }
    }
}
