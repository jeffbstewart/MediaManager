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
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.UnmatchedBook
import net.stewart.mediamanager.entity.UnmatchedBookStatus
import net.stewart.mediamanager.service.BookIngestionService
import net.stewart.mediamanager.service.OpenLibraryHttpService
import net.stewart.mediamanager.service.OpenLibraryResult
import net.stewart.mediamanager.service.OpenLibraryService
import java.time.LocalDateTime

/**
 * Admin-only endpoints for the Unmatched Books queue (M4).
 *
 * When the NAS scanner finds an ebook file without a resolvable ISBN, it
 * parks the file in [UnmatchedBook]. Admin then either provides a correct
 * ISBN via [linkByIsbn] or marks the row [ignore].
 */
@Blocking
class UnmatchedBookHttpService(
    private val openLibrary: OpenLibraryService = OpenLibraryHttpService()
) {

    private val gson = Gson()

    @Get("/api/v2/admin/unmatched-books")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val rows = UnmatchedBook.findAll()
            .filter { it.match_status == UnmatchedBookStatus.UNMATCHED.name }
            .sortedByDescending { it.discovered_at }
            .map { row ->
                mapOf(
                    "id" to row.id,
                    "file_path" to row.file_path,
                    "file_name" to row.file_name,
                    "file_size_bytes" to row.file_size_bytes,
                    "media_format" to row.media_format,
                    "parsed_title" to row.parsed_title,
                    "parsed_author" to row.parsed_author,
                    "parsed_isbn" to row.parsed_isbn,
                    "discovered_at" to row.discovered_at?.toString()
                )
            }

        return jsonResponse(gson.toJson(mapOf("files" to rows, "total" to rows.size)))
    }

    /**
     * Admin manually supplies an ISBN for an unmatched file. We re-run
     * ingestion digitally against Open Library, create (or reuse) the Title,
     * add a MediaItem pointing at `file_path`, and mark the row LINKED.
     */
    @Post("/api/v2/admin/unmatched-books/{id}/link-isbn")
    fun linkByIsbn(
        ctx: ServiceRequestContext,
        @Param("id") id: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val row = UnmatchedBook.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        val isbn = (body["isbn"] as? String)?.trim()?.replace("-", "")
            ?: return badRequest("isbn required")

        val lookup = openLibrary.lookupByIsbn(isbn)
        if (lookup !is OpenLibraryResult.Success) {
            return jsonResponse(gson.toJson(mapOf(
                "ok" to false,
                "error" to when (lookup) {
                    is OpenLibraryResult.NotFound -> "Open Library has no record of that ISBN"
                    is OpenLibraryResult.Error -> "Open Library error: ${lookup.message}"
                    else -> "Unknown lookup failure"
                }
            )))
        }

        val format = runCatching { MediaFormat.valueOf(row.media_format) }.getOrDefault(MediaFormat.EBOOK_EPUB)
        val result = BookIngestionService.ingestDigital(
            filePath = row.file_path,
            fileFormat = format,
            isbn = isbn,
            lookup = lookup.book
        )
        row.match_status = UnmatchedBookStatus.LINKED.name
        row.linked_title_id = result.title.id
        row.linked_at = LocalDateTime.now()
        row.save()

        return jsonResponse(gson.toJson(mapOf(
            "ok" to true,
            "title_id" to result.title.id,
            "title_name" to result.title.name,
            "reused" to result.titleReused
        )))
    }

    @Post("/api/v2/admin/unmatched-books/{id}/ignore")
    fun ignore(ctx: ServiceRequestContext, @Param("id") id: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val row = UnmatchedBook.findById(id) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        row.match_status = UnmatchedBookStatus.IGNORED.name
        row.save()
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(message: String): HttpResponse =
        HttpResponse.builder()
            .status(HttpStatus.BAD_REQUEST)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to message)))
            .build()
}
