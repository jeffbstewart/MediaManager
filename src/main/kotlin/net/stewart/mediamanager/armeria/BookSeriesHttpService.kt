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
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.OpenLibraryHttpService
import net.stewart.mediamanager.service.OpenLibraryService
import net.stewart.mediamanager.service.WishListService
import net.stewart.mediamanager.service.parseSeriesLine

/**
 * Book-series browse surface. A series has a poster (auto-filled from
 * volume 1 on first scan — see BookIngestionService), an author, and an
 * ordered list of volumes. The UI uses this to answer "which volumes of
 * Foundation do I own?" and, eventually, "let me fill the gaps." See
 * docs/BOOKS.md.
 */
@Blocking
class BookSeriesHttpService(
    private val openLibrary: OpenLibraryService = OpenLibraryHttpService()
) {

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

        val ownedWorkIds = Title.findAll()
            .asSequence()
            .filter { it.media_type == MMMediaType.BOOK.name && it.book_series_id == seriesId }
            .mapNotNull { it.open_library_work_id }
            .toSet()

        val missing = missingVolumes(series, author, ownedWorkIds, user.id!!)

        val result = mapOf(
            "id" to series.id,
            "name" to series.name,
            "description" to series.description,
            "poster_url" to posterUrl(series),
            "author" to author?.let {
                mapOf("id" to it.id, "name" to it.name)
            },
            "volumes" to volumes,
            "missing_volumes" to missing,
            // UI hint — the "Missing Volumes" section is only meaningful when
            // we can enumerate against a known author's bibliography.
            "can_fill_gaps" to (author?.open_library_author_id != null)
        )
        return jsonResponse(gson.toJson(result))
    }

    /**
     * POST /api/v2/catalog/series/{seriesId}/wishlist-gaps — bulk-adds a book
     * wish for every currently-missing volume. Returns the count added and
     * the count that were already wished (so the client can render a
     * toast like "Added 3 books, 2 already on your wishlist").
     */
    @Post("/api/v2/catalog/series/{seriesId}/wishlist-gaps")
    fun wishlistGaps(
        ctx: ServiceRequestContext,
        @Param("seriesId") seriesId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val series = BookSeries.findById(seriesId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val author = series.author_id?.let { Author.findById(it) }
            ?: return jsonResponse(gson.toJson(mapOf(
                "added" to 0,
                "already_wished" to 0,
                "error" to "Series has no associated author; cannot enumerate volumes."
            )))

        val ownedWorkIds = Title.findAll()
            .asSequence()
            .filter { it.media_type == MMMediaType.BOOK.name && it.book_series_id == seriesId }
            .mapNotNull { it.open_library_work_id }
            .toSet()

        val already = WishListService.activeBookWishWorkIdsForUser(user.id!!)
        val missing = missingVolumes(series, author, ownedWorkIds, user.id!!)

        var added = 0
        var alreadyCount = 0
        for (vol in missing) {
            val workId = vol["ol_work_id"] as? String ?: continue
            if (workId in already) {
                alreadyCount++
                continue
            }
            WishListService.addBookWishForUser(user.id!!, WishListService.BookWishInput(
                openLibraryWorkId = workId,
                title = (vol["title"] as? String) ?: "",
                author = author.name,
                coverIsbn = null,
                seriesId = series.id,
                seriesNumber = (vol["series_number"] as? String)?.toBigDecimalOrNull()
            ))
            added++
        }

        return jsonResponse(gson.toJson(mapOf(
            "added" to added,
            "already_wished" to alreadyCount
        )))
    }

    /**
     * Derives "missing volumes in this series" from the series's primary author's
     * Open Library bibliography. Filters to works whose parsed series name matches
     * this series (case-insensitive) and whose OL work ID isn't in the owned set.
     * Returns [] if the series has no author_id (caller uses can_fill_gaps to
     * render that case).
     */
    private fun missingVolumes(
        series: BookSeries,
        author: Author?,
        ownedWorkIds: Set<String>,
        userId: Long
    ): List<Map<String, Any?>> {
        val olid = author?.open_library_author_id ?: return emptyList()
        val wishedIds = WishListService.activeBookWishWorkIdsForUser(userId)
        val seriesNameLower = series.name.lowercase()

        return openLibrary.listAuthorWorks(olid, limit = 200)
            .asSequence()
            .filter { it.seriesRaw != null }
            .mapNotNull { work ->
                val raw = work.seriesRaw ?: return@mapNotNull null
                val (parsedName, number) = parseSeriesLine(raw)
                if (!parsedName.equals(series.name, ignoreCase = true) &&
                    !parsedName.lowercase().contains(seriesNameLower)) {
                    return@mapNotNull null
                }
                if (work.openLibraryWorkId in ownedWorkIds) return@mapNotNull null
                Triple(work, parsedName, number)
            }
            .sortedBy { it.third ?: java.math.BigDecimal("9999") }
            .map { (work, _, number) ->
                mapOf(
                    "ol_work_id" to work.openLibraryWorkId,
                    "title" to work.title,
                    "series_number" to number?.toPlainString(),
                    "year" to work.firstPublishYear,
                    "cover_url" to work.coverUrl,
                    "already_wished" to (work.openLibraryWorkId in wishedIds)
                )
            }
            .toList()
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
