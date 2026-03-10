package net.stewart.mediamanager.service

import com.github.vokorm.asc
import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class UpcLookupAgent(
    private val lookupService: UpcLookupService = UpcItemDbLookupService(),
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(UpcLookupAgent::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastLookupTime = 0L
    private var wasQuotaExhausted = false
    private fun countLookup(result: String) {
        MetricsRegistry.registry.counter("mm_upc_lookups_total", "result", result).increment()
    }
    companion object {
        private const val MIN_LOOKUP_GAP_MS = 11_000L
        private val POLL_INTERVAL = 5.seconds
        private val QUOTA_EXHAUSTED_INTERVAL = 60.seconds
        private val RATE_LIMITED_BACKOFF = 24.hours + 5.minutes
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("UPC Lookup Agent started")
            while (running.get()) {
                var sleepDuration = POLL_INTERVAL
                try {
                    sleepDuration = processNext()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    log.error("Agent error ({}): {}", e.javaClass.simpleName, e.message, e)
                }
                try {
                    clock.sleep(sleepDuration)
                } catch (e: InterruptedException) {
                    break
                }
            }
            log.info("UPC Lookup Agent stopped")
        }, "upc-lookup-agent").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    internal fun processNext(): Duration {
        val quota = QuotaTracker.getStatus()
        if (quota.exhausted) {
            if (!wasQuotaExhausted) {
                log.info("Quota exhausted ({}/{}), pausing lookups until reset", quota.used, quota.limit)
                wasQuotaExhausted = true
            }
            return QUOTA_EXHAUSTED_INTERVAL
        }
        if (wasQuotaExhausted) {
            log.info("Quota available again ({}/{}), resuming lookups", quota.used, quota.limit)
            wasQuotaExhausted = false
        }

        val scan = findOldestPending() ?: return POLL_INTERVAL

        val elapsed = clock.currentTimeMillis() - lastLookupTime
        val wait = MIN_LOOKUP_GAP_MS - elapsed
        if (wait > 0) {
            log.debug("Throttling: waiting {}ms before next lookup", wait)
            clock.sleep(wait.milliseconds)
        }

        log.info("Looking up UPC: {} (scan #{})", scan.upc, scan.id)
        lastLookupTime = clock.currentTimeMillis()
        val result = lookupService.lookup(scan.upc)

        if (result.apiError) {
            log.warn("API error for UPC {}: {}", scan.upc, result.errorMessage)
            countLookup("error")
            // Leave as NOT_LOOKED_UP for retry — do NOT count against quota
            if (result.rateLimited) {
                log.info("Rate limited by API, backing off for {}", RATE_LIMITED_BACKOFF)
                return RATE_LIMITED_BACKOFF
            }
            return POLL_INTERVAL
        }

        if (result.found) {
            countLookup("found")
            handleFound(scan, result)
        } else {
            countLookup("not_found")
            handleNotFound(scan)
        }

        QuotaTracker.increment()

        Broadcaster.broadcast(ScanUpdateEvent(
            scanId = scan.id!!,
            upc = scan.upc,
            newStatus = scan.lookup_status,
            notes = scan.notes
        ))

        return POLL_INTERVAL
    }

    private fun handleFound(scan: BarcodeScan, result: UpcLookupResult) {
        val now = clock.now()

        // Detect multi-pack and season info before creating records
        val multiPack = MultiPackDetector.detect(result.productName)
        val season = SeasonDetector.detect(result.productName)

        // Create MediaItem
        val mediaItem = MediaItem(
            upc = scan.upc,
            media_format = result.mediaFormat ?: MediaFormat.DVD.name,
            title_count = if (multiPack.isMultiPack) multiPack.estimatedTitleCount else 1,
            expansion_status = if (multiPack.isMultiPack)
                ExpansionStatus.NEEDS_EXPANSION.name else ExpansionStatus.SINGLE.name,
            upc_lookup_json = result.rawJson,
            product_name = result.productName,
            created_at = now,
            updated_at = now
        )
        mediaItem.save()

        // Create placeholder Title
        // For multi-packs, skip enrichment (raw name like "Die Hard / Lethal Weapon" won't match well)
        val title = Title(
            name = result.productName ?: "Unknown Title",
            raw_upc_title = result.productName,
            enrichment_status = if (multiPack.isMultiPack)
                EnrichmentStatus.SKIPPED.name else EnrichmentStatus.PENDING.name,
            release_year = result.releaseYear,
            description = result.description,
            created_at = now,
            updated_at = now
        )
        title.save()
        SearchIndexService.onTitleChanged(title.id!!)

        // Create join record
        MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!,
            seasons = if (season.hasSeason) season.seasons else null
        ).save()

        // Update scan
        var notes = "${result.productName ?: "Unknown"} (${result.releaseYear ?: "?"}) - ${result.mediaFormat ?: "DVD"}"
        if (multiPack.isMultiPack) {
            notes += " [MULTI-PACK: ~${multiPack.estimatedTitleCount} titles]"
        }
        if (season.hasSeason) {
            notes += " [SEASON: ${season.seasons}]"
        }
        scan.lookup_status = LookupStatus.FOUND.name
        scan.media_item_id = mediaItem.id
        scan.notes = notes
        scan.save()

        // Link any orphaned ownership photos captured before the media item existed
        if (scan.upc != null) {
            OwnershipPhotoService.resolveOrphans(scan.upc!!, mediaItem.id!!)
        }

        log.info("FOUND: {} -> {}", scan.upc, notes)
    }

    private fun handleNotFound(scan: BarcodeScan) {
        scan.lookup_status = LookupStatus.NOT_FOUND.name
        scan.notes = "UPC not found in database"
        scan.save()

        log.info("NOT_FOUND: {}", scan.upc)
    }

    private fun findOldestPending(): BarcodeScan? {
        return BarcodeScan.findAll(BarcodeScan::scanned_at.asc)
            .firstOrNull { it.lookup_status == LookupStatus.NOT_LOOKED_UP.name }
    }
}
