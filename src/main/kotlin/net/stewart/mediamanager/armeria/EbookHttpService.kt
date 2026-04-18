package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.MetricsRegistry
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Serves a digital book edition's raw bytes. Used by the web reader
 * (`/reader/:mediaItemId`) — epub.js fetches the URL and renders it,
 * and native PDF viewers iframe it directly.
 *
 * The handler:
 *  - Enforces the user's rating ceiling via the linked Title.
 *  - Supports single-range HTTP Range (PDF viewers like to seek).
 *  - Picks a Content-Type from `media_item.media_format`.
 *  - Sets an aggressive cache header — books don't change under an ID.
 *
 * See docs/BOOKS.md (M5).
 */
@Blocking
class EbookHttpService {

    @Get("/ebook/{mediaItemId}")
    fun get(ctx: ServiceRequestContext, @Param("mediaItemId") mediaItemId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val item = MediaItem.findById(mediaItemId) ?: return notFound()
        val filePath = item.file_path ?: return notFound()

        // Rating check via any linked Title (books usually link 1:1 but keep the generality).
        val linkedTitleIds = MediaItemTitle.findAll()
            .filter { it.media_item_id == mediaItemId }
            .map { it.title_id }
        val visible = linkedTitleIds.mapNotNull { Title.findById(it) }.all { user.canSeeRating(it.content_rating) }
        if (!visible) return forbidden()

        val format = runCatching { MediaFormat.valueOf(item.media_format) }.getOrDefault(MediaFormat.EBOOK_EPUB)
        val contentType = when (format) {
            MediaFormat.EBOOK_EPUB -> MediaType.parse("application/epub+zip")
            MediaFormat.EBOOK_PDF -> MediaType.parse("application/pdf")
            else -> MediaType.OCTET_STREAM
        }

        val path = Path.of(filePath)
        if (!Files.exists(path) || !Files.isRegularFile(path)) return notFound()

        val fileLength = Files.size(path)
        val rangeHeader = ctx.request().headers().get("range")

        if (rangeHeader == null) {
            val bytes = Files.readAllBytes(path)
            val headers = ResponseHeaders.builder(HttpStatus.OK)
                .contentType(contentType)
                .add("Accept-Ranges", "bytes")
                .add("Cache-Control", "private, max-age=3600")
                .contentLength(bytes.size.toLong())
                .build()
            MetricsRegistry.countHttpResponse("ebook", 200)
            return HttpResponse.of(headers, HttpData.wrap(bytes))
        }

        val range = parseRange(rangeHeader, fileLength)
        if (range == null) {
            val headers = ResponseHeaders.builder(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .add("Content-Range", "bytes */$fileLength")
                .build()
            MetricsRegistry.countHttpResponse("ebook", 416)
            return HttpResponse.of(headers)
        }
        val (start, end) = range
        val length = (end - start + 1).toInt()
        val buf = ByteArray(length)
        RandomAccessFile(path.toFile(), "r").use { raf ->
            raf.seek(start)
            var read = 0
            while (read < length) {
                val n = raf.read(buf, read, length - read)
                if (n == -1) break
                read += n
            }
        }
        val headers = ResponseHeaders.builder(HttpStatus.PARTIAL_CONTENT)
            .contentType(contentType)
            .add("Accept-Ranges", "bytes")
            .add("Content-Range", "bytes $start-$end/$fileLength")
            .add("Cache-Control", "private, max-age=3600")
            .contentLength(length.toLong())
            .build()
        MetricsRegistry.countHttpResponse("ebook", 206)
        return HttpResponse.of(headers, HttpData.wrap(buf))
    }

    /** Parses a single-range `Range: bytes=N-M` header; returns null if malformed. */
    internal fun parseRange(header: String, length: Long): Pair<Long, Long>? {
        val m = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(header.trim()) ?: return null
        val (startStr, endStr) = m.destructured
        val start: Long
        val end: Long
        when {
            startStr.isEmpty() && endStr.isEmpty() -> return null
            startStr.isEmpty() -> {
                // suffix range: last N bytes
                val suffix = endStr.toLongOrNull() ?: return null
                start = maxOf(0L, length - suffix)
                end = length - 1
            }
            endStr.isEmpty() -> {
                start = startStr.toLongOrNull() ?: return null
                end = length - 1
            }
            else -> {
                start = startStr.toLongOrNull() ?: return null
                end = endStr.toLongOrNull() ?: return null
            }
        }
        if (start < 0 || end >= length || start > end) return null
        return start to end
    }

    private fun notFound(): HttpResponse {
        MetricsRegistry.countHttpResponse("ebook", 404)
        return HttpResponse.of(HttpStatus.NOT_FOUND)
    }

    private fun forbidden(): HttpResponse {
        MetricsRegistry.countHttpResponse("ebook", 403)
        return HttpResponse.of(HttpStatus.FORBIDDEN)
    }
}
