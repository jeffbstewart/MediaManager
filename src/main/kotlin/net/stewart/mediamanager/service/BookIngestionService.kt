package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.AuthorRole
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.ExpansionStatus
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.PosterSource
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleAuthor
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Takes an [OpenLibraryBookLookup] and a scanned ISBN barcode, produces a
 * complete book catalog record: authors (created or reused), series (created
 * or reused), [Title] (deduped by Open Library Work ID), [MediaItem] for the
 * edition, and [MediaItemTitle] linking them.
 *
 * Idempotent by design: scanning a second ISBN of the same work creates a new
 * MediaItem but reuses the existing Title + Authors + Series.
 */
object BookIngestionService {

    private val log = LoggerFactory.getLogger(BookIngestionService::class.java)

    data class IngestResult(
        val mediaItem: MediaItem,
        val title: Title,
        /** True if the Title already existed and we linked a new edition to it. */
        val titleReused: Boolean
    )

    /**
     * Sentinel thrown when a caller asks us to ingest an audiobook
     * format. The iOS reader is text-only and the OL editions for
     * audiobooks routinely list the narrator in the author array
     * (which contaminated the author grid before this guard was
     * added). Audiobook support — with proper narrator modelling —
     * arrives with the audio module.
     */
    class AudiobookFormatNotSupported(format: MediaFormat) :
        IllegalStateException("Audiobook formats not yet supported: $format")

    private val SKIPPED_AUDIOBOOK_FORMATS = setOf(
        MediaFormat.AUDIOBOOK_CD,
        MediaFormat.AUDIOBOOK_DIGITAL,
    )

    /**
     * Physical-book scan path. [isbn] is the EAN-13 the user scanned. The
     * MediaItem's `media_format` falls back to [lookup.mediaFormat] and
     * then to [MediaFormat.MASS_MARKET_PAPERBACK] — a reasonable default
     * for unlabeled physical editions.
     */
    fun ingest(isbn: String, lookup: OpenLibraryBookLookup, clock: Clock = SystemClock): IngestResult =
        ingestInternal(
            isbn = isbn,
            filePath = null,
            fileFormat = null,
            lookup = lookup,
            clock = clock
        )

    /**
     * Digital-edition ingestion path (M4). Called by the NAS book scanner when
     * an .epub / .pdf file has a resolvable ISBN. [filePath] is stored on the
     * MediaItem and [fileFormat] overrides the format derived from OL (which
     * would otherwise be a physical hint like "Paperback").
     */
    fun ingestDigital(
        filePath: String,
        fileFormat: MediaFormat,
        isbn: String?,
        lookup: OpenLibraryBookLookup,
        clock: Clock = SystemClock
    ): IngestResult {
        if (fileFormat in SKIPPED_AUDIOBOOK_FORMATS) {
            throw AudiobookFormatNotSupported(fileFormat)
        }
        return ingestInternal(
            isbn = isbn,
            filePath = filePath,
            fileFormat = fileFormat,
            lookup = lookup,
            clock = clock
        )
    }

