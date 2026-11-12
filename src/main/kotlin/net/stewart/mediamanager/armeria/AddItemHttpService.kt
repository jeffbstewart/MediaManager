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
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*
import net.stewart.mediamanager.util.toIsoUtc
import java.time.LocalDateTime

@Blocking
class AddItemHttpService {

    private val gson = Gson()
    private val tmdbService = TmdbService()
    private val openLibrary: OpenLibraryService = OpenLibraryHttpService()

    /** Submit a UPC barcode for lookup. */
    @Post("/api/v2/admin/add-item/scan")
    fun scanUpc(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val upc = (map["upc"] as? String)?.trim() ?: return badRequest("upc required")

        val result = BarcodeScanService.submit(upc)
        return when (result) {
            is BarcodeScanService.SubmitResult.Created ->
                jsonResponse(gson.toJson(mapOf("status" to "created", "scan_id" to result.scanId, "upc" to result.upc)))
            is BarcodeScanService.SubmitResult.Duplicate ->
                jsonResponse(gson.toJson(mapOf("status" to "duplicate", "upc" to result.upc, "title_name" to result.titleName)))
            is BarcodeScanService.SubmitResult.Invalid ->
                jsonResponse(gson.toJson(mapOf("status" to "invalid", "reason" to result.reason)))
        }
    }

    /** Get UPC lookup quota status. */
    @Get("/api/v2/admin/add-item/quota")
    fun quota(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        val s = QuotaTracker.getStatus()
        return jsonResponse(gson.toJson(mapOf("used" to s.used, "limit" to s.limit, "remaining" to s.remaining)))
    }

    /** List recent items (past 30 days) with filter. */
    @Get("/api/v2/admin/add-item/recent")
    fun recent(ctx: ServiceRequestContext, @Param("filter") @Default("ALL") filter: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val cutoff = LocalDateTime.now().minusDays(30)
        val allItems = MediaItem.findAll().filter { it.created_at?.isAfter(cutoff) == true }
        val allLinks = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val allTitles = Title.findAll().associateBy { it.id }
        val photoCounts = OwnershipPhotoService.countByMediaItem()

        // Also include stuck barcode scans (no media item)
        val scans = BarcodeScan.findAll().filter {
            it.media_item_id == null && it.scanned_at?.isAfter(cutoff) == true
        }

        val rows = mutableListOf<Map<String, Any?>>()

        for (item in allItems) {
            val titleId = allLinks[item.id]?.firstOrNull()?.title_id
            val title = titleId?.let { allTitles[it] }
            val displayName = title?.name ?: item.product_name ?: item.upc ?: "(unknown)"
            val hasPurchase = item.purchase_price != null || item.purchase_place != null || item.purchase_date != null

            rows.add(mapOf(
                "type" to "item", "media_item_id" to item.id, "title_id" to titleId,
                "display_name" to displayName, "upc" to item.upc,
                "format" to item.media_format,
                "enrichment_status" to (title?.enrichment_status ?: "PENDING"),
                "poster_url" to title?.posterUrl(PosterSize.THUMBNAIL),
                "has_purchase" to hasPurchase,
                "photo_count" to (photoCounts[item.id] ?: 0),
                "entry_source" to item.entry_source,
                "created_at" to toIsoUtc(item.created_at)
            ))
        }

        for (scan in scans) {
            rows.add(mapOf(
                "type" to "scan", "scan_id" to scan.id,
                "display_name" to (scan.upc), "upc" to scan.upc,
                "format" to null, "enrichment_status" to scan.lookup_status,
                "poster_url" to null, "has_purchase" to false, "photo_count" to 0,
                "entry_source" to EntrySource.UPC_SCAN.name, "created_at" to toIsoUtc(scan.scanned_at)
            ))
        }

        // Apply filter
        val filtered = when (filter) {
            "NEEDS_ATTENTION" -> rows.filter { r ->
                val es = r["enrichment_status"] as? String
                es in setOf("NOT_LOOKED_UP", "NOT_FOUND", "FAILED", "ABANDONED") ||
                    r["has_purchase"] == false || r["photo_count"] == 0
            }
            "UPC_NOT_FOUND" -> rows.filter { (it["enrichment_status"] as? String) in setOf("NOT_LOOKED_UP", "NOT_FOUND") }
            "NEEDS_ENRICHMENT" -> rows.filter { (it["enrichment_status"] as? String) in setOf("PENDING", "REASSIGNMENT_REQUESTED", "FAILED", "SKIPPED", "ABANDONED") }
            "NEEDS_PURCHASE" -> rows.filter { it["has_purchase"] == false && it["type"] == "item" }
            "NEEDS_PHOTOS" -> rows.filter { it["photo_count"] == 0 && it["type"] == "item" }
            else -> rows
        }

        return jsonResponse(gson.toJson(mapOf("items" to filtered.sortedByDescending { it["created_at"] as? String }, "total" to filtered.size)))
    }

