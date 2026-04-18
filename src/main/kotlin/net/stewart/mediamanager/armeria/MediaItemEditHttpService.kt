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
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.service.AmazonImportService
import net.stewart.mediamanager.service.MissingSeasonService
import net.stewart.mediamanager.service.OwnershipPhotoService
import net.stewart.mediamanager.service.ScanDetailService
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TitleCleanerService
import net.stewart.mediamanager.service.TmdbService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * REST endpoints for the Angular media item edit page.
 * Supports TMDB reassignment, purchase info, seasons, and Amazon order linking.
 */
@Blocking
class MediaItemEditHttpService {

    private val gson = Gson()
    private val tmdbService = TmdbService()
    private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Get full detail for a media item. */
    @Get("/api/v2/admin/media-item/{itemId}")
    fun getItem(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val joins = MediaItemTitle.findAll().filter { it.media_item_id == itemId }
        val titles = joins.mapNotNull { join -> Title.findById(join.title_id)?.let { join to it } }
        val primaryTitle = titles.firstOrNull()?.second
        val displayName = primaryTitle?.name ?: item.product_name ?: "Item #${item.id}"

        val photos = OwnershipPhotoService.findAllForItem(itemId, item.upc)

        val titleList = titles.map { (join, title) ->
            mapOf(
                "join_id" to join.id,
                "title_id" to title.id,
                "title_name" to title.name,
                "media_type" to title.media_type,
                "tmdb_id" to title.tmdb_id,
                "enrichment_status" to title.enrichment_status,
                "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                "seasons" to join.seasons
            )
        }

        // Book-specific read-only fields: authors and series name, when the
        // primary title is a book. Lets the admin verify the scan populated
        // the right metadata.
        val bookAuthors = if (primaryTitle?.media_type == MMMediaType.BOOK.name) {
            TitleAuthor.findAll()
                .filter { it.title_id == primaryTitle.id }
                .sortedBy { it.author_order }
                .mapNotNull { Author.findById(it.author_id)?.name }
        } else emptyList()
        val bookSeries = if (primaryTitle?.media_type == MMMediaType.BOOK.name && primaryTitle.book_series_id != null) {
            val series = BookSeries.findById(primaryTitle.book_series_id!!)
            series?.let { mapOf("name" to it.name, "volume" to primaryTitle.series_number?.toPlainString()) }
        } else null

        val result = mapOf(
            "media_item_id" to item.id,
            "display_name" to displayName,
            "upc" to item.upc,
            "product_name" to item.product_name,
            "media_format" to item.media_format,
            "media_type" to primaryTitle?.media_type,
            "storage_location" to item.storage_location,
            "purchase_place" to item.purchase_place,
            "purchase_date" to item.purchase_date?.format(dtf),
            "purchase_price" to item.purchase_price?.toDouble(),
            "amazon_order_id" to item.amazon_order_id,
            "authors" to bookAuthors,
            "book_series" to bookSeries,
            "titles" to titleList,
            "photo_count" to photos.size,
            "photos" to photos.map { p ->
                mapOf("id" to p.id, "captured_at" to p.captured_at?.toString())
            }
        )

        return jsonResponse(gson.toJson(result))
    }

