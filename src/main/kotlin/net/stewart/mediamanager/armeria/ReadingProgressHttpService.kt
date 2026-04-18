package net.stewart.mediamanager.armeria

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
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.ReadingProgressService

/**
 * Per-user reading position endpoints used by the web reader
 * (`/reader/:mediaItemId`). Clients `POST` every ~10 s as the user reads;
 * `GET` is called on reader open to resume at the last known CFI.
 */
@Blocking
class ReadingProgressHttpService {

    private val gson = Gson()

    @Get("/api/v2/reading-progress/{mediaItemId}")
    fun get(ctx: ServiceRequestContext, @Param("mediaItemId") mediaItemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!canSee(user, mediaItemId)) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val progress = ReadingProgressService.get(user.id!!, mediaItemId)
            ?: return jsonResponse(gson.toJson(mapOf(
                "media_item_id" to mediaItemId,
                "cfi" to null,
                "percent" to 0.0,
                "updated_at" to null
            )))

        return jsonResponse(gson.toJson(mapOf(
            "media_item_id" to mediaItemId,
            "cfi" to progress.cfi,
            "percent" to progress.percent,
            "updated_at" to progress.updated_at?.toString()
        )))
    }

    @Post("/api/v2/reading-progress/{mediaItemId}")
    fun save(ctx: ServiceRequestContext, @Param("mediaItemId") mediaItemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!canSee(user, mediaItemId)) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        val cfi = (body["cfi"] as? String)?.ifBlank { null }
            ?: return badRequest("cfi required")
        val percent = (body["percent"] as? Number)?.toDouble() ?: 0.0

        val saved = ReadingProgressService.save(user.id!!, mediaItemId, cfi, percent)
        return jsonResponse(gson.toJson(mapOf(
            "ok" to true,
            "cfi" to saved.cfi,
            "percent" to saved.percent
        )))
    }

    @Delete("/api/v2/reading-progress/{mediaItemId}")
    fun delete(ctx: ServiceRequestContext, @Param("mediaItemId") mediaItemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        ReadingProgressService.delete(user.id!!, mediaItemId)
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    /** Books link 1:1 with Title via MediaItemTitle. Defer to the title's rating. */
    private fun canSee(user: net.stewart.mediamanager.entity.AppUser, mediaItemId: Long): Boolean {
        if (MediaItem.findById(mediaItemId) == null) return false
        val linkedTitleIds = MediaItemTitle.findAll()
            .filter { it.media_item_id == mediaItemId }
            .map { it.title_id }
        val titles = linkedTitleIds.mapNotNull { Title.findById(it) }
        return titles.all { user.canSeeRating(it.content_rating) }
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(message: String): HttpResponse =
        HttpResponse.builder()
            .status(HttpStatus.BAD_REQUEST)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to message)))
            .build()
}
