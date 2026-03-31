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
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.service.DiscoveredFileLinkService
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.service.FuzzyMatchService
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TmdbService

/**
 * REST endpoints for the unmatched files admin page in the Angular web app.
 */
@Blocking
class UnmatchedHttpService {

    private val gson = Gson()
    private val tmdbService = TmdbService()

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
                "is_personal" to (df.media_type == MMMediaType.PERSONAL.name),
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

        val count = DiscoveredFileLinkService.linkToTitle(df, best.title)
        return jsonResponse(gson.toJson(mapOf("ok" to true, "linked" to count, "title_name" to best.title.name)))
    }

    /** Link an unmatched file to a specific title by ID. */
    @Post("/api/v2/admin/unmatched/{id}/link/{titleId}")
    fun link(ctx: ServiceRequestContext, @Param("id") id: Long, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val df = DiscoveredFile.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val title = Title.findById(titleId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val count = DiscoveredFileLinkService.linkToTitle(df, title)
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

    /** Create a personal video title and link the discovered file to it. */
    @Post("/api/v2/admin/unmatched/{id}/create-personal")
    fun createPersonal(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val df = DiscoveredFile.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val title = Title(
            name = df.parsed_title ?: df.file_name,
            media_type = MMMediaType.PERSONAL.name,
            enrichment_status = EnrichmentStatus.SKIPPED.name,
            created_at = java.time.LocalDateTime.now(),
            updated_at = java.time.LocalDateTime.now()
        )
        title.save()

        val tc = Transcode(title_id = title.id!!, file_path = df.file_path, media_format = df.media_format)
        tc.save()

        df.match_status = DiscoveredFileStatus.MATCHED.name
        df.matched_title_id = title.id
        df.save()

        SearchIndexService.onTitleChanged(title.id!!)
        return jsonResponse(gson.toJson(mapOf("ok" to true, "title_id" to title.id, "title_name" to title.name)))
    }

    /** Search TMDB and create + link a new title from results. */
    @Post("/api/v2/admin/unmatched/{id}/add-from-tmdb")
    fun addFromTmdb(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val df = DiscoveredFile.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val tmdbId = (map["tmdb_id"] as? Number)?.toInt() ?: return HttpResponse.of(HttpStatus.BAD_REQUEST)
        val mediaType = map["media_type"] as? String ?: return HttpResponse.of(HttpStatus.BAD_REQUEST)

        val mmType = when (mediaType.uppercase()) {
            "TV" -> MMMediaType.TV
            else -> MMMediaType.MOVIE
        }
        val tmdbKey = TmdbId(tmdbId, mmType)
        val details = try { tmdbService.getDetails(tmdbKey) } catch (_: Exception) { null }

        // Check for existing title with same TMDB key
        var title = Title.findAll().firstOrNull { it.tmdbKey() == tmdbKey }
        if (title == null) {
            title = Title(
                name = details?.title ?: "Unknown",
                media_type = tmdbKey.typeString,
                tmdb_id = tmdbKey.id,
                release_year = details?.releaseYear,
                description = details?.overview,
                poster_path = details?.posterPath,
                enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name,
                created_at = java.time.LocalDateTime.now(),
                updated_at = java.time.LocalDateTime.now()
            )
            title.save()
            SearchIndexService.onTitleChanged(title.id!!)
        }

        val tc = Transcode(title_id = title.id!!, file_path = df.file_path, media_format = df.media_format)
        tc.save()

        df.match_status = DiscoveredFileStatus.MATCHED.name
        df.matched_title_id = title.id
        df.save()

        return jsonResponse(gson.toJson(mapOf("ok" to true, "title_id" to title.id, "title_name" to title.name)))
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
