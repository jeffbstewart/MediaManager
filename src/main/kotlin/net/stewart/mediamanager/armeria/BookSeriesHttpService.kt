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
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title

/**
 * Book-series browse surface. A series has a poster (auto-filled from
 * volume 1 on first scan — see BookIngestionService), an author, and an
 * ordered list of volumes. The UI uses this to answer "which volumes of
 * Foundation do I own?" and, eventually, "let me fill the gaps." See
 * docs/BOOKS.md.
 */
@Blocking
class BookSeriesHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/series")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        // Size each series by the number of linked Title rows so the list
        // can sort largest-first (matches how collection browse behaves).
        val byId = Title.findAll()
            .filter { it.media_type == MMMediaType.BOOK.name && it.book_series_id != null }
            .groupBy { it.book_series_id!! }

        val items = BookSeries.findAll()
            .sortedBy { it.name.lowercase() }
            .map { series ->
                val volumes = byId[series.id] ?: emptyList()
                mapOf(
                    "id" to series.id,
                    "name" to series.name,
                    "poster_url" to posterUrl(series),
                    "volume_count" to volumes.size
                )
            }
        return jsonResponse(gson.toJson(mapOf("series" to items)))
    }

    @Get("/api/v2/catalog/series/{seriesId}")
    fun detail(
        ctx: ServiceRequestContext,
        @Param("seriesId") seriesId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val series = BookSeries.findById(seriesId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val volumes = Title.findAll()
            .filter { it.media_type == MMMediaType.BOOK.name && it.book_series_id == seriesId }
            .filter { !it.hidden && user.canSeeRating(it.content_rating) }
            .sortedWith(compareBy(
                { it.series_number ?: java.math.BigDecimal("9999") },
                { it.sort_name ?: it.name.lowercase() }
            ))
            .map { title ->
                mapOf(
                    "title_id" to title.id,
                    "title_name" to title.name,
                    "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                    "series_number" to title.series_number?.toPlainString(),
                    "first_publication_year" to title.first_publication_year,
                    "owned" to true  // In M2 these are by definition owned — scan origin.
                                      // "Fill the gaps" (not-owned vols from OL) comes later.
                )
            }

        val author = series.author_id?.let { Author.findById(it) }

        val result = mapOf(
            "id" to series.id,
            "name" to series.name,
            "description" to series.description,
            "poster_url" to posterUrl(series),
            "author" to author?.let {
                mapOf("id" to it.id, "name" to it.name)
            },
            "volumes" to volumes
        )
        return jsonResponse(gson.toJson(result))
    }

    /** Series posters are stored as "isbn/<isbn>" — same sentinel the title side uses. */
    private fun posterUrl(series: BookSeries): String? =
        series.poster_path?.let { path ->
            if (path.startsWith("isbn/")) {
                "https://covers.openlibrary.org/b/isbn/${path.removePrefix("isbn/")}-M.jpg"
            } else path
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
