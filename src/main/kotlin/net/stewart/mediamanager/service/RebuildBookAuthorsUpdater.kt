package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.AuthorRole
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleAuthor
import org.slf4j.LoggerFactory
import com.github.vokorm.findAll

/**
 * One-shot destructive migration that fixes the audiobook-narrator
 * contamination of the BOOK author graph.
 *
 * Background: prior to V096 the title_author table had no role column
 * and BookIngestionService populated authors from
 * `work.authors ?: edition.authors`. For audiobook editions OL
 * routinely lists the narrator in `edition.authors`, so we ended up
 * with TitleAuthor rows attributing books to narrators (Mel Foster,
 * Emily Durante, …). The iOS author grid surfaced them as if they
 * had written the books.
 *
 * The fix lands in three pieces and this updater is the third:
 *
 *  1. V096 adds `title_author.role` (default `AUTHOR`).
 *  2. [BookIngestionService] now pulls AUTHORs only from
 *     `work.authors` (no edition fallback) and tags each row.
 *  3. This updater wipes the contaminated state and re-runs the OL
 *     lookup against every remaining BOOK title to rebuild clean
 *     AUTHOR-role links.
 *
 * Steps:
 *
 *  - Delete every audiobook-format MediaItem (AUDIOBOOK_CD,
 *    AUDIOBOOK_DIGITAL) — the iOS reader is text-only and these
 *    were the source of the contamination. MediaItemTitle rows for
 *    them go too.
 *  - Drop any BOOK Title that no longer has any MediaItem after
 *    the audiobook deletion.
 *  - Wipe ALL TitleAuthor rows for BOOK titles — the existing rows
 *    can't be told apart by role since they were inserted before
 *    V096 and all default to AUTHOR.
 *  - Delete Author rows that have no remaining TitleAuthor links
 *    (Author rows are book-only — there's no Movie/TV use of
 *    Author).
 *  - For each remaining BOOK Title with an
 *    [Title.open_library_work_id], refetch the OL work record and
 *    rebuild AUTHOR-role TitleAuthor rows.
 *
 * The OL refetch loop runs on a background thread so the server
 * starts up immediately even if the library has many books; the
 * iOS author grid will populate progressively as the rebuild
 * finishes. With OL's ~1 rps soft limit a few hundred books take a
 * few minutes.
 *
 * Bumping [version] re-runs the whole sequence — useful if we
 * later refine the role-extraction logic and want to roll the
 * library forward again.
 */
