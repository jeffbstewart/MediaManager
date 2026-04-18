package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.UnmatchedBook
import net.stewart.mediamanager.entity.UnmatchedBookStatus
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Background daemon that walks [CONFIG_KEY_BOOKS_ROOT] looking for .epub
 * and .pdf files. For each file not already catalogued:
 *
 * - Extracts metadata via [EbookMetadataExtractor].
 * - If the file has an embedded ISBN that resolves against Open Library,
 *   dispatches to [BookIngestionService.ingestDigital] — creates / reuses
 *   the Title and adds a digital [MediaItem] with [MediaItem.file_path] set.
 * - Otherwise stages in [UnmatchedBook] for admin resolution.
 *
 * Idempotent by `file_path` — rescanning the same directory won't duplicate
 * entries.
 *
 * See docs/BOOKS.md (M4).
 */
class BookScannerAgent(
    private val clock: Clock = SystemClock,
    private val openLibrary: OpenLibraryService = OpenLibraryHttpService()
) {

    private val log = LoggerFactory.getLogger(BookScannerAgent::class.java)
    internal val running = AtomicBoolean(false)
    private var thread: Thread? = null

    companion object {
        /** app_config key pointing at the folder containing .epub / .pdf files. */
        const val CONFIG_KEY_BOOKS_ROOT = "books_root_path"

        private val CYCLE_INTERVAL = 1.hours
        private val STARTUP_DELAY = 45.seconds
        private val BOOK_EXTENSIONS = setOf("epub", "pdf")

        /** Max files walked per cycle — keeps the scan bounded on large shelves. */
        private const val MAX_FILES_PER_CYCLE = 500
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("BookScannerAgent started (cycle every {}m, root key '{}')",
                CYCLE_INTERVAL.inWholeMinutes, CONFIG_KEY_BOOKS_ROOT)
            try { clock.sleep(STARTUP_DELAY) } catch (_: InterruptedException) { return@Thread }
            while (running.get()) {
                try {
                    scanOnce()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("BookScannerAgent error: {}", e.message, e)
                }
                try { clock.sleep(CYCLE_INTERVAL) } catch (_: InterruptedException) { break }
            }
            log.info("BookScannerAgent stopped")
        }, "book-scanner").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    internal fun scanOnce() {
        val rootStr = booksRoot() ?: run {
            log.debug("BookScannerAgent: '{}' not configured, skipping", CONFIG_KEY_BOOKS_ROOT)
            return
        }
        val root = Path.of(rootStr)
        if (!Files.isDirectory(root)) {
            log.warn("BookScannerAgent: books_root_path '{}' is not a directory, skipping", rootStr)
            return
        }

        val knownMediaItemPaths = MediaItem.findAll().mapNotNullTo(HashSet()) { it.file_path }
        val knownUnmatchedPaths = UnmatchedBook.findAll().mapTo(HashSet()) { it.file_path }

        var processed = 0
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (!running.get()) break
                if (processed >= MAX_FILES_PER_CYCLE) {
                    log.info("BookScannerAgent: hit MAX_FILES_PER_CYCLE={}, will continue next cycle",
                        MAX_FILES_PER_CYCLE)
                    break
                }
                if (!Files.isRegularFile(path)) continue
                val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
                if (ext !in BOOK_EXTENSIONS) continue
                val canonical = path.toString()
                if (canonical in knownMediaItemPaths || canonical in knownUnmatchedPaths) continue

                processed++
                try {
                    handleFile(path.toFile(), ext)
                    // Track so we don't re-handle inside the same cycle either.
                    knownMediaItemPaths += canonical
                    knownUnmatchedPaths += canonical
                } catch (e: Exception) {
                    log.warn("BookScannerAgent: failed to ingest {}: {}", canonical, e.message, e)
                }
            }
        }

        if (processed > 0) {
            log.info("BookScannerAgent: processed {} new ebook file(s) under {}", processed, rootStr)
        }
    }

    internal fun handleFile(file: File, extension: String) {
        val format = when (extension) {
            "epub" -> MediaFormat.EBOOK_EPUB
            "pdf" -> MediaFormat.EBOOK_PDF
            else -> return
        }

        val metadata = EbookMetadataExtractor.extract(file)
        val filePath = file.absolutePath

        // ISBN path — try Open Library. If it resolves, full ingestion (which
        // reuses the existing Title when the work is already known).
        if (!metadata.isbn.isNullOrBlank()) {
            val lookup = openLibrary.lookupByIsbn(metadata.isbn)
            if (lookup is OpenLibraryResult.Success) {
                BookIngestionService.ingestDigital(
                    filePath = filePath,
                    fileFormat = format,
                    isbn = metadata.isbn,
                    lookup = lookup.book
                )
                return
            }
            log.info("Ebook {} has ISBN {} but OL returned {}; staging as unmatched",
                filePath, metadata.isbn, when (lookup) {
                    is OpenLibraryResult.NotFound -> "NOT_FOUND"
                    is OpenLibraryResult.Error -> "ERROR(${lookup.message})"
                    else -> "?"
                })
        }

        // No ISBN or no OL match — stage for admin resolution.
        stageUnmatched(file, format, metadata)
    }

    private fun stageUnmatched(file: File, format: MediaFormat, metadata: EbookMetadata) {
        UnmatchedBook(
            file_path = file.absolutePath,
            file_name = file.name,
            file_size_bytes = runCatching { file.length() }.getOrNull(),
            media_format = format.name,
            parsed_title = metadata.title,
            parsed_author = metadata.author,
            parsed_isbn = metadata.isbn,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()
        ).save()
        log.info("Unmatched book staged: {} (title='{}' author='{}')",
            file.absolutePath, metadata.title, metadata.author)
    }

    private fun booksRoot(): String? =
        AppConfig.findAll().firstOrNull { it.config_key == CONFIG_KEY_BOOKS_ROOT }
            ?.config_val
            ?.takeIf { it.isNotBlank() }
}
