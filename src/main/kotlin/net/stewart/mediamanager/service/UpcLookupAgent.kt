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
    private val openLibraryService: OpenLibraryService = OpenLibraryHttpService(),
    private val musicBrainzService: MusicBrainzService = MusicBrainzHttpService(),
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(UpcLookupAgent::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastLookupTime = 0L
    private var wasQuotaExhausted = false
    private var lastIdleSummaryMs = 0L
    private fun countLookup(result: String) {
        MetricsRegistry.registry.counter("mm_upc_lookups_total", "result", result).increment()
    }
    companion object {
        private const val MIN_LOOKUP_GAP_MS = 11_000L
        private val POLL_INTERVAL = 5.seconds
        private val QUOTA_EXHAUSTED_INTERVAL = 60.seconds
        private val RATE_LIMITED_BACKOFF = 24.hours + 5.minutes
        /** How often to log the queue snapshot while idle. */
        private const val IDLE_SUMMARY_INTERVAL_MS = 60_000L
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("UPC Lookup Agent started")
            runCatching { logQueueSummary(prefix = "startup") }
                .onFailure { log.warn("startup queue summary failed: {}", it.message) }
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

        val scan = findOldestPending()
        if (scan == null) {
            // Nothing eligible — either the queue is empty or every pending
            // scan is in backoff. Periodically dump a per-status tally so
            // an admin can tell the difference.
            val nowMs = clock.currentTimeMillis()
            if (nowMs - lastIdleSummaryMs >= IDLE_SUMMARY_INTERVAL_MS) {
                logQueueSummary(prefix = "idle")
                lastIdleSummaryMs = nowMs
            }
            return POLL_INTERVAL
        }

        val elapsed = clock.currentTimeMillis() - lastLookupTime
        val wait = MIN_LOOKUP_GAP_MS - elapsed
        if (wait > 0) {
            log.debug("Throttling: waiting {}ms before next lookup", wait)
            clock.sleep(wait.milliseconds)
        }

        val isIsbn = isIsbnBarcode(scan.upc)
        log.info(
            "Looking up UPC: '{}' (scan #{}, len={}, route={})",
            scan.upc, scan.id, scan.upc.length, if (isIsbn) "ISBN/OpenLibrary" else "Music/UPCitemdb"
        )
        lastLookupTime = clock.currentTimeMillis()

        // ISBN barcodes (EAN-13 with 978/979 prefix) route to Open Library
        // instead of UPCitemdb. See docs/BOOKS.md.
        if (isIsbn) {
            return handleIsbn(scan)
        }

        // Non-ISBN barcodes try MusicBrainz first (for CDs), then fall
        // back to UPCitemdb for DVDs/Blu-rays/consumer products. MB returns
        // nothing for non-music barcodes at negligible cost; a confident
        // match is structurally richer than UPCitemdb's flat product name.
        // See docs/MUSIC.md.
        val musicResult = musicBrainzService.lookupByBarcode(scan.upc)
        if (musicResult is MusicBrainzResult.Success) {
            countLookup("found_music")
            handleMusicFound(scan, musicResult.release)
            QuotaTracker.increment()
            Broadcaster.broadcast(ScanUpdateEvent(scan.id!!, scan.upc, scan.lookup_status, scan.notes))
            return POLL_INTERVAL
        }
        if (musicResult is MusicBrainzResult.Error && musicResult.rateLimited) {
            log.info("MusicBrainz rate limited; skipping music branch for this scan")
            // Fall through to UPCitemdb — MB rate-limit shouldn't block DVD lookup.
        }

        val result = lookupService.lookup(scan.upc)

        if (result.apiError) {
            log.warn("API error for UPC {}: {}", scan.upc, result.errorMessage)
            countLookup("error")
            // Leave as NOT_LOOKED_UP for retry — do NOT count against quota.
            if (result.rateLimited) {
                // The 24 h global backoff already keeps us from hammering
                // the API. Don't also penalize this scan with a per-scan
                // cooldown — the failure wasn't UPC-specific.
                log.info("Rate limited by API, backing off for {}", RATE_LIMITED_BACKOFF)
                return RATE_LIMITED_BACKOFF
            }
            // Per-UPC failure: bump the scan's backoff ladder so a
            // persistently-failing barcode doesn't hog the agent every
            // 11 s forever.
            markAttemptFailed(scan)
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
        OwnershipPhotoService.resolveOrphans(scan.upc, mediaItem.id!!)

        log.info("FOUND: {} -> {}", scan.upc, notes)
    }

    private fun handleNotFound(scan: BarcodeScan) {
        scan.lookup_status = LookupStatus.NOT_FOUND.name
        scan.notes = "UPC not found in database"
        scan.save()

        log.info("NOT_FOUND: {}", scan.upc)
    }

    private fun handleMusicFound(scan: BarcodeScan, release: MusicBrainzReleaseLookup) {
        val ingest = MusicIngestionService.ingest(
            upc = scan.upc,
            mediaFormat = MediaFormat.CD,
            lookup = release,
            clock = clock
        )
        val primaryArtist = release.albumArtistCredits.firstOrNull()?.name ?: "Unknown Artist"
        var notes = "$primaryArtist — ${release.title} (${release.releaseYear ?: "?"}) — ${release.tracks.size} tracks"
        if (ingest.titleReused) notes += " [existing release-group]"
        scan.lookup_status = LookupStatus.FOUND.name
        scan.media_item_id = ingest.mediaItem.id
        scan.notes = notes
        scan.save()

        log.info("FOUND ALBUM: {} -> {}", scan.upc, notes)
    }

    private fun findOldestPending(): BarcodeScan? {
        val now = clock.now()
        return BarcodeScan.findAll(BarcodeScan::scanned_at.asc).firstOrNull {
            it.lookup_status == LookupStatus.NOT_LOOKED_UP.name &&
                EnrichmentBackoff.isEligibleForRetry(
                    it.lookup_last_attempt_at,
                    it.lookup_no_progress_streak,
                    now
                )
        }
    }

    /**
     * Stamp the scan so [EnrichmentBackoff] holds it off the queue
     * until the next ladder step. Only reached on paths that leave
     * the scan in NOT_LOOKED_UP — found / not-found transitions move
     * the scan to a terminal status and don't go through here.
     */
    private fun markAttemptFailed(scan: BarcodeScan) {
        scan.lookup_last_attempt_at = clock.now()
        scan.lookup_no_progress_streak = EnrichmentBackoff.nextStreak(
            currentStreak = scan.lookup_no_progress_streak,
            madeProgress = false
        )
        scan.save()
        val cooldown = EnrichmentBackoff.cooldownFor(scan.lookup_no_progress_streak)
        log.info(
            "Scan #{} '{}' backed off: streak={}, next attempt in {}",
            scan.id, scan.upc, scan.lookup_no_progress_streak, cooldown
        )
    }

    /**
     * Print a per-status count of scans + the next-to-process barcode
     * so an admin staring at binnacle can tell at a glance whether the
     * agent is idle by design or stuck. Called both during idle cycles
     * and at startup.
     */
    private fun logQueueSummary(prefix: String) {
        val all = BarcodeScan.findAll()
        val byStatus = all.groupingBy { it.lookup_status }.eachCount()
        val pendingBooks = all.count {
            it.lookup_status == LookupStatus.NOT_LOOKED_UP.name && isIsbnBarcode(it.upc)
        }
        val pendingOther = all.count {
            it.lookup_status == LookupStatus.NOT_LOOKED_UP.name && !isIsbnBarcode(it.upc)
        }
        val next = findOldestPending()
        log.info(
            "UpcLookupAgent [{}] status tally={}, pending_isbn={}, pending_non_isbn={}, " +
            "next='{}' (scan_id={}, isbn={})",
            prefix, byStatus, pendingBooks, pendingOther,
            next?.upc ?: "(none)", next?.id, next?.upc?.let { isIsbnBarcode(it) }
        )
    }

    /**
     * EAN-13 barcodes starting with 978 or 979 are Bookland — ISBNs. Any
     * other 13-digit barcode (or any 12-digit UPC) is routed through the
     * movie/product pipeline.
     */
    internal fun isIsbnBarcode(code: String): Boolean =
        code.length == 13 && (code.startsWith("978") || code.startsWith("979"))

    private fun handleIsbn(scan: BarcodeScan): Duration {
        log.info("ISBN lookup: calling Open Library for '{}' (scan #{})", scan.upc, scan.id)
        val result = openLibraryService.lookupByIsbn(scan.upc)
        log.info("ISBN lookup: Open Library responded with {} for '{}'",
            result::class.simpleName, scan.upc)
        when (result) {
            is OpenLibraryResult.Success -> {
                countLookup("found")
                val ingest = BookIngestionService.ingest(scan.upc, result.book, clock)
                var notes = "${result.book.workTitle} (${result.book.editionYear ?: "?"}) - ${result.book.mediaFormat ?: "Book"}"
                if (ingest.titleReused) notes += " [existing work]"
                scan.lookup_status = LookupStatus.FOUND.name
                scan.media_item_id = ingest.mediaItem.id
                scan.notes = notes
                scan.save()

                QuotaTracker.increment()
                Broadcaster.broadcast(ScanUpdateEvent(
                    scanId = scan.id!!,
                    upc = scan.upc,
                    newStatus = scan.lookup_status,
                    notes = scan.notes
                ))
                log.info("FOUND BOOK: {} -> {}", scan.upc, notes)
            }
            is OpenLibraryResult.NotFound -> {
                countLookup("not_found")
                handleNotFound(scan)
                QuotaTracker.increment()
                Broadcaster.broadcast(ScanUpdateEvent(scan.id!!, scan.upc, scan.lookup_status, scan.notes))
            }
            is OpenLibraryResult.Error -> {
                countLookup("error")
                log.warn("Open Library error for ISBN {}: {}", scan.upc, result.message)
                if (result.rateLimited) {
                    // Global rate limit — already throttled by RATE_LIMITED_BACKOFF,
                    // don't pile a per-scan cooldown on top.
                    return RATE_LIMITED_BACKOFF
                }
                // Leave as NOT_LOOKED_UP for retry, but push this scan into
                // the backoff ladder so a bad ISBN doesn't get re-queried
                // every 11 s forever.
                markAttemptFailed(scan)
                return POLL_INTERVAL
            }
        }
        return POLL_INTERVAL
    }
}