    /** Change media type for the primary title. */
    @Post("/api/v2/admin/media-item/{itemId}/media-type")
    fun setMediaType(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        val newType = body["media_type"] as? String ?: return badRequest("media_type required")

        val titleId = MediaItemTitle.findAll().firstOrNull { it.media_item_id == itemId }?.title_id
            ?: return badRequest("No title linked")
        val title = Title.findById(titleId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        title.media_type = newType
        title.save()
        SearchIndexService.onTitleChanged(titleId)

        return jsonResponse("""{"ok":true}""")
    }

    /** Update seasons for a media_item_title join. */
    @Post("/api/v2/admin/media-item/{itemId}/seasons")
    fun setSeasons(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        val joinId = (body["join_id"] as? Number)?.toLong() ?: return badRequest("join_id required")
        val seasons = body["seasons"] as? String

        val join = MediaItemTitle.findById(joinId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        if (join.media_item_id != itemId) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val trimmed = seasons?.trim()?.ifEmpty { null }
        if (trimmed != null && MissingSeasonService.parseSeasonText(trimmed) == null) {
            return badRequest("Invalid seasons format. Use numbers like: 2 or 1, 2 or 1-3")
        }

        join.seasons = trimmed
        join.save()
        MissingSeasonService.syncStructuredSeasons(join.id!!, join.title_id, trimmed)

        return jsonResponse("""{"ok":true}""")
    }

    /** Assign a TMDB match to the primary title. */
    @Post("/api/v2/admin/media-item/{itemId}/assign-tmdb")
    fun assignTmdb(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        val tmdbId = (body["tmdb_id"] as? Number)?.toInt() ?: return badRequest("tmdb_id required")
        val mediaType = body["media_type"] as? String ?: return badRequest("media_type required")

        val titleId = MediaItemTitle.findAll().firstOrNull { it.media_item_id == itemId }?.title_id
            ?: return badRequest("No title linked")

        return when (val result = ScanDetailService.assignTmdb(titleId, tmdbId, mediaType)) {
            is ScanDetailService.AssignResult.Assigned ->
                jsonResponse(gson.toJson(mapOf("status" to "assigned", "title_id" to result.titleId)))
            is ScanDetailService.AssignResult.Merged ->
                jsonResponse(gson.toJson(mapOf("status" to "merged", "title_id" to result.intoTitleId, "title_name" to result.mergedTitleName)))
            is ScanDetailService.AssignResult.NotFound ->
                badRequest(result.message)
        }
    }

    /** Search TMDB for reassignment candidates. */
    @Get("/api/v2/admin/media-item/search-tmdb")
    fun searchTmdb(
        ctx: ServiceRequestContext,
        @Param("q") query: String,
        @Param("type") @Default("MOVIE") type: String
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val results = if (type.uppercase() == "TV") tmdbService.searchTvMultiple(query.trim(), 10)
        else tmdbService.searchMovieMultiple(query.trim(), 10)

        val items = results.map { r ->
            mapOf(
                "tmdb_id" to r.tmdbId,
                "title" to r.title,
                "media_type" to r.mediaType,
                "release_year" to r.releaseYear,
                "poster_path" to r.posterPath,
                "overview" to r.overview?.take(120)
            )
        }
        return jsonResponse(gson.toJson(mapOf("results" to items)))
    }

    /** Update purchase info on a media item. */
    @Post("/api/v2/admin/media-item/{itemId}/purchase")
    fun updatePurchase(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)

        if (body.containsKey("purchase_place")) item.purchase_place = (body["purchase_place"] as? String)?.ifBlank { null }
        if (body.containsKey("storage_location")) item.storage_location = (body["storage_location"] as? String)?.ifBlank { null }
        if (body.containsKey("purchase_date")) {
            val dateStr = body["purchase_date"] as? String
            item.purchase_date = dateStr?.let { LocalDate.parse(it) }
        }
        if (body.containsKey("purchase_price")) {
            val price = body["purchase_price"] as? Number
            item.purchase_price = price?.let { BigDecimal.valueOf(it.toDouble()) }
        }
        item.updated_at = LocalDateTime.now()
        item.save()

        return jsonResponse("""{"ok":true}""")
    }

    /** Search Amazon orders for linking. */
    @Get("/api/v2/admin/media-item/{itemId}/amazon-orders")
    fun searchAmazonOrders(
        ctx: ServiceRequestContext,
        @Param("itemId") itemId: Long,
        @Param("q") @Default("") query: String
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val searchQuery = query.ifBlank {
            TitleCleanerService.clean(item.product_name ?: "").displayName
        }

        val orders = AmazonImportService.searchOrders(
            user.id!!, searchQuery, unlinkedOnly = true, limit = 20
        )

        val rows = orders.map { o ->
            mapOf(
                "id" to o.id,
                "product_name" to TitleCleanerService.clean(o.product_name).displayName,
                "order_date" to o.order_date?.toLocalDate()?.format(dtf),
                "unit_price" to o.unit_price?.let { it.setScale(2, RoundingMode.HALF_UP).toDouble() }
            )
        }

        return jsonResponse(gson.toJson(mapOf("orders" to rows, "search_query" to searchQuery)))
    }

    /** Link an Amazon order to this media item. */
    @Post("/api/v2/admin/media-item/{itemId}/link-amazon/{orderId}")
    fun linkAmazonOrder(
        ctx: ServiceRequestContext,
        @Param("itemId") itemId: Long,
        @Param("orderId") orderId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        AmazonImportService.linkToMediaItem(orderId, itemId)

        return jsonResponse("""{"ok":true}""")
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return HttpResponse.of(
            ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON_UTF_8)
                .contentLength(bytes.size.toLong()).build(),
            HttpData.wrap(bytes)
        )
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        return HttpResponse.of(
            ResponseHeaders.builder(HttpStatus.BAD_REQUEST).contentType(MediaType.JSON_UTF_8)
                .contentLength(bytes.size.toLong()).build(),
            HttpData.wrap(bytes)
        )
    }
}
