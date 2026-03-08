package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.ItemCondition
import net.stewart.mediamanager.entity.MediaItem
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

data class AmazonOrderRow(
    val orderId: String,
    val orderDate: LocalDateTime?,
    val shipDate: LocalDateTime?,
    val unitPrice: BigDecimal?,
    val unitPriceTax: BigDecimal?,
    val totalAmount: BigDecimal?,
    val totalDiscounts: BigDecimal?,
    val asin: String,
    val productName: String,
    val quantity: Int,
    val orderStatus: String,
    val productCondition: String?,
    val currency: String?,
    val website: String?
)

data class ImportResult(val inserted: Int, val skipped: Int)

data class AmazonSuggestion(
    val amazonOrder: AmazonOrder,
    val score: Double,
    val cleanedName: String
)

object AmazonImportService {
    private val log = LoggerFactory.getLogger(AmazonImportService::class.java)

    private val MEDIA_KEYWORDS = listOf(
        "blu-ray", "blu ray", "bluray", "dvd", "uhd", "4k", "hd dvd", "hd-dvd",
        "steelbook", "steel book"
    )

    // --- A. Parsing ---

    fun parseZip(input: InputStream): List<AmazonOrderRow> {
        val zip = ZipInputStream(input)
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                return parseCsv(zip)
            }
            entry = zip.nextEntry
        }
        return emptyList()
    }

    fun parseCsv(input: InputStream): List<AmazonOrderRow> {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val rawHeader = reader.readLine() ?: return emptyList()
        // Strip BOM if present
        val headerLine = rawHeader.removePrefix("\uFEFF")
        val headers = parseCsvLine(headerLine)
        val colIndex = headers.withIndex().associate { (i, h) -> h.trim() to i }

        val orderIdCol = colIndex["Order ID"] ?: colIndex["Website Order ID"] ?: return emptyList()
        val orderDateCol = colIndex["Order Date"] ?: return emptyList()
        val shipDateCol = colIndex["Shipment Date"] ?: colIndex["Ship Date"]
        val unitPriceCol = colIndex["Unit Price"] ?: colIndex["Item Total"]
        val unitPriceTaxCol = colIndex["Unit Price Tax"]
        val totalAmountCol = colIndex["Item Total"]?.takeIf { unitPriceCol != it } ?: colIndex["Total Owed"]
        val totalDiscountsCol = colIndex["Total Discounts"]
        val asinCol = colIndex["ASIN/ISBN"] ?: colIndex["ASIN"]
        val titleCol = colIndex["Product Name"] ?: colIndex["Title"] ?: return emptyList()
        val quantityCol = colIndex["Quantity"]
        val statusCol = colIndex["Order Status"]
        val conditionCol = colIndex["Product Condition"] ?: colIndex["Condition"]
        val currencyCol = colIndex["Currency"]
        val websiteCol = colIndex["Website"]

        val rows = mutableListOf<AmazonOrderRow>()
        var line = reader.readLine()
        while (line != null) {
            if (line.isBlank()) {
                line = reader.readLine()
                continue
            }
            val fields = parseCsvLine(line)

            val productName = fields.getOrNull(titleCol)?.trim() ?: ""
            if (productName.isEmpty()) {
                line = reader.readLine()
                continue
            }

            rows.add(AmazonOrderRow(
                orderId = fields.getOrNull(orderIdCol)?.trim() ?: "",
                orderDate = fields.getOrNull(orderDateCol)?.trim()?.let { parseDate(it) },
                shipDate = shipDateCol?.let { fields.getOrNull(it)?.trim()?.let { d -> parseDate(d) } },
                unitPrice = unitPriceCol?.let { fields.getOrNull(it)?.trim()?.let { p -> parsePrice(p) } },
                unitPriceTax = unitPriceTaxCol?.let { fields.getOrNull(it)?.trim()?.let { p -> parsePrice(p) } },
                totalAmount = totalAmountCol?.let { fields.getOrNull(it)?.trim()?.let { p -> parsePrice(p) } },
                totalDiscounts = totalDiscountsCol?.let { fields.getOrNull(it)?.trim()?.let { p -> parsePrice(p) } },
                asin = asinCol?.let { fields.getOrNull(it)?.trim() } ?: "",
                productName = productName,
                quantity = quantityCol?.let { fields.getOrNull(it)?.trim()?.toIntOrNull() } ?: 1,
                orderStatus = fields.getOrNull(statusCol ?: -1)?.trim() ?: "",
                productCondition = conditionCol?.let { fields.getOrNull(it)?.trim()?.ifEmpty { null } },
                currency = currencyCol?.let { fields.getOrNull(it)?.trim() },
                website = websiteCol?.let { fields.getOrNull(it)?.trim() }
            ))

            line = reader.readLine()
        }
        return rows
    }

    internal fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    current.append(c)
                }
            } else {
                when (c) {
                    ',' -> {
                        fields.add(current.toString())
                        current.clear()
                    }
                    '"' -> inQuotes = true
                    else -> current.append(c)
                }
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    internal fun parseDate(s: String): LocalDateTime? {
        if (s.isBlank()) return null
        return try {
            // ISO-8601: 2022-07-24T13:18:08Z or 2022-07-24T13:18:08.000Z
            LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME)
        } catch (_: Exception) {
            try {
                // Fallback: MM/dd/yyyy
                val ld = java.time.LocalDate.parse(s, DateTimeFormatter.ofPattern("MM/dd/yyyy"))
                ld.atStartOfDay()
            } catch (_: Exception) {
                null
            }
        }
    }

    internal fun parsePrice(s: String): BigDecimal? {
        // Handle single-quote notation: '-15.97' → -15.97
        val stripped = s.trim().removeSurrounding("'")
        val cleaned = stripped.replace("$", "").replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        return try {
            BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP)
        } catch (_: Exception) {
            null
        }
    }

    // --- B. Media Filtering ---

    fun isLikelyMedia(productName: String): Boolean {
        val lower = productName.lowercase()
        return MEDIA_KEYWORDS.any { lower.contains(it) }
    }

    // --- C. Import ---

    fun importRows(userId: Long, rows: List<AmazonOrderRow>): ImportResult {
        // Load existing keys for dedup (mutable to track within-batch dupes too)
        val seen = AmazonOrder.findAll()
            .filter { it.user_id == userId }
            .map { it.order_id + "|" + it.asin }
            .toMutableSet()

        var inserted = 0
        var skipped = 0
        for (row in rows) {
            val key = row.orderId + "|" + row.asin
            if (!seen.add(key)) {
                skipped++
                continue
            }

            val order = AmazonOrder(
                user_id = userId,
                order_id = row.orderId,
                asin = row.asin,
                product_name = row.productName,
                product_name_lower = row.productName.lowercase(),
                order_date = row.orderDate,
                ship_date = row.shipDate,
                order_status = row.orderStatus.ifEmpty { null },
                product_condition = row.productCondition,
                unit_price = row.unitPrice,
                unit_price_tax = row.unitPriceTax,
                total_amount = row.totalAmount,
                total_discounts = row.totalDiscounts,
                quantity = row.quantity,
                currency = row.currency,
                website = row.website,
                imported_at = LocalDateTime.now()
            )
            order.save()
            inserted++
        }
        log.info("Amazon import for user {}: {} inserted, {} skipped", userId, inserted, skipped)
        return ImportResult(inserted, skipped)
    }

    // --- D. Search ---

    fun searchOrders(
        userId: Long,
        query: String = "",
        mediaOnly: Boolean = false,
        unlinkedOnly: Boolean = false,
        hideCancelled: Boolean = true,
        limit: Int = 200
    ): List<AmazonOrder> {
        var results = AmazonOrder.findAll().filter { it.user_id == userId }

        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            results = results.filter { it.product_name_lower.contains(q) }
        }
        if (unlinkedOnly) {
            results = results.filter { it.linked_media_item_id == null }
        }
        if (hideCancelled) {
            results = results.filter { it.order_status == null || it.order_status != "Cancelled" }
        }
        if (mediaOnly) {
            results = results.filter { isLikelyMedia(it.product_name) }
        }

        return results
            .sortedByDescending { it.order_date }
            .take(limit)
    }

    fun countOrders(userId: Long): Triple<Int, Int, Int> {
        val all = AmazonOrder.findAll().filter { it.user_id == userId }
        val total = all.size
        val linked = all.count { it.linked_media_item_id != null }
        return Triple(total, 0, linked)
    }

    // --- E. Linking ---

    fun linkToMediaItem(amazonOrderId: Long, mediaItemId: Long) {
        val order = AmazonOrder.findById(amazonOrderId) ?: return
        val item = MediaItem.findById(mediaItemId) ?: return

        // Update amazon_order link
        order.linked_media_item_id = mediaItemId
        order.linked_at = LocalDateTime.now()
        order.save()

        // Copy purchase data to media_item
        item.purchase_place = "Amazon"
        item.purchase_date = order.order_date?.toLocalDate()
        item.purchase_price = order.unit_price
        item.amazon_order_id = order.order_id
        // Map condition: New→MINT, Used/Collectible→GOOD
        if (order.product_condition != null) {
            item.item_condition = when (order.product_condition?.lowercase()) {
                "new" -> ItemCondition.MINT.name
                "used", "collectible" -> ItemCondition.GOOD.name
                else -> item.item_condition
            }
        }
        item.updated_at = LocalDateTime.now()
        item.save()
    }

    // --- F. Suggestions ---

    fun findSuggestionsForMediaItems(
        userId: Long,
        unpricedItems: List<MediaItem>,
        titleMap: Map<Long, String>
    ): Map<Long, AmazonSuggestion> {
        val unlinkedOrders = searchOrders(userId, unlinkedOnly = true, mediaOnly = true, limit = Int.MAX_VALUE)
        return matchSuggestions(unlinkedOrders, unpricedItems, titleMap)
    }

    internal fun matchSuggestions(
        unlinkedOrders: List<AmazonOrder>,
        unpricedItems: List<MediaItem>,
        titleMap: Map<Long, String>
    ): Map<Long, AmazonSuggestion> {
        // Clean each order name once
        data class CleanedOrder(val order: AmazonOrder, val cleanedName: String)
        val cleanedOrders = unlinkedOrders.map { order ->
            CleanedOrder(order, TitleCleanerService.clean(order.product_name).displayName)
        }

        // Score all (item, order) pairs
        data class Candidate(val mediaItemId: Long, val cleanedOrder: CleanedOrder, val score: Double)
        val candidates = mutableListOf<Candidate>()

        for (item in unpricedItems) {
            val itemTitles = titleMap[item.id] ?: continue
            val titleNames = itemTitles.split(", ").filter { it.isNotBlank() }
            if (titleNames.isEmpty()) continue

            for (co in cleanedOrders) {
                val bestScore = titleNames.maxOf { titleName ->
                    FuzzyMatchService.similarity(co.cleanedName, titleName)
                }
                if (bestScore >= 0.50) {
                    candidates.add(Candidate(item.id!!, co, bestScore))
                }
            }
        }

        // Greedy assignment: sort by descending score, each order assigned at most once
        candidates.sortByDescending { it.score }
        val result = mutableMapOf<Long, AmazonSuggestion>()
        val usedOrders = mutableSetOf<Long>()
        val usedItems = mutableSetOf<Long>()

        for (c in candidates) {
            if (c.mediaItemId in usedItems) continue
            if (c.cleanedOrder.order.id!! in usedOrders) continue
            result[c.mediaItemId] = AmazonSuggestion(c.cleanedOrder.order, c.score, c.cleanedOrder.cleanedName)
            usedOrders.add(c.cleanedOrder.order.id!!)
            usedItems.add(c.mediaItemId)
        }

        return result
    }

    fun unlinkFromMediaItem(amazonOrderId: Long) {
        val order = AmazonOrder.findById(amazonOrderId) ?: return
        val mediaItemId = order.linked_media_item_id ?: return

        // Clear the link
        order.linked_media_item_id = null
        order.linked_at = null
        order.save()

        // Clear purchase fields on media_item
        val item = MediaItem.findById(mediaItemId) ?: return
        item.purchase_place = null
        item.purchase_date = null
        item.purchase_price = null
        item.amazon_order_id = null
        item.updated_at = LocalDateTime.now()
        item.save()
    }
}
