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
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.service.MediaItemDeleteService
import net.stewart.mediamanager.service.SearchIndexService
import java.time.LocalDateTime

@Blocking
class DataQualityHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/data-quality")
    fun list(
        ctx: ServiceRequestContext,
        @Param("status") @Default("") status: String,
        @Param("search") @Default("") search: String,
        @Param("show_hidden") @Default("false") showHidden: Boolean,
        @Param("page") @Default("0") page: Int,
        @Param("size") @Default("50") size: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        var titles = Title.findAll()
        if (status == "NEEDS_ATTENTION") {
            titles = titles.filter { it.enrichment_status != EnrichmentStatus.ENRICHED.name && it.media_type != MMMediaType.PERSONAL.name }
        } else if (status.isNotBlank()) {
            titles = titles.filter { it.enrichment_status == status }
        }
        if (!showHidden) titles = titles.filter { !it.hidden }
        val q = search.trim().lowercase()
        if (q.isNotEmpty()) titles = titles.filter { it.name.lowercase().contains(q) }

        titles = titles.sortedBy { it.name.lowercase() }

        val castByTitle = CastMember.findAll().groupBy { it.title_id }
        val genresByTitle = TitleGenre.findAll().groupBy { it.title_id }

        val total = titles.size
        val paged = titles.drop(page * size).take(size)

        val rows = paged.map { title ->
            val issues = mutableListOf<String>()
            if (title.poster_path == null) issues.add("NO_POSTER")
            if (title.description.isNullOrBlank()) issues.add("NO_DESCRIPTION")
            if (title.tmdb_id == null) issues.add("NO_TMDB_ID")
            if (title.release_year == null) issues.add("NO_YEAR")
            if (title.content_rating == null) issues.add("NO_CONTENT_RATING")
            if (title.backdrop_path == null) issues.add("NO_BACKDROP")
            if (castByTitle[title.id].isNullOrEmpty()) issues.add("NO_CAST")
            if (genresByTitle[title.id].isNullOrEmpty()) issues.add("NO_GENRES")
            if (title.enrichment_status == EnrichmentStatus.FAILED.name) issues.add("ENRICHMENT_FAILED")
            if (title.enrichment_status == EnrichmentStatus.ABANDONED.name) issues.add("ENRICHMENT_ABANDONED")

            mapOf(
                "title_id" to title.id, "name" to title.name,
                "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                "release_year" to title.release_year, "media_type" to title.media_type,
                "content_rating" to title.content_rating,
                "enrichment_status" to title.enrichment_status,
                "hidden" to title.hidden, "tmdb_id" to title.tmdb_id,
                "issues" to issues
            )
        }

        // Count needing attention: not enriched, excluding personal videos (matches Vaadin badge)
        val needsAttention = Title.findAll().count { t ->
            t.enrichment_status != EnrichmentStatus.ENRICHED.name &&
                t.media_type != MMMediaType.PERSONAL.name
        }

        return jsonResponse(gson.toJson(mapOf("rows" to rows, "total" to total, "needs_attention" to needsAttention)))
    }

    @Post("/api/v2/admin/data-quality/{titleId}/re-enrich")
    fun reEnrich(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val title = Title.findById(titleId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        title.enrichment_status = EnrichmentStatus.PENDING.name
        title.retry_after = null
        title.updated_at = LocalDateTime.now()
        title.save()
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/data-quality/{titleId}/toggle-hidden")
    fun toggleHidden(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val title = Title.findById(titleId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        title.hidden = !title.hidden
        title.updated_at = LocalDateTime.now()
        title.save()
        return jsonResponse(gson.toJson(mapOf("ok" to true, "hidden" to title.hidden)))
    }

    @Post("/api/v2/admin/data-quality/{titleId}/update")
    fun update(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val title = Title.findById(titleId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)

        if (map.containsKey("tmdb_id")) {
            val newTmdbId = (map["tmdb_id"] as? Number)?.toInt()
            title.tmdb_id = newTmdbId
            if (newTmdbId != null) {
                title.enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name
            }
        }
        if (map.containsKey("media_type")) title.media_type = map["media_type"] as String
        title.updated_at = LocalDateTime.now()
        title.save()
        SearchIndexService.onTitleChanged(titleId)
        return jsonResponse("""{"ok":true}""")
    }

    @Delete("/api/v2/admin/data-quality/{titleId}")
    fun deleteTitle(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        // Delete all media items linked to this title (cascades through MediaItemDeleteService).
        // MediaItemDeleteService.delete() handles orphaned title cleanup: if no other item
        // references the title, the title and all its dependents are deleted too.
        val mediaItemIds = MediaItemTitle.findAll()
            .filter { it.title_id == titleId }
            .mapNotNull { it.media_item_id }
            .distinct()
        for (id in mediaItemIds) {
            MediaItemDeleteService.delete(id)
        }

        // If the title still exists (e.g., shared by another media item that wasn't deleted,
        // or had no media items at all), delete it directly with full cascade.
        if (Title.findById(titleId) != null) {
            MediaItemDeleteService.deleteTitleCascade(titleId)
        }

        return jsonResponse("""{"ok":true}""")
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON_UTF_8).contentLength(bytes.size.toLong()).build(), HttpData.wrap(bytes))
    }
}