    /**
     * Registers a digital edition [filePath] as a new [MediaItem] linked to an
     * already-known [title], skipping the Open Library round-trip entirely.
     * Used by the NAS scanner to auto-link a PDF that sits next to an already-
     * catalogued EPUB of the same work (matching directory + basename). See
     * [net.stewart.mediamanager.service.BookScannerAgent.findSibling].
     *
     * Does **not** touch author / series records — those were established when
     * the sibling was first ingested, and we're only adding a format row.
     */
    fun linkDigitalEditionToTitle(
        filePath: String,
        fileFormat: MediaFormat,
        title: Title,
        clock: Clock = SystemClock
    ): IngestResult {
        if (fileFormat in SKIPPED_AUDIOBOOK_FORMATS) {
            throw AudiobookFormatNotSupported(fileFormat)
        }
        val now = clock.now()
        val mediaItem = MediaItem(
            media_format = fileFormat.name,
            title_count = 1,
            expansion_status = ExpansionStatus.SINGLE.name,
            product_name = title.name,
            file_path = filePath,
            created_at = now,
            updated_at = now
        )
        mediaItem.save()
        MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!
        ).save()
        log.info("Sibling edition linked: filePath={} format={} title='{}'",
            filePath, fileFormat.name, title.name)
        return IngestResult(mediaItem, title, titleReused = true)
    }

    private fun ingestInternal(
        isbn: String?,
        filePath: String?,
        fileFormat: MediaFormat?,
        lookup: OpenLibraryBookLookup,
        clock: Clock
    ): IngestResult {
        val now = clock.now()

        val authors = lookup.authors.map { upsertAuthor(it, now) }
        val series = lookup.series.firstOrNull()?.let { ref ->
            upsertSeries(ref.name, authors.firstOrNull()?.id, now)
        }

        val (title, reused) = upsertTitle(lookup, series, now)

        if (!reused) {
            authors.forEachIndexed { index, author ->
                // OL `work.authors` is the only source we trust for AUTHOR
                // links — see OpenLibraryService.parse. Translators and
                // illustrators (TitleAuthor.role != AUTHOR) come from
                // edition.contributors and are wired in once the book-detail
                // credits row needs them.
                TitleAuthor(
                    title_id = title.id!!,
                    author_id = author.id!!,
                    author_order = index,
                    role = AuthorRole.AUTHOR.name,
                ).save()
            }
            SearchIndexService.onTitleChanged(title.id!!)
        }

        // series poster auto-fill on first creation
        val posterIsbn = isbn ?: lookup.isbn
        if (series != null && series.poster_path == null && series.poster_source == PosterSource.AUTO.name
            && posterIsbn != null) {
            series.poster_path = "isbn/$posterIsbn"
            series.updated_at = now
            series.save()
        }

        val resolvedFormat = fileFormat?.name
            ?: lookup.mediaFormat
            ?: MediaFormat.MASS_MARKET_PAPERBACK.name

        val mediaItem = MediaItem(
            upc = isbn,
            media_format = resolvedFormat,
            title_count = 1,
            expansion_status = ExpansionStatus.SINGLE.name,
            upc_lookup_json = lookup.rawJson,
            product_name = lookup.workTitle,
            file_path = filePath,
            created_at = now,
            updated_at = now
        )
        mediaItem.save()

        MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!
        ).save()

        if (isbn != null) {
            OwnershipPhotoService.resolveOrphans(isbn, mediaItem.id!!)
        }

        // Fulfill any active wishes for this work (across all users).
        WishListService.fulfillBookWishes(lookup.openLibraryWorkId)

        log.info("Book ingested: ISBN={} filePath={} workId={} title='{}' titleReused={}",
            isbn, filePath, lookup.openLibraryWorkId, lookup.workTitle, reused)

        return IngestResult(mediaItem, title, reused)
    }

    /**
     * Rebuilds the AUTHOR-role TitleAuthor links for an existing
     * [title]. Used by the [net.stewart.mediamanager.service.RebuildBookAuthorsUpdater]
     * one-shot migration after the wipe step. Wipes existing AUTHOR
     * rows for this title and re-creates them from [lookup.authors];
     * leaves TRANSLATOR / ILLUSTRATOR rows alone (none today, future-
     * proofing the call shape).
     *
     * Returns the number of AUTHOR rows written. Caller is responsible
     * for upserting series / mediaitems if needed.
     */
    fun rebuildAuthorLinks(title: Title, lookup: OpenLibraryBookLookup, clock: Clock = SystemClock): Int {
        val now = clock.now()
        // Drop existing AUTHOR rows for this title; keep other roles.
        TitleAuthor.findAll()
            .filter { it.title_id == title.id && it.role == AuthorRole.AUTHOR.name }
            .forEach { it.delete() }

        val authors = lookup.authors.map { upsertAuthor(it, now) }
        authors.forEachIndexed { index, author ->
            TitleAuthor(
                title_id = title.id!!,
                author_id = author.id!!,
                author_order = index,
                role = AuthorRole.AUTHOR.name,
            ).save()
        }
        return authors.size
    }

    private fun upsertAuthor(ol: OpenLibraryAuthor, now: LocalDateTime): Author {
        val existing = Author.findAll().firstOrNull {
            it.open_library_author_id == ol.openLibraryAuthorId
        }
        if (existing != null) return existing

        val new = Author(
            name = ol.name,
            sort_name = authorSortName(ol.name),
            open_library_author_id = ol.openLibraryAuthorId,
            created_at = now,
            updated_at = now
        )
        new.save()
        return new
    }

    private fun upsertSeries(name: String, primaryAuthorId: Long?, now: LocalDateTime): BookSeries {
        val existing = BookSeries.findAll().firstOrNull {
            it.name.equals(name, ignoreCase = true)
        }
        if (existing != null) return existing

        val new = BookSeries(
            name = name,
            author_id = primaryAuthorId,
            poster_source = PosterSource.AUTO.name,
            created_at = now,
            updated_at = now
        )
        new.save()
        return new
    }

    /**
     * Reuses an existing BOOK title with the same [OpenLibraryBookLookup.openLibraryWorkId];
     * otherwise creates one.
     */
    private fun upsertTitle(
        lookup: OpenLibraryBookLookup,
        series: BookSeries?,
        now: LocalDateTime
    ): Pair<Title, Boolean> {
        val existing = Title.findAll().firstOrNull {
            it.media_type == MediaType.BOOK.name &&
                it.open_library_work_id == lookup.openLibraryWorkId
        }
        if (existing != null) return existing to true

        val title = Title(
            name = lookup.workTitle,
            media_type = MediaType.BOOK.name,
            raw_upc_title = lookup.workTitle,
            release_year = lookup.editionYear,
            first_publication_year = lookup.firstPublicationYear,
            description = lookup.description,
            poster_path = lookup.isbn?.let { "isbn/$it" },
            sort_name = titleSortName(lookup.workTitle),
            // Books don't go through TMDB enrichment; they're complete after scan.
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            open_library_work_id = lookup.openLibraryWorkId,
            book_series_id = series?.id,
            series_number = lookup.series.firstOrNull()?.number,
            page_count = lookup.pageCount,
            created_at = now,
            updated_at = now
        )
        title.save()
        return title to false
    }

    internal fun authorSortName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return trimmed
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 2) return trimmed
        val last = parts.last()
        val first = parts.dropLast(1).joinToString(" ")
        return "$last, $first"
    }

    internal fun titleSortName(name: String): String {
        val lower = name.lowercase().trim()
        return when {
            lower.startsWith("the ") -> lower.removePrefix("the ").trim()
            lower.startsWith("a ") -> lower.removePrefix("a ").trim()
            lower.startsWith("an ") -> lower.removePrefix("an ").trim()
            else -> lower
        }
    }
}
