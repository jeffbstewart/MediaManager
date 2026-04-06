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
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.ProblemReport
import net.stewart.mediamanager.entity.ReportStatus
import java.time.LocalDateTime

@Blocking
class ProblemReportHttpService {

    private val gson = Gson()

    /** Any authenticated user can submit a report. */
    @Post("/api/v2/reports")
    fun submit(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java) ?: return badRequest("Invalid JSON")

        val description = (map["description"] as? String)?.trim()
        if (description.isNullOrBlank()) return badRequest("Description is required")

        val now = LocalDateTime.now()
        val report = ProblemReport(
            user_id = user.id!!,
            title_id = (map["title_id"] as? Number)?.toLong(),
            title_name = (map["title_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            season_number = (map["season_number"] as? Number)?.toInt(),
            episode_number = (map["episode_number"] as? Number)?.toInt(),
            description = description,
            created_at = now,
            updated_at = now
        )
        report.save()

        return jsonResponse(gson.toJson(mapOf("ok" to true, "id" to report.id)))
    }

    /** Admin: list reports with optional status filter and pagination. */
    @Get("/api/v2/admin/reports")
    fun list(
        ctx: ServiceRequestContext,
        @Param("status") @Default("OPEN") status: String,
        @Param("page") @Default("0") page: Int,
        @Param("size") @Default("50") size: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        var reports = ProblemReport.findAll()
        val openCount = reports.count { it.status == ReportStatus.OPEN.name }

        if (status.isNotBlank()) {
            reports = reports.filter { it.status == status }
        }
        reports = reports.sortedByDescending { it.created_at }

        val total = reports.size
        val paged = reports.drop(page * size).take(size)

        // Pre-load user names for display
        val userIds = (paged.map { it.user_id } + paged.mapNotNull { it.resolved_by }).toSet()
        val usersById = if (userIds.isNotEmpty()) {
            AppUser.findAll().filter { it.id in userIds }.associateBy { it.id }
        } else emptyMap()

        val rows = paged.map { r ->
            mapOf(
                "id" to r.id,
                "reporter_name" to (usersById[r.user_id]?.username ?: "Unknown"),
                "title_id" to r.title_id,
                "title_name" to r.title_name,
                "season_number" to r.season_number,
                "episode_number" to r.episode_number,
                "description" to r.description,
                "status" to r.status,
                "admin_notes" to r.admin_notes,
                "resolved_by_name" to r.resolved_by?.let { usersById[it]?.username },
                "created_at" to r.created_at?.toString(),
                "updated_at" to r.updated_at?.toString()
            )
        }

        return jsonResponse(gson.toJson(mapOf(
            "rows" to rows,
            "total" to total,
            "open_count" to openCount
        )))
    }

    /** Admin: resolve or dismiss a report. */
    @Post("/api/v2/admin/reports/{reportId}/resolve")
    fun resolve(ctx: ServiceRequestContext, @Param("reportId") reportId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val report = ProblemReport.findById(reportId)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java) ?: return badRequest("Invalid JSON")

        val newStatus = (map["status"] as? String) ?: ReportStatus.RESOLVED.name
        if (newStatus != ReportStatus.RESOLVED.name && newStatus != ReportStatus.DISMISSED.name) {
            return badRequest("Status must be RESOLVED or DISMISSED")
        }

        report.status = newStatus
        report.admin_notes = (map["notes"] as? String)?.trim()
        report.resolved_by = user.id
        report.updated_at = LocalDateTime.now()
        report.save()

        return jsonResponse("""{"ok":true}""")
    }

    /** Admin: reopen a resolved/dismissed report. */
    @Post("/api/v2/admin/reports/{reportId}/reopen")
    fun reopen(ctx: ServiceRequestContext, @Param("reportId") reportId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val report = ProblemReport.findById(reportId)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        report.status = ReportStatus.OPEN.name
        report.admin_notes = null
        report.resolved_by = null
        report.updated_at = LocalDateTime.now()
        report.save()

        return jsonResponse("""{"ok":true}""")
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return HttpResponse.of(
            ResponseHeaders.builder(HttpStatus.OK)
                .contentType(MediaType.JSON_UTF_8)
                .contentLength(bytes.size.toLong())
                .build(),
            HttpData.wrap(bytes)
        )
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        return HttpResponse.of(
            ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.JSON_UTF_8)
                .contentLength(bytes.size.toLong())
                .build(),
            HttpData.wrap(bytes)
        )
    }
}
