package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Background daemon that prices eligible media items via the Keepa API.
 *
 * Reads configuration from app_config:
 *   - keepa_enabled: "true" to activate
 *   - keepa_api_key: Keepa API key
 *   - keepa_tokens_per_minute: batch size per cycle (default 20)
 *
 * Eligible items: media_format in (DVD, BLURAY, UHD_BLURAY, HD_DVD),
 * linked to a non-PERSONAL title, and replacement_value_updated_at is
 * null or older than 30 days.
 *
 * ASIN resolution priority per item:
 *   1. override_asin (user override)
 *   2. Linked AmazonOrder.asin
 *   3. UPC lookup via Keepa
 *   4. Title keyword search via Keepa (last resort)
 *
 * Batches ASIN lookups (up to tokens_per_minute per batch), sleeps 62
 * seconds between batches to stay within rate limits.
 *
 * All pricing uses Amazon.com (US marketplace, domain=1) only.
 */
class PriceLookupAgent(
    private val keepaServiceFactory: (String) -> KeepaService = { KeepaHttpService(it) },
    private val clock: Clock = SystemClock
) {
    private val log = LoggerFactory.getLogger(PriceLookupAgent::class.java)
    internal val running = AtomicBoolean(false)
    private var thread: Thread? = null

    // Observable status for UI
    @Volatile var lastBatchTime: LocalDateTime? = null; private set
    @Volatile var lastBatchSize: Int = 0; private set
    @Volatile var lastBatchPriced: Int = 0; private set
    @Volatile var lastEligibleCount: Int = 0; private set
    @Volatile var totalItemsPriced: Int = 0; private set
    @Volatile var totalBatches: Int = 0; private set
    @Volatile var status: String = "idle"; private set

    companion object {
        /** Singleton for UI access. Set from Main.kt on startup. */
        @Volatile var instance: PriceLookupAgent? = null

        private val BATCH_INTERVAL = 62.seconds
        private val DISABLED_CHECK_INTERVAL = 5.minutes
        private val STARTUP_DELAY = 30.seconds
        private const val STALENESS_DAYS = 30L
        private val ELIGIBLE_FORMATS = setOf(
            MediaFormat.DVD.name, MediaFormat.BLURAY.name,
            MediaFormat.UHD_BLURAY.name, MediaFormat.HD_DVD.name
        )
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("PriceLookupAgent started (startup delay {}min)", STARTUP_DELAY.inWholeMinutes)
            status = "waiting (${STARTUP_DELAY.inWholeMinutes}min startup delay)"
            try {
                clock.sleep(STARTUP_DELAY)
            } catch (_: InterruptedException) {
                log.info("PriceLookupAgent interrupted during startup delay")
                status = "stopped"
                return@Thread
            }
            status = "running"
            while (running.get()) {
                try {
                    val config = readConfig()
                    if (config == null) {
                        status = "disabled (check Settings)"
                        clock.sleep(DISABLED_CHECK_INTERVAL)
                        continue
                    }
                    status = "processing batch #${totalBatches + 1}..."
                    processBatch(config)
                    status = if (lastEligibleCount == 0) "idle (all items priced)" else "sleeping ${BATCH_INTERVAL.inWholeSeconds}s (${lastEligibleCount} eligible)"
                    clock.sleep(BATCH_INTERVAL)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("PriceLookupAgent error: {}", e.message, e)
                    status = "error: ${e.message?.take(80)}"
                    try { clock.sleep(BATCH_INTERVAL) } catch (_: InterruptedException) { break }
                }
            }
            status = "stopped"
            log.info("PriceLookupAgent stopped")
        }, "price-lookup-agent").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    internal data class Config(val apiKey: String, val tokensPerMinute: Int, val keepaService: KeepaService)

    private fun readConfig(): Config? {
        val configs = AppConfig.findAll()
        val enabled = configs.firstOrNull { it.config_key == "keepa_enabled" }?.config_val == "true"
        if (!enabled) return null

        val apiKey = configs.firstOrNull { it.config_key == "keepa_api_key" }?.config_val
        if (apiKey.isNullOrBlank()) {
            log.debug("Keepa enabled but no API key configured")
            return null
        }

        val tokensPerMinute = configs.firstOrNull { it.config_key == "keepa_tokens_per_minute" }
            ?.config_val?.toIntOrNull() ?: 20

        return Config(apiKey, tokensPerMinute, keepaServiceFactory(apiKey))
    }

    internal fun processBatch(config: Config) {
        val eligible = findEligibleItems()
        lastEligibleCount = eligible.size
        if (eligible.isEmpty()) {
            log.info("No items eligible for pricing")
            return
        }

        val batchSize = config.tokensPerMinute.coerceIn(1, 100)
        val batch = eligible.take(batchSize)

        log.info("Pricing batch of {} items ({} total eligible)", batch.size, eligible.size)

        // Resolve ASINs for the batch
        val amazonOrders = AmazonOrder.findAll()
        val titleMap = buildTitleMap()

        val asinItems = mutableListOf<Pair<MediaItem, String>>() // items with known ASINs
        val upcItems = mutableListOf<MediaItem>()                // items needing UPC lookup
        val searchItems = mutableListOf<MediaItem>()             // items needing title search

        for (item in batch) {
            val asin = resolveAsin(item, amazonOrders)
            if (asin != null) {
                asinItems.add(item to asin)
            } else if (!item.upc.isNullOrBlank()) {
                upcItems.add(item)
            } else {
                searchItems.add(item)
            }
        }

        log.info("Batch breakdown: {} with ASIN, {} with UPC, {} need title search",
            asinItems.size, upcItems.size, searchItems.size)

        var pricedCount = 0

        // Batch ASIN lookups
        if (asinItems.isNotEmpty()) {
            val asins = asinItems.map { it.second }
            val results = config.keepaService.lookupByAsin(asins)
            for ((idx, result) in results.withIndex()) {
                if (idx < asinItems.size) {
                    val (item, asin) = asinItems[idx]
                    if (processResult(item, result, "ASIN", asin)) pricedCount++
                }
            }
        }

        // UPC lookups (one at a time — Keepa code lookup is single-item)
        for (item in upcItems) {
            val result = config.keepaService.lookupByUpc(item.upc!!)
            if (processResult(item, result, "UPC", item.upc!!)) pricedCount++
        }

        // Title search lookups (last resort)
        for (item in searchItems) {
            val titleName = titleMap[item.id] ?: continue
            val format = formatToSearchTerm(item.media_format)
            val asin = config.keepaService.searchByTitle(titleName, format)
            if (asin != null) {
                val results = config.keepaService.lookupByAsin(listOf(asin))
                val result = results.firstOrNull()
                if (result != null) {
                    if (processResult(item, result, "SEARCH", titleName)) pricedCount++
                } else {
                    log.info("No Keepa ASIN lookup result for item #{} '{}' (ASIN: {})", item.id, item.product_name?.take(40), asin)
                    markChecked(item)
                }
            } else {
                log.info("No Keepa result for title search: '{}' (item #{})", titleName, item.id)
                markChecked(item)
            }
        }

        lastBatchTime = LocalDateTime.now()
        lastBatchSize = batch.size
        lastBatchPriced = pricedCount
        totalItemsPriced += pricedCount
        totalBatches++

        MetricsRegistry.registry.counter("mm_price_lookups_total").increment(batch.size.toDouble())
        log.info("Pricing batch complete: {}/{} priced, {} eligible remaining",
            pricedCount, batch.size, eligible.size - batch.size)
    }

    /** Mark an item as checked so it won't be retried for 30 days. */
    private fun markChecked(item: MediaItem) {
        val fresh = MediaItem.findById(item.id!!) ?: return
        fresh.replacement_value_updated_at = LocalDateTime.now()
        fresh.save()
    }

    /** Process a Keepa result for an item. Returns true if a price was set. */
    private fun processResult(item: MediaItem, result: KeepaProductResult, keyType: String, keyValue: String): Boolean {
        if (!result.found) {
            log.info("No Keepa match for item #{} '{}' via {} '{}'",
                item.id, item.product_name?.take(40), keyType, keyValue)
            markChecked(item)
            return false
        }

        val selectedPrice = PriceSelectionService.selectPrice(result)
        val now = LocalDateTime.now()

        // Store price lookup record
        PriceLookup(
            media_item_id = item.id!!,
            lookup_key_type = keyType,
            lookup_key = keyValue,
            price_new_current = result.priceNewCurrent,
            price_new_avg_30d = result.priceNewAvg30d,
            price_new_avg_90d = result.priceNewAvg90d,
            price_amazon_current = result.priceAmazonCurrent,
            price_used_current = result.priceUsedCurrent,
            offer_count_new = result.offerCountNew,
            offer_count_used = result.offerCountUsed,
            keepa_asin = result.asin,
            selected_price = selectedPrice,
            looked_up_at = now,
            raw_json = result.rawJson
        ).create()

        // Update media item replacement value if we got a price
        if (selectedPrice != null) {
            val fresh = MediaItem.findById(item.id!!) ?: return false
            fresh.replacement_value = selectedPrice
            fresh.replacement_value_updated_at = now
            fresh.save()
            log.info("Priced item #{} '{}': \${} via {} '{}' (ASIN: {})",
                item.id, item.product_name?.take(40), selectedPrice, keyType, keyValue, result.asin)
            return true
        } else {
            log.info("Keepa found item #{} '{}' but no price available (ASIN: {})",
                item.id, item.product_name?.take(40), result.asin)
            // Mark as checked so we don't retry for 30 days
            val fresh = MediaItem.findById(item.id!!) ?: return false
            fresh.replacement_value_updated_at = now
            fresh.save()
            return false
        }
    }

    /**
     * Find media items eligible for pricing, sorted by priority.
     * Eligible: real media format, not personal, stale or unpriced.
     */
    internal fun findEligibleItems(): List<MediaItem> {
        val cutoff = LocalDateTime.now().minusDays(STALENESS_DAYS)

        // Build set of personal-type title IDs to exclude
        val personalTitleIds = Title.findAll()
            .filter { it.media_type == MediaType.PERSONAL.name }
            .mapNotNull { it.id }
            .toSet()

        val personalItemIds = if (personalTitleIds.isNotEmpty()) {
            MediaItemTitle.findAll()
                .filter { it.title_id in personalTitleIds }
                .map { it.media_item_id }
                .toSet()
        } else emptySet()

        return MediaItem.findAll()
            .filter { item ->
                item.media_format in ELIGIBLE_FORMATS &&
                item.id !in personalItemIds &&
                (item.replacement_value_updated_at == null || item.replacement_value_updated_at!! < cutoff)
            }
            .sortedWith(
                // Priority: items with override_asin first, then items with linked orders, then UPC, then rest
                compareByDescending<MediaItem> { it.override_asin != null }
                    .thenByDescending { it.amazon_order_id != null }
                    .thenByDescending { !it.upc.isNullOrBlank() }
                    .thenBy { it.replacement_value_updated_at ?: LocalDateTime.MIN }
            )
    }

    private fun resolveAsin(item: MediaItem, amazonOrders: List<AmazonOrder>): String? {
        // User override
        if (!item.override_asin.isNullOrBlank()) return item.override_asin

        // Linked Amazon order
        val order = amazonOrders.firstOrNull {
            it.linked_media_item_id == item.id && it.asin.isNotBlank()
        }
        if (order != null) return order.asin

        return null
    }

    private fun buildTitleMap(): Map<Long, String> {
        val allLinks = MediaItemTitle.findAll()
        val allTitles = Title.findAll().associateBy { it.id }
        return allLinks.groupBy { it.media_item_id }
            .mapValues { (_, links) ->
                links.mapNotNull { allTitles[it.title_id]?.name }.joinToString(", ")
            }
    }

    private fun formatToSearchTerm(format: String): String = when (format) {
        MediaFormat.DVD.name -> "DVD"
        MediaFormat.BLURAY.name -> "Blu-ray"
        MediaFormat.UHD_BLURAY.name -> "4K UHD Blu-ray"
        MediaFormat.HD_DVD.name -> "HD DVD"
        else -> ""
    }
}
