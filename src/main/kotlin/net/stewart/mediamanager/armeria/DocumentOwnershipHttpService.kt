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
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.OwnershipPhotoService

@Blocking
class DocumentOwnershipHttpService {

    private val gson = Gson()

    /** Look up a media item by UPC. Returns item details + existing photos. */
    @Get("/api/v2/admin/ownership/lookup")
    fun lookup(ctx: ServiceRequestContext, @Param("upc") upc: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val item = MediaItem.findAll().firstOrNull { it.upc == upc.trim() }
        if (item == null) {
            // Novel UPC — no item in catalog yet
            val photos = OwnershipPhotoService.findByUpc(upc.trim())
            return jsonResponse(gson.toJson(mapOf(
                "found" to false, "upc" to upc.trim(),
                "photos" to photos.map { photoMap(it) }
            )))
        }

        val itemId = item.id!!
        val titleName = OwnershipPhotoService.resolveTitleName(itemId)
        val photos = OwnershipPhotoService.findAllForItem(itemId, item.upc)
        val titleId = MediaItemTitle.findAll().firstOrNull { it.media_item_id == itemId }?.title_id
        val posterUrl = titleId?.let { Title.findById(it)?.posterUrl(PosterSize.THUMBNAIL) }

        return jsonResponse(gson.toJson(mapOf(
            "found" to true, "upc" to item.upc,
            "media_item_id" to item.id,
            "title_name" to titleName,
            "media_format" to item.media_format,
            "poster_url" to posterUrl,
            "photos" to photos.map { photoMap(it) }
        )))
    }

    /** Search media items by title or UPC for the search mode. */
    @Get("/api/v2/admin/ownership/search")
    fun search(ctx: ServiceRequestContext, @Param("q") query: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val q = query.trim().lowercase()
        if (q.length < 2) return jsonResponse(gson.toJson(mapOf("items" to emptyList<Any>())))

        val allItems = MediaItem.findAll()
        val allLinks = MediaItemTitle.findAll().groupBy { it.media_item_id }
        val allTitles = Title.findAll().associateBy { it.id }
        val photoCounts = OwnershipPhotoService.countByMediaItem()

        val matches = allItems.filter { item ->
            val upcMatch = item.upc?.contains(q) == true
            val nameMatch = item.product_name?.lowercase()?.contains(q) == true
            val titleMatch = allLinks[item.id]?.any { allTitles[it.title_id]?.name?.lowercase()?.contains(q) == true } == true
            upcMatch || nameMatch || titleMatch
        }.take(20)

        val results = matches.map { item ->
            val titleName = allLinks[item.id]?.firstOrNull()?.let { allTitles[it.title_id]?.name }
                ?: item.product_name ?: "(unknown)"
            mapOf(
                "media_item_id" to item.id,
                "upc" to item.upc,
                "title_name" to titleName,
                "media_format" to item.media_format,
                "photo_count" to (photoCounts[item.id] ?: 0)
            )
        }
        return jsonResponse(gson.toJson(mapOf("items" to results)))
    }

    /** Upload a photo for a media item or UPC. Body is raw image bytes. */
    @Post("/api/v2/admin/ownership/upload")
    fun uploadPhoto(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val agg = ctx.request().aggregate().join()
        val bytes = agg.content().array()
        val contentType = ctx.request().headers().get("Content-Type") ?: "image/jpeg"
        val mediaItemId = ctx.request().headers().get("X-Media-Item-Id")?.toLongOrNull()
        val upc = ctx.request().headers().get("X-UPC")

        if (mediaItemId != null) {
            OwnershipPhotoService.store(bytes, contentType, mediaItemId)
        } else if (upc != null) {
            OwnershipPhotoService.storeForUpc(bytes, contentType, upc, null)
        } else {
            return badRequest("Either X-Media-Item-Id or X-UPC header required")
        }

        return jsonResponse("""{"ok":true}""")
    }

    /** Delete a photo by UUID. */
    @Delete("/api/v2/admin/ownership/photos/{photoId}")
    fun deletePhoto(ctx: ServiceRequestContext, @Param("photoId") photoId: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        OwnershipPhotoService.delete(photoId)
        return jsonResponse("""{"ok":true}""")
    }

    private fun photoMap(photo: net.stewart.mediamanager.entity.OwnershipPhoto) = mapOf(
        "id" to photo.id,
        "url" to "/ownership-photos/${photo.id}",
        "captured_at" to photo.captured_at?.toString()
    )

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8).contentLength(bytes.size.toLong()).build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.JSON_UTF_8).contentLength(bytes.size.toLong()).build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
