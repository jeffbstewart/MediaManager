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
import com.linecorp.armeria.server.annotation.Put
import net.stewart.mediamanager.service.TagService

/**
 * REST endpoints for admin tag management in the Angular web app.
 */
@Blocking
class TagManagementHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/tags")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val tags = TagService.getAllTags()
        val counts = TagService.getTagTitleCounts()

        val items = tags.map { tag ->
            mapOf(
                "id" to tag.id,
                "name" to tag.name,
                "bg_color" to tag.bg_color,
                "text_color" to tag.textColor(),
                "source_type" to tag.source_type,
                "title_count" to (counts[tag.id] ?: 0)
            )
        }
        return jsonResponse(gson.toJson(mapOf("tags" to items, "total" to items.size)))
    }

    @Post("/api/v2/admin/tags")
    fun create(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val name = (map["name"] as? String)?.trim() ?: return badRequest("name required")
        val bgColor = map["bg_color"] as? String ?: "#6B7280"

        if (name.isBlank()) return badRequest("name required")
        if (!TagService.isNameUnique(name)) return jsonResponse(gson.toJson(mapOf("ok" to false, "error" to "Name already exists")))

        val tag = TagService.createTag(name, bgColor, user.id)
        return jsonResponse(gson.toJson(mapOf("ok" to true, "id" to tag.id)))
    }

    @Put("/api/v2/admin/tags/{tagId}")
    fun update(ctx: ServiceRequestContext, @Param("tagId") tagId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val name = (map["name"] as? String)?.trim() ?: return badRequest("name required")
        val bgColor = map["bg_color"] as? String ?: "#6B7280"

        if (name.isBlank()) return badRequest("name required")
        if (!TagService.isNameUnique(name, tagId)) return jsonResponse(gson.toJson(mapOf("ok" to false, "error" to "Name already exists")))

        TagService.updateTag(tagId, name, bgColor)
        return jsonResponse("""{"ok":true}""")
    }

    @Delete("/api/v2/admin/tags/{tagId}")
    fun delete(ctx: ServiceRequestContext, @Param("tagId") tagId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        TagService.deleteTag(tagId)
        return jsonResponse("""{"ok":true}""")
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
