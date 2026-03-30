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
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.WishListService

/**
 * REST endpoint for the transcode backlog (rip suggestions) admin page.
 */
@Blocking
class BacklogHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/transcode-backlog")
    fun list(
        ctx: ServiceRequestContext,
        @Param("search") @Default("") search: String,
        @Param("page") @Default("0") page: Int,
        @Param("size") @Default("50") size: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val titles = Title.findAll()
        val transcodes = Transcode.findAll()
        val wishCounts = WishListService.getRipPriorityCounts()

        val titlesWithTranscodes = transcodes
            .filter { it.file_path != null }
            .map { it.title_id }
            .toSet()

        val titlesWithMedia = MediaItemTitle.findAll()
            .map { it.title_id }
            .toSet()

        var rows = titles
            .filter {
                it.enrichment_status == EnrichmentStatus.ENRICHED.name &&
                    !it.hidden &&
                    it.id in titlesWithMedia &&
                    it.id !in titlesWithTranscodes
            }
            .map { title ->
                mapOf(
                    "title_id" to title.id,
                    "title_name" to title.name,
                    "media_type" to title.media_type,
                    "release_year" to title.release_year,
                    "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                    "request_count" to (wishCounts[title.id] ?: 0),
                    "popularity" to (title.popularity ?: 0.0)
                )
            }

        val q = search.trim().lowercase()
        if (q.isNotEmpty()) {
            rows = rows.filter { (it["title_name"] as String).lowercase().contains(q) }
        }

        rows = rows.sortedWith(
            compareByDescending<Map<String, Any?>> { (it["request_count"] as Int) }
                .thenByDescending { (it["popularity"] as Double) }
        )

        val total = rows.size
        val paged = rows.drop(page * size).take(size)

        return jsonResponse(gson.toJson(mapOf("rows" to paged, "total" to total)))
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
