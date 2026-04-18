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
import net.stewart.mediamanager.entity.TitleAuthor

/**
 * Author browse surface. See docs/BOOKS.md — mirrors the ActorHttpService
 * shape but reads from the Open-Library-sourced `author` table and the
 * `title_author` link. "Other works" from Open Library is a later
 * enrichment (M2+); for now the screen shows bio, headshot, and the
 * books the user owns by this author.
 */
@Blocking
class AuthorHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/authors")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        // Count owned books per author so the list can lead with "most-read."
        val byAuthor = TitleAuthor.findAll().groupingBy { it.author_id }.eachCount()
        val authors = Author.findAll()
            .sortedBy { it.sort_name.ifBlank { it.name }.lowercase() }
            .map { author ->
                mapOf(
                    "id" to author.id,
                    "name" to author.name,
                    "sort_name" to author.sort_name,
                    "headshot_url" to headshotUrl(author),
                    "book_count" to (byAuthor[author.id] ?: 0)
                )
            }

        return jsonResponse(gson.toJson(mapOf("authors" to authors)))
    }

    @Get("/api/v2/catalog/authors/{authorId}")
    fun detail(
        ctx: ServiceRequestContext,
        @Param("authorId") authorId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val author = Author.findById(authorId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        // Titles this author is credited on, filtered by the user's rating ceiling.
        val authorLinks = TitleAuthor.findAll().filter { it.author_id == authorId }
        val linkedTitleIds = authorLinks.map { it.title_id }.toSet()
        val titles = Title.findAll()
            .filter { it.id in linkedTitleIds }
            .filter { it.media_type == MMMediaType.BOOK.name }
            .filter { !it.hidden && user.canSeeRating(it.content_rating) }

        val seriesById = BookSeries.findAll().associateBy { it.id }

        val ownedBooks = titles.map { title ->
            val series = title.book_series_id?.let { seriesById[it] }
            mapOf(
                "title_id" to title.id,
                "title_name" to title.name,
                "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                "release_year" to (title.first_publication_year ?: title.release_year),
                "series_name" to series?.name,
                "series_number" to title.series_number?.toPlainString()
            )
        }.sortedWith(compareBy(
            { (it["series_name"] as? String) ?: "zzz" },
            { (it["series_number"] as? String)?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO },
            { (it["title_name"] as? String) ?: "" }
        ))

        val result = mapOf(
            "id" to author.id,
            "name" to author.name,
            "biography" to author.biography,
            "headshot_url" to headshotUrl(author),
            "birth_date" to author.birth_date?.toString(),
            "death_date" to author.death_date?.toString(),
            "open_library_author_id" to author.open_library_author_id,
            "owned_books" to ownedBooks
        )
        return jsonResponse(gson.toJson(result))
    }

    private fun headshotUrl(author: Author): String? = author.headshot_path
        ?: author.open_library_author_id?.let { id ->
            "https://covers.openlibrary.org/a/olid/$id-M.jpg"
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
