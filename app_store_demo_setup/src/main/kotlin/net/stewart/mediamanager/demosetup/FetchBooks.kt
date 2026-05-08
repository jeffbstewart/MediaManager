package net.stewart.mediamanager.demosetup

import java.nio.file.Path

/**
 * Standard Ebooks → `<demoMedia>/books/`. Each row in
 * `fixtures/books.tsv` resolves to
 * `https://standardebooks.org/ebooks/<author>/<book>/downloads/
 * <author>_<book>.epub`.
 *
 * Drop-and-scan: BookScannerAgent walks `books_root_path` for
 * `.epub` and `.pdf` files, so once the EPUB is on disk the
 * server's normal scan picks it up. No transformation needed —
 * Standard Ebooks ship with cover art baked in and consistent
 * metadata.
 */
internal object FetchBooks {
    fun run(demoMedia: Path) {
        TODO("not yet implemented — wire fetchers row-by-row from books.tsv " +
            "after the FetchMovies pattern stabilizes")
    }
}
