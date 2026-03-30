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
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TmdbService
import net.stewart.mediamanager.service.WishListService
import java.time.LocalDateTime

@Blocking
class ExpandHttpService {

    private val gson = Gson()
    private val tmdbService = TmdbService()

    /** List multi-packs awaiting expansion. */
    @Get("/api/v2/admin/expand")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val items = MediaItem.findAll().filter { it.expansion_status == ExpansionStatus.NEEDS_EXPANSION.name }
        val allLinks = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val allTitles = Title.findAll().associateBy { it.id }

        val rows = items.sortedBy { it.product_name?.lowercase() ?: "" }.map { item ->
            val linkedTitles = allLinks[item.id]?.map { join ->
                val t = allTitles[join.title_id]
                mapOf("title_id" to join.title_id, "name" to (t?.name ?: "Unknown"),
                    "release_year" to t?.release_year, "poster_url" to t?.posterUrl(PosterSize.THUMBNAIL),
                    "disc_number" to join.disc_number)
            } ?: emptyList()

            mapOf("id" to item.id, "upc" to item.upc, "product_name" to item.product_name,
                "media_format" to item.media_format, "title_count" to item.title_count,
                "linked_titles" to linkedTitles)
        }
        return jsonResponse(gson.toJson(mapOf("items" to rows, "total" to rows.size)))
    }

    /** Get detail for a single expansion item (for the dialog). */
    @Get("/api/v2/admin/expand/{itemId}")
    fun detail(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val joins = MediaItemTitle.findAll().filter { it.media_item_id == itemId }
        val linkedTitles = joins.sortedBy { it.disc_number }.map { join ->
            val t = Title.findById(join.title_id)
            mapOf("title_id" to join.title_id, "name" to (t?.name ?: "Unknown"),
                "release_year" to t?.release_year, "poster_url" to t?.posterUrl(PosterSize.THUMBNAIL),
                "disc_number" to join.disc_number)
        }

        return jsonResponse(gson.toJson(mapOf("id" to item.id, "upc" to item.upc,
            "product_name" to item.product_name, "media_format" to item.media_format,
            "title_count" to item.title_count, "linked_titles" to linkedTitles)))
    }

    /** Search TMDB and add a title to the expansion. */
    @Post("/api/v2/admin/expand/{itemId}/add-title")
    fun addTitle(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val tmdbId = (map["tmdb_id"] as? Number)?.toInt() ?: return badRequest("tmdb_id required")
        val mediaType = map["media_type"] as? String ?: return badRequest("media_type required")

        val existingCount = MediaItemTitle.findAll().count { it.media_item_id == itemId }
        if (existingCount >= 50) return jsonResponse("""{"ok":false,"error":"Maximum 50 titles per multi-pack"}""")

        val mmType = when (mediaType.uppercase()) { "TV" -> net.stewart.mediamanager.entity.MediaType.TV; else -> net.stewart.mediamanager.entity.MediaType.MOVIE }
        val tmdbKey = TmdbId(tmdbId, mmType)
        val details = try { tmdbService.getDetails(tmdbKey) } catch (_: Exception) { null }

        val now = LocalDateTime.now()
        var title = Title.findAll().firstOrNull { it.tmdbKey() == tmdbKey }
        val alreadyExisted = title != null
        if (title == null) {
            title = Title(name = details?.title ?: "Unknown", media_type = tmdbKey.typeString, tmdb_id = tmdbKey.id,
                release_year = details?.releaseYear, description = details?.overview, poster_path = details?.posterPath,
                enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name, created_at = now, updated_at = now)
            title.save()
            SearchIndexService.onTitleChanged(title.id!!)
        }

        val existingJoins = MediaItemTitle.findAll().filter { it.media_item_id == itemId }
        val nextDisc = (existingJoins.maxOfOrNull { it.disc_number } ?: 0) + 1
        MediaItemTitle(media_item_id = itemId, title_id = title.id!!, disc_number = nextDisc).save()

        WishListService.syncPhysicalOwnership(title.id!!)
        WishListService.fulfillMediaWishes(tmdbKey)

        return jsonResponse(gson.toJson(mapOf("ok" to true, "title_id" to title.id,
            "title_name" to title.name, "disc_number" to nextDisc, "already_existed" to alreadyExisted)))
    }

    /** Remove a title from the expansion. */
    @Delete("/api/v2/admin/expand/{itemId}/title/{titleId}")
    fun removeTitle(ctx: ServiceRequestContext, @Param("itemId") itemId: Long, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val join = MediaItemTitle.findAll().firstOrNull { it.media_item_id == itemId && it.title_id == titleId }
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        join.delete()
        // Resequence disc numbers
        MediaItemTitle.findAll().filter { it.media_item_id == itemId }.sortedBy { it.disc_number }.forEachIndexed { i, j -> j.disc_number = i + 1; j.save() }
        return jsonResponse("""{"ok":true}""")
    }

    /** Mark expansion as complete. */
    @Post("/api/v2/admin/expand/{itemId}/mark-expanded")
    fun markExpanded(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val joins = MediaItemTitle.findAll().filter { it.media_item_id == itemId }

        // Retire placeholders
        val placeholders = joins.mapNotNull { j -> Title.findById(j.title_id)?.takeIf { it.tmdb_id == null && it.enrichment_status == EnrichmentStatus.SKIPPED.name } }
        for (ph in placeholders) {
            MediaItemTitle.findAll().filter { it.media_item_id == itemId && it.title_id == ph.id!! }.forEach { it.delete() }
            if (MediaItemTitle.findAll().none { it.title_id == ph.id!! }) ph.delete()
        }

        val remaining = MediaItemTitle.findAll().count { it.media_item_id == itemId }
        item.expansion_status = ExpansionStatus.EXPANDED.name
        item.title_count = remaining
        item.updated_at = LocalDateTime.now()
        item.save()
        return jsonResponse("""{"ok":true}""")
    }

    /** Mark as not a multi-pack (single title). */
    @Post("/api/v2/admin/expand/{itemId}/not-multipack")
    fun notMultiPack(ctx: ServiceRequestContext, @Param("itemId") itemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findById(itemId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        item.expansion_status = ExpansionStatus.SINGLE.name
        item.title_count = 1
        item.updated_at = LocalDateTime.now()
        item.save()

        MediaItemTitle.findAll().filter { it.media_item_id == itemId }.forEach { join ->
            val title = Title.findById(join.title_id)
            if (title != null && title.enrichment_status == EnrichmentStatus.SKIPPED.name) {
                title.enrichment_status = EnrichmentStatus.PENDING.name
                title.updated_at = LocalDateTime.now()
                title.save()
            }
        }
        return jsonResponse("""{"ok":true}""")
    }

    /** Search TMDB for titles to add. */
    @Get("/api/v2/admin/expand/search-tmdb")
    fun searchTmdb(ctx: ServiceRequestContext, @Param("q") query: String, @Param("type") type: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val results = if (type.uppercase() == "TV") tmdbService.searchTvMultiple(query.trim(), 10) else tmdbService.searchMovieMultiple(query.trim(), 10)
        val items = results.map { r ->
            mapOf("tmdb_id" to r.tmdbId, "title" to r.title, "media_type" to r.mediaType,
                "release_year" to r.releaseYear, "poster_path" to r.posterPath)
        }
        return jsonResponse(gson.toJson(mapOf("results" to items)))
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON_UTF_8).contentLength(bytes.size.toLong()).build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.BAD_REQUEST).contentType(MediaType.JSON_UTF_8).contentLength(bytes.size.toLong()).build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
