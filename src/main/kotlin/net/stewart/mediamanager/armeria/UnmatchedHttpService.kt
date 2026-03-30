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
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.DiscoveredFileStatus
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.linkDiscoveredFileToTitle
import net.stewart.mediamanager.service.FuzzyMatchService

/**
 * REST endpoints for the unmatched files admin page in the Angular web app.
 */
@Blocking
class UnmatchedHttpService {

    private val gson = Gson()

    /** List all unmatched discovered files with top fuzzy-match suggestion. */
    @Get("/api/v2/admin/unmatched")
    fun listUnmatched(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val unmatched = DiscoveredFile.findAll()
            .filter { it.match_status == DiscoveredFileStatus.UNMATCHED.name }
            .sortedBy { it.parsed_title?.lowercase() ?: it.file_name.lowercase() }

        val allTitles = Title.findAll()

        val items = unmatched.map { df ->
            val suggestions = if (!df.parsed_title.isNullOrBlank()) {
                FuzzyMatchService.findSuggestions(df.parsed_title!!, allTitles, maxResults = 1)
            } else emptyList()
            val best = suggestions.firstOrNull()

            mapOf(
                "id" to df.id,
                "file_name" to df.file_name,
                "file_path" to df.file_path,
                "directory" to df.directory,
                "media_type" to df.media_type,
                "parsed_title" to df.parsed_title,
                "parsed_year" to df.parsed_year,
                "parsed_season" to df.parsed_season,
                "parsed_episode" to df.parsed_episode,
                "suggestion" to if (best != null) mapOf(
                    "title_id" to best.title.id,
                    "title_name" to best.title.name,
                    "score" to (best.score * 100).toInt()
                ) else null
            )
        }

        return jsonResponse(gson.toJson(mapOf("files" to items, "total" to items.size)))
    }

    /** Accept the top fuzzy-match suggestion for an unmatched file. */
    @Post("/api/v2/admin/unmatched/{id}/accept")
    fun accept(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val df = DiscoveredFile.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        if (df.parsed_title.isNullOrBlank()) return jsonResponse("""{"ok":false,"reason":"No parsed title"}""")

        val allTitles = Title.findAll()
        val suggestions = FuzzyMatchService.findSuggestions(df.parsed_title!!, allTitles)
        val best = suggestions.firstOrNull() ?: return jsonResponse("""{"ok":false,"reason":"No match found"}""")

        val count = linkDiscoveredFileToTitle(df, best.title)
        return jsonResponse(gson.toJson(mapOf("ok" to true, "linked" to count, "title_name" to best.title.name)))
    }

    /** Link an unmatched file to a specific title by ID. */
    @Post("/api/v2/admin/unmatched/{id}/link/{titleId}")
    fun link(ctx: ServiceRequestContext, @Param("id") id: Long, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val df = DiscoveredFile.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val title = Title.findById(titleId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val count = linkDiscoveredFileToTitle(df, title)
        return jsonResponse(gson.toJson(mapOf("ok" to true, "linked" to count, "title_name" to title.name)))
    }

    /** Ignore an unmatched file (dismiss from list). */
    @Post("/api/v2/admin/unmatched/{id}/ignore")
    fun ignore(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val df = DiscoveredFile.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        df.match_status = DiscoveredFileStatus.IGNORED.name
        df.save()
        return jsonResponse("""{"ok":true}""")
    }

    /** Search catalog titles for manual linking. */
    @Get("/api/v2/admin/unmatched/search-titles")
    fun searchTitles(ctx: ServiceRequestContext, @Param("q") query: String): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val q = query.trim()
        if (q.length < 2) return jsonResponse(gson.toJson(mapOf("titles" to emptyList<Any>())))

        val allTitles = Title.findAll().filter { !it.hidden }
        val lower = q.lowercase()

        val matches = allTitles.filter {
            it.name.lowercase().contains(lower) || (it.sort_name?.lowercase()?.contains(lower) == true)
        }.sortedBy { it.name.lowercase() }.take(20)

        val results = matches.map { t ->
            mapOf("id" to t.id, "name" to t.name, "media_type" to t.media_type, "release_year" to t.release_year)
        }
        return jsonResponse(gson.toJson(mapOf("titles" to results)))
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
