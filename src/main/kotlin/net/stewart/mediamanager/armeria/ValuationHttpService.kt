package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.KeepaHttpService
import net.stewart.mediamanager.service.PriceLookupAgent
import net.stewart.mediamanager.service.TitleCleanerService
import java.time.LocalDateTime

@Blocking
class ValuationHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/valuations")
    fun list(
        ctx: ServiceRequestContext,
        @Param("search") @Default("") search: String,
        @Param("unpriced_only") @Default("false") unpricedOnly: Boolean,
        @Param("page") @Default("0") page: Int,
        @Param("size") @Default("50") size: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val allItems = MediaItem.findAll()
        val allLinks = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val allTitles = Title.findAll().associateBy { it.id }
        val photoCounts = OwnershipPhoto.findAll().groupBy { it.media_item_id }.mapValues { it.value.size }

        var items = allItems.map { item ->
            val titleNames = allLinks[item.id]?.mapNotNull { allTitles[it.title_id]?.name }?.joinToString(", ") ?: ""
            val displayName = item.product_name?.ifBlank { null } ?: titleNames.ifBlank { "(unknown)" }
            Triple(item, displayName, titleNames)
        }

        val q = search.trim().lowercase()
        if (q.isNotEmpty()) {
            items = items.filter { (_, display, titles) ->
                display.lowercase().contains(q) || titles.lowercase().contains(q)
            }
        }
        if (unpricedOnly) {
            items = items.filter { (item, _, _) -> item.purchase_price == null }
        }

        items = items.sortedBy { it.second.lowercase() }
        val total = items.size
        val paged = items.drop(page * size).take(size)

        val rows = paged.map { (item, display, _) ->
            mapOf(
                "id" to item.id,
                "display_name" to display,
                "media_format" to item.media_format,
                "upc" to item.upc,
                "purchase_place" to item.purchase_place,
                "purchase_date" to item.purchase_date?.toString(),
                "purchase_price" to item.purchase_price?.toDouble(),
                "replacement_value" to item.replacement_value?.toDouble(),
                "override_asin" to item.override_asin,
                "photo_count" to (photoCounts[item.id] ?: 0)
            )
        }

        val totalPurchase = allItems.mapNotNull { it.purchase_price?.toDouble() }.sum()
        val totalReplacement = allItems.mapNotNull { it.replacement_value?.toDouble() }.sum()

        return jsonResponse(gson.toJson(mapOf(
            "rows" to rows, "total" to total,
            "summary" to mapOf(
                "total_items" to allItems.size,
                "total_purchase" to totalPurchase,
                "total_replacement" to totalReplacement
            )
        )))
    }

    @Post("/api/v2/admin/valuations/{itemId}")
    fun update(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)

        if (map.containsKey("purchase_place")) item.purchase_place = (map["purchase_place"] as? String)?.ifBlank { null }
        if (map.containsKey("purchase_date")) item.purchase_date = try { java.time.LocalDate.parse(map["purchase_date"] as String) } catch (_: Exception) { null }
        if (map.containsKey("purchase_price")) item.purchase_price = (map["purchase_price"] as? Number)?.toDouble()?.let { java.math.BigDecimal.valueOf(it) }
        if (map.containsKey("replacement_value")) item.replacement_value = (map["replacement_value"] as? Number)?.toDouble()?.let { java.math.BigDecimal.valueOf(it) }
        if (map.containsKey("override_asin")) item.override_asin = (map["override_asin"] as? String)?.ifBlank { null }
        item.updated_at = LocalDateTime.now()
        item.save()

        return jsonResponse("""{"ok":true}""")
    }

    /** Search Keepa for replacement value candidates. Blocks ~4s for API response. */
    @Get("/api/v2/admin/valuations/{itemId}/keepa-search")
    fun keepaSearch(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val apiKey = AppConfig.findAll().firstOrNull { it.config_key == "keepa_api_key" }?.config_val
        if (apiKey.isNullOrBlank()) {
            return jsonResponse(gson.toJson(mapOf("error" to "Keepa API key not configured")))
        }

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val titleName = MediaItemTitle.findAll().firstOrNull { it.media_item_id == itemId }
            ?.let { Title.findById(it.title_id)?.name }
        val searchTerm = TitleCleanerService.clean(titleName ?: item.product_name ?: "").displayName

        val keepa = KeepaHttpService(apiKey)
        val candidates = try {
            keepa.searchCandidates(searchTerm, item.media_format)
        } catch (e: Exception) {
            return jsonResponse(gson.toJson(mapOf("error" to (e.message ?: "Keepa search failed"))))
        }

        val results = candidates.filter { it.found }.map { r ->
            mapOf(
                "asin" to r.asin,
                "title" to r.title,
                "price_new" to r.priceNewCurrent?.toDouble(),
                "price_amazon" to r.priceAmazonCurrent?.toDouble(),
                "price_used" to r.priceUsedCurrent?.toDouble(),
                "offers_new" to r.offerCountNew,
                "offers_used" to r.offerCountUsed
            )
        }
        return jsonResponse(gson.toJson(mapOf("results" to results)))
    }

    /** Use a Keepa result to set replacement value and ASIN. */
    @Post("/api/v2/admin/valuations/{itemId}/keepa-apply")
    fun keepaApply(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val asin = map["asin"] as? String
        val price = (map["price"] as? Number)?.toDouble()

        if (asin != null) item.override_asin = asin
        if (price != null) item.replacement_value = java.math.BigDecimal.valueOf(price)
        item.replacement_value_updated_at = LocalDateTime.now()
        item.updated_at = LocalDateTime.now()
        item.save()
        return jsonResponse("""{"ok":true}""")
    }

    /** Pricing agent status. */
    @Get("/api/v2/admin/valuations/agent-status")
    fun agentStatus(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val agent = PriceLookupAgent.instance
        val result = if (agent != null) {
            mapOf(
                "status" to agent.status,
                "last_batch_time" to agent.lastBatchTime?.toString(),
                "last_batch_size" to agent.lastBatchSize,
                "last_batch_priced" to agent.lastBatchPriced,
                "eligible_remaining" to agent.lastEligibleCount,
                "session_total_priced" to agent.totalItemsPriced,
                "session_total_batches" to agent.totalBatches
            )
        } else {
            mapOf("status" to "not configured")
        }
        return jsonResponse(gson.toJson(result))
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
