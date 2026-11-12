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
import net.stewart.mediamanager.service.FamilyMemberService

@Blocking
class FamilyMemberHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/family-members")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val members = FamilyMemberService.getAllMembers()
        val counts = FamilyMemberService.getMemberTitleCounts()

        val rows = members.map { m ->
            mapOf(
                "id" to m.id,
                "name" to m.name,
                "notes" to m.notes,
                "video_count" to (counts[m.id] ?: 0)
            )
        }
        return jsonResponse(gson.toJson(mapOf("members" to rows)))
    }

    @Post("/api/v2/admin/family-members")
    fun create(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        val name = (body["name"] as? String)?.trim() ?: return badRequest("name required")
        if (name.isBlank()) return badRequest("name required")
        if (!FamilyMemberService.isNameUnique(name)) return badRequest("Name already exists")

        val notes = body["notes"] as? String
        val member = FamilyMemberService.createMember(name, notes = notes)
        return jsonResponse(gson.toJson(mapOf("ok" to true, "id" to member.id)))
    }

    @Post("/api/v2/admin/family-members/{id}")
    fun update(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        val name = (body["name"] as? String)?.trim() ?: return badRequest("name required")
        if (name.isBlank()) return badRequest("name required")
        if (!FamilyMemberService.isNameUnique(name, id)) return badRequest("Name already exists")

        val notes = body["notes"] as? String
        FamilyMemberService.updateMember(id, name, notes = notes)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        return jsonResponse("""{"ok":true}""")
    }

    @Delete("/api/v2/admin/family-members/{id}")
    fun delete(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        FamilyMemberService.deleteMember(id)
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
