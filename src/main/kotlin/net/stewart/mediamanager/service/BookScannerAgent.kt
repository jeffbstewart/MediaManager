package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.Title
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

        // Collect then sort: EPUB before PDF so sibling auto-link sees an
        // already-catalogued MediaItem when the pair arrives in one pass.
        // Secondary sort by path is alphabetical for deterministic ordering
        // across filesystems / JVM implementations.
        val raw = mutableListOf<Pair<Path, String>>()
        Files.walk(root).use { stream ->
            for (p in stream) {
                if (!Files.isRegularFile(p)) continue
                val ext = p.fileName.toString().substringAfterLast('.', "").lowercase()
                if (ext !in BOOK_EXTENSIONS) continue
                val canonical = p.toString()
                if (canonical in knownMediaItemPaths || canonical in knownUnmatchedPaths) continue
                raw += p to ext
            }
        }
        val candidates = raw.sortedWith(
            compareBy({ extensionPriority(it.second) }, { it.first.toString() })
        )

        var processed = 0
        for ((path, ext) in candidates) {
            if (!running.get()) break
            if (processed >= MAX_FILES_PER_CYCLE) {
                log.info("BookScannerAgent: hit MAX_FILES_PER_CYCLE={}, will continue next cycle",
                    MAX_FILES_PER_CYCLE)
                break
            }
            val canonical = path.toString()
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

        if (processed > 0) {
            log.info("BookScannerAgent: processed {} new ebook file(s) under {}", processed, rootStr)
        }
    }

    /** Lower is earlier. EPUBs (metadata-rich) before PDFs (metadata-poor). */
    internal fun extensionPriority(ext: String): Int = when (ext) {
        "epub" -> 0
        "pdf" -> 1
        else -> 2
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
                    is OpenLibraryResult.Success -> "?"
                })
        }

        // Before giving up to admin resolution, check if there's an
        // already-catalogued sibling file with the same directory and
        // basename — a common pattern is Foundation.epub alongside
        // Foundation.pdf. The EPUB is metadata-rich and gets catalogued
        // first (see extensionPriority in scanOnce), so the PDF can
        // piggyback on its Title without needing its own ISBN.
        val siblingTitle = findSiblingTitle(file)
        if (siblingTitle != null) {
            BookIngestionService.linkDigitalEditionToTitle(
                filePath = filePath,
                fileFormat = format,
                title = siblingTitle
            )
            log.info("Ebook {} auto-linked as sibling edition of title '{}'",
                filePath, siblingTitle.name)
            return
        }

        // Nothing to link against — stage for admin resolution.
        stageUnmatched(file, format, metadata)
    }

    /**
     * Returns the [Title] of an already-catalogued [MediaItem] whose
     * `file_path` is in the same directory as [file] with the same basename
     * (case-insensitive) and a different extension. Null if no such sibling
     * exists.
     */
    internal fun findSiblingTitle(file: File): Title? {
        val dirPath = file.parentFile?.absolutePath ?: return null
        val baseName = file.nameWithoutExtension
        val ext = file.extension.lowercase()

        val sibling = MediaItem.findAll()
            .asSequence()
            .mapNotNull { mi -> mi.file_path?.let { mi to File(it) } }
            .firstOrNull { (_, siblingFile) ->
                siblingFile.parentFile?.absolutePath.equals(dirPath, ignoreCase = true) &&
                    siblingFile.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                    siblingFile.extension.lowercase() != ext
            } ?: return null

        val (mediaItem, _) = sibling
        val titleId = MediaItemTitle.findAll()
            .firstOrNull { it.media_item_id == mediaItem.id }
            ?.title_id ?: return null
        return Title.findById(titleId)
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