    /** Search TMDB for adding titles manually. */
    @Get("/api/v2/admin/add-item/search-tmdb")
    fun searchTmdb(ctx: ServiceRequestContext, @Param("q") query: String, @Param("type") @Default("MOVIE") type: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val results = if (type.uppercase() == "TV") tmdbService.searchTvMultiple(query.trim(), 10) else tmdbService.searchMovieMultiple(query.trim(), 10)
        val items = results.map { r ->
            mapOf("tmdb_id" to r.tmdbId, "title" to r.title, "media_type" to r.mediaType,
                "release_year" to r.releaseYear, "poster_path" to r.posterPath, "overview" to r.overview?.take(120))
        }
        return jsonResponse(gson.toJson(mapOf("results" to items)))
    }

    /** Add a title from TMDB search. */
    @Post("/api/v2/admin/add-item/add-from-tmdb")
    fun addFromTmdb(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val tmdbId = (map["tmdb_id"] as? Number)?.toInt() ?: return badRequest("tmdb_id required")
        val mediaType = map["media_type"] as? String ?: return badRequest("media_type required")
        val format = map["format"] as? String ?: "BLURAY"
        val seasonsText = map["seasons"] as? String

        val mmType = when (mediaType.uppercase()) { "TV" -> net.stewart.mediamanager.entity.MediaType.TV; else -> net.stewart.mediamanager.entity.MediaType.MOVIE }
        val tmdbKey = TmdbId(tmdbId, mmType)
        val details = try { tmdbService.getDetails(tmdbKey) } catch (_: Exception) { null }

        val now = LocalDateTime.now()
        var title = Title.findAll().firstOrNull { it.tmdbKey() == tmdbKey }
        if (title == null) {
            title = Title(name = details?.title ?: "Unknown", media_type = tmdbKey.typeString, tmdb_id = tmdbKey.id,
                release_year = details?.releaseYear, description = details?.overview, poster_path = details?.posterPath,
                enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name, created_at = now, updated_at = now)
            title.save()
            SearchIndexService.onTitleChanged(title.id!!)
        }

        val item = MediaItem(media_format = format, entry_source = EntrySource.MANUAL.name, created_at = now, updated_at = now)
        item.save()

        val join = MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!, disc_number = 1, seasons = seasonsText)
        join.save()

        WishListService.syncPhysicalOwnership(title.id!!)
        WishListService.fulfillMediaWishes(tmdbKey)

        return jsonResponse(gson.toJson(mapOf("ok" to true, "title_name" to title.name, "media_item_id" to item.id)))
    }

    /**
     * Add a physical book by ISBN. Resolves via Open Library and calls
     * [BookIngestionService.ingest] (no file_path — this is a physical
     * edition being catalogued, not a NAS file).
     *
     * Used by the "Search Books" tab after the admin picks a work from
     * OL's search results.
     */
    @Post("/api/v2/admin/add-item/add-from-isbn")
    fun addFromIsbn(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val rawIsbn = (map["isbn"] as? String)?.trim()?.replace("-", "")?.replace(" ", "")
            ?: return badRequest("isbn required")

        val lookup = openLibrary.lookupByIsbn(rawIsbn)
        if (lookup !is OpenLibraryResult.Success) {
            val reason = when (lookup) {
                is OpenLibraryResult.NotFound -> "Open Library has no record of that ISBN"
                is OpenLibraryResult.Error -> "Open Library error: ${lookup.message}"
                is OpenLibraryResult.Success -> "Unreachable"
            }
            return jsonResponse(gson.toJson(mapOf("ok" to false, "error" to reason)))
        }

        val result = BookIngestionService.ingest(rawIsbn, lookup.book)
        return jsonResponse(gson.toJson(mapOf(
            "ok" to true,
            "title_name" to result.title.name,
            "title_id" to result.title.id,
            "media_item_id" to result.mediaItem.id,
            "reused" to result.titleReused
        )))
    }

    /** Delete a media item and cascade to all dependents (undo an add). */
    @Delete("/api/v2/admin/add-item/item/{mediaItemId}")
    fun deleteItem(ctx: ServiceRequestContext, @Param("mediaItemId") mediaItemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        MediaItemDeleteService.delete(mediaItemId)
        return jsonResponse("""{"ok":true}""")
    }

    /** Delete a stuck barcode scan. */
    @Delete("/api/v2/admin/add-item/scan/{scanId}")
    fun deleteScan(ctx: ServiceRequestContext, @Param("scanId") scanId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val scan = BarcodeScan.findById(scanId)
        if (scan != null) {
            OwnershipPhotoService.findByUpc(scan.upc).forEach { OwnershipPhotoService.delete(it.id!!) }
            scan.delete()
        }
        return jsonResponse("""{"ok":true}""")
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON_UTF_8).contentLength(bytes.size.toLong()).build(), HttpData.wrap(bytes))
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.BAD_REQUEST).contentType(MediaType.JSON_UTF_8).contentLength(bytes.size.toLong()).build(), HttpData.wrap(bytes))
    }
}