class RebuildBookAuthorsUpdater(
    private val openLibrary: OpenLibraryService = OpenLibraryHttpService(),
) : SchemaUpdater {

    private val log = LoggerFactory.getLogger(RebuildBookAuthorsUpdater::class.java)

    override val name = "rebuild_book_authors"
    // v4: drop `personal_name` from the "fleshed-out OL record"
    // heuristic — it's auto-populated from `name` even on narrators
    // and other contributors. Dick Hill (audiobook narrator) survived
    // the v3 filter because his record had `personal_name` set. v4
    // requires a real bio / birth_date / alternate_names to count an
    // author as fleshed-out.
    override val version = 4

    override fun run() {
        wipeAudiobookMediaItems()
        wipeBookTitleAuthorAndOrphans()
        // OL refetch is slow (rate-limited; one HTTP per book). Run on
        // a background thread so the SchemaUpdaterRunner returns and
        // Bootstrap.init() proceeds without holding the server out
        // of service for minutes. Books reappear with proper authors
        // as the loop progresses.
        val bookTitles = Title.findAll()
            .filter { it.media_type == MediaType.BOOK.name && it.open_library_work_id != null }
        log.info("Spawning background OL refetch for {} BOOK title(s)", bookTitles.size)
        val thread = Thread({ refetchAndRebuild(bookTitles) }, "rebuild-book-authors")
        thread.isDaemon = true
        thread.start()
    }

    private fun wipeAudiobookMediaItems() {
        val audiobookFormats = setOf(
            MediaFormat.AUDIOBOOK_CD.name,
            MediaFormat.AUDIOBOOK_DIGITAL.name,
        )
        val audiobookItems = MediaItem.findAll()
            .filter { it.media_format in audiobookFormats }
        if (audiobookItems.isEmpty()) {
            log.info("No audiobook MediaItem rows to delete")
            return
        }
        val ids = audiobookItems.mapNotNull { it.id }.toSet()
        // Cascade delete MediaItemTitle links first to keep the FK
        // consistent (jdbi-orm doesn't auto-cascade).
        MediaItemTitle.findAll()
            .filter { it.media_item_id in ids }
            .forEach { it.delete() }
        audiobookItems.forEach { it.delete() }
        log.info("Deleted {} audiobook MediaItem(s) and their MediaItemTitle links", ids.size)
    }

    private fun wipeBookTitleAuthorAndOrphans() {
        // Drop BOOK titles that lost their last MediaItem after the
        // audiobook wipe — those rows were exclusively audiobook-
        // backed and have nothing to read.
        val titlesById = Title.findAll().associateBy { it.id }
        val mediaItemTitles = MediaItemTitle.findAll()
        val titlesWithMedia = mediaItemTitles.map { it.title_id }.toSet()
        val orphanBookTitleIds = titlesById.values
            .filter { it.media_type == MediaType.BOOK.name && it.id !in titlesWithMedia }
            .mapNotNull { it.id }
            .toSet()
        if (orphanBookTitleIds.isNotEmpty()) {
            log.info("Deleting {} orphan BOOK title(s) (no MediaItem after audiobook wipe)",
                orphanBookTitleIds.size)
            Title.findAll()
                .filter { it.id in orphanBookTitleIds }
                .forEach { it.delete() }
        }

        // Wipe ALL TitleAuthor rows for any remaining BOOK title.
        // Rows can't be sorted by trustworthy-ness because everything
        // pre-V096 defaults to AUTHOR — the OL refetch below rebuilds
        // them from scratch.
        val remainingBookIds = Title.findAll()
            .filter { it.media_type == MediaType.BOOK.name }
            .mapNotNull { it.id }
            .toSet()
        val bookLinks = TitleAuthor.findAll()
            .filter { it.title_id in remainingBookIds }
        if (bookLinks.isNotEmpty()) {
            log.info("Wiping {} TitleAuthor link(s) on BOOK titles", bookLinks.size)
            bookLinks.forEach { it.delete() }
        }

        // Delete Authors that no longer have any TitleAuthor pointing
        // at them. The Author table is book-only — no movies/TV/audio
        // path creates Author rows — so this safely cleans up the
        // narrators et al. that we had pretending to be authors.
        val referencedAuthorIds = TitleAuthor.findAll().map { it.author_id }.toSet()
        val orphans = Author.findAll().filter { it.id !in referencedAuthorIds }
        if (orphans.isNotEmpty()) {
            log.info("Deleting {} orphan Author row(s)", orphans.size)
            orphans.forEach { it.delete() }
        }
    }

    private fun refetchAndRebuild(titles: List<Title>) {
        var rebuilt = 0
        var skipped = 0
        var failed = 0
        for (title in titles) {
            val workId = title.open_library_work_id ?: continue
            // OpenLibraryService doesn't expose a direct lookup-by-work
            // ID variant; iterate over the title's MediaItem ISBNs and
            // try each. For digital editions where the MediaItem has no
            // ISBN, skip — those titles need an admin to relink.
            val mediaItemIds = MediaItemTitle.findAll()
                .filter { it.title_id == title.id }
                .map { it.media_item_id }
                .toSet()
            val isbn = MediaItem.findAll()
                .firstOrNull { it.id in mediaItemIds && !it.upc.isNullOrBlank() }
                ?.upc
            if (isbn == null) {
                log.warn("Skipping title {} '{}' — no ISBN on any MediaItem", title.id, title.name)
                skipped++
                continue
            }
            try {
                when (val result = openLibrary.lookupByIsbn(isbn)) {
                    is OpenLibraryResult.Success -> {
                        val written = BookIngestionService.rebuildAuthorLinks(title, result.book)
                        log.info("Rebuilt {} AUTHOR link(s) for title {} '{}'",
                            written, title.id, title.name)
                        rebuilt++
                    }
                    is OpenLibraryResult.NotFound -> {
                        log.warn("OL NotFound for ISBN {} (title {} '{}')",
                            isbn, title.id, title.name)
                        skipped++
                    }
                    is OpenLibraryResult.Error -> {
                        log.warn("OL error for ISBN {} (title {} '{}'): {}",
                            isbn, title.id, title.name, result.message)
                        failed++
                    }
                }
            } catch (e: Exception) {
                log.error("Refetch failed for title {} '{}': {}", title.id, title.name, e.message, e)
                failed++
            }
        }
        log.info("Book-author rebuild complete: rebuilt={} skipped={} failed={}",
            rebuilt, skipped, failed)
    }
}
