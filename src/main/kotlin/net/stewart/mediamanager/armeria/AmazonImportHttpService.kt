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
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.AmazonImportService
import java.io.ByteArrayInputStream
import java.time.format.DateTimeFormatter

@Blocking
class AmazonImportHttpService {

    private val gson = Gson()
    private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** List imported orders with filters and search. */
    @Get("/api/v2/admin/amazon-orders")
    fun list(
        ctx: ServiceRequestContext,
        @Param("search") @Default("") search: String,
        @Param("media_only") @Default("false") mediaOnly: Boolean,
        @Param("unlinked_only") @Default("false") unlinkedOnly: Boolean,
        @Param("hide_cancelled") @Default("true") hideCancelled: Boolean,
        @Param("page") @Default("0") page: Int,
        @Param("size") @Default("50") size: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val all = AmazonImportService.searchOrders(user.id!!, search, mediaOnly, unlinkedOnly, hideCancelled, limit = 10000)
        val mediaItems = MediaItem.findAll().associateBy { it.id }
        val titles = Title.findAll().associateBy { it.id }
        val links = MediaItemTitle.findAll().groupBy { it.media_item_id }

        val total = all.size
        val paged = all.drop(page * size).take(size)

        val rows = paged.map { o ->
            val linkedItem = o.linked_media_item_id?.let { mediaItems[it] }
            val linkedTitle = if (linkedItem != null) {
                links[linkedItem.id]?.firstOrNull()?.let { titles[it.title_id]?.name }
                    ?: linkedItem.product_name
            } else null

            mapOf(
                "id" to o.id,
                "order_id" to o.order_id,
                "asin" to o.asin,
                "product_name" to o.product_name,
                "order_date" to o.order_date?.toLocalDate()?.format(dtf),
                "unit_price" to o.unit_price?.toDouble(),
                "product_condition" to o.product_condition,
                "order_status" to o.order_status,
                "linked_title" to linkedTitle,
                "linked_media_item_id" to o.linked_media_item_id
            )
        }

        val (statTotal, _, statLinked) = AmazonImportService.countOrders(user.id!!)
        val statMedia = all.count { AmazonImportService.isLikelyMedia(it.product_name) }

        return jsonResponse(gson.toJson(mapOf(
            "rows" to rows, "total" to total,
            "stats" to mapOf("total" to statTotal, "media" to statMedia, "linked" to statLinked)
        )))
    }

    /** Upload CSV/ZIP file. Body is raw file bytes. */
    @Post("/api/v2/admin/amazon-orders/upload")
    fun upload(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val agg = ctx.request().aggregate().join()
        val bytes = agg.content().array()
        val contentType = agg.contentType()?.toString() ?: ""
        val fileName = ctx.request().headers().get("X-Filename") ?: "upload.csv"

        val rows = if (fileName.endsWith(".zip") || contentType.contains("zip")) {
            AmazonImportService.parseZip(ByteArrayInputStream(bytes))
        } else {
            AmazonImportService.parseCsv(ByteArrayInputStream(bytes))
        }

        val result = AmazonImportService.importRows(user.id!!, rows)

        return jsonResponse(gson.toJson(mapOf(
            "ok" to true,
            "inserted" to result.inserted,
            "skipped" to result.skipped,
            "total_parsed" to rows.size
        )))
    }

    /** Link an Amazon order to a media item. */
    @Post("/api/v2/admin/amazon-orders/{orderId}/link/{itemId}")
    fun link(ctx: ServiceRequestContext, @Param("orderId") orderId: Long, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        AmazonImportService.linkToMediaItem(orderId, itemId)
        return jsonResponse("""{"ok":true}""")
    }

    /** Unlink an Amazon order from its media item. */
    @Post("/api/v2/admin/amazon-orders/{orderId}/unlink")
    fun unlink(ctx: ServiceRequestContext, @Param("orderId") orderId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        AmazonImportService.unlinkFromMediaItem(orderId)
        return jsonResponse("""{"ok":true}""")
    }

    /** Search media items for linking dialog. */
    @Get("/api/v2/admin/amazon-orders/search-items")
    fun searchItems(ctx: ServiceRequestContext, @Param("q") query: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val q = query.trim().lowercase()
        if (q.length < 2) return jsonResponse(gson.toJson(mapOf("items" to emptyList<Any>())))

        val allItems = MediaItem.findAll()
        val allLinks = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val allTitles = Title.findAll().associateBy { it.id }

        val matches = allItems.filter { item ->
            val name = item.product_name?.lowercase() ?: ""
            val titles = allLinks[item.id]?.mapNotNull { allTitles[it.title_id]?.name?.lowercase() } ?: emptyList()
            name.contains(q) || titles.any { it.contains(q) }
        }.take(20)

        val results = matches.map { item ->
            val titleName = allLinks[item.id]?.firstOrNull()?.let { allTitles[it.title_id]?.name }
            mapOf(
                "id" to item.id,
                "display_name" to (titleName ?: item.product_name ?: "(unknown)"),
                "media_format" to item.media_format,
                "upc" to item.upc
            )
        }
        return jsonResponse(gson.toJson(mapOf("items" to results)))
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
