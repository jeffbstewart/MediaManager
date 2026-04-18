package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.UnmatchedBook
import net.stewart.mediamanager.entity.UnmatchedBookStatus
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Retries Open Library ISBN lookups for ebooks that were staged as unmatched
 * before OpenLibraryHttpService learned to follow 302 redirects. Any row that
 * still has a parsed ISBN and is in UNMATCHED status gets re-resolved; a
 * successful hit is promoted via [BookIngestionService.ingestDigital] and its
 * unmatched row deleted.
 *
 * Throttled to stay polite to openlibrary.org.
 */
class RetryUnmatchedBooksUpdater(
    private val openLibrary: OpenLibraryService = OpenLibraryHttpService(),
    private val clock: Clock = SystemClock
) : SchemaUpdater {

    private val log = LoggerFactory.getLogger(RetryUnmatchedBooksUpdater::class.java)

    override val name = "retry_unmatched_books_ol_302"
    override val version = 1

    override fun run() {
        val candidates = UnmatchedBook.findAll().filter {
            it.match_status == UnmatchedBookStatus.UNMATCHED.name &&
                !it.parsed_isbn.isNullOrBlank()
        }
        if (candidates.isEmpty()) return

        log.info("Retrying OL lookup for {} unmatched ebook(s)", candidates.size)
        var promoted = 0
        var stillUnresolved = 0
        for (row in candidates) {
            val isbn = row.parsed_isbn!!
            val format = runCatching { MediaFormat.valueOf(row.media_format) }.getOrNull()
                ?: MediaFormat.EBOOK_EPUB
            val lookup = openLibrary.lookupByIsbn(isbn)
            if (lookup is OpenLibraryResult.Success) {
                BookIngestionService.ingestDigital(
                    filePath = row.file_path,
                    fileFormat = format,
                    isbn = isbn,
                    lookup = lookup.book
                )
                row.delete()
                promoted++
                log.info("Promoted unmatched ebook {} → title '{}'", row.file_path, lookup.book.workTitle)
            } else {
                stillUnresolved++
            }
            try { clock.sleep(200.milliseconds) } catch (_: InterruptedException) { break }
        }
        log.info("Retry complete: promoted={} stillUnresolved={}", promoted, stillUnresolved)
    }
}
