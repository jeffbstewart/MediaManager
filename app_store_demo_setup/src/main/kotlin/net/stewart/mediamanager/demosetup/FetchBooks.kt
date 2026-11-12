package net.stewart.mediamanager.demosetup

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.notExists

/**
 * Standard Ebooks → `<demoMedia>/books/`. Each row in
 * `fixtures/books.tsv` carries an `se_id` like
 * `jane-austen/pride-and-prejudice` (or, for translated works,
 * three segments: `jules-verne/<book>/<translator>`). The download
 * URL is derived deterministically:
 *
 *   path  = ebooks/<se_id>/downloads/<se_id_with_slashes_to_underscores>.epub
 *   url   = https://standardebooks.org/<path>?source=download
 *
 * The `?source=download` query parameter is mandatory — without it
 * Standard Ebooks serves an HTML "Your download has started"
 * interstitial page (it uses meta-refresh to land the real EPUB on
 * a browser, which doesn't help a non-browser fetcher). Tested:
 * including the parameter returns `Content-Type: application/
 * epub+zip` and the binary EPUB.
 *
 * Drop-and-scan: the server's BookScannerAgent walks
 * `books_root_path` for `.epub` and `.pdf` files, so once the EPUB
 * lands the regular scan picks it up. Standard Ebooks ship with
 * cover art baked into the OPF + consistent metadata, so no
 * post-processing needed.
 *
 * Default parallelism is 6 — Standard Ebooks is lightly trafficked
 * and the EPUBs are small (200–800 KB), so the bottleneck is per-
 * request latency rather than throughput.
 *
 * Idempotent — rows whose target already exists at non-zero size
 * are skipped. Loud failures: any non-2xx HTTP throws.
 */
internal object FetchBooks {

    const val DEFAULT_PARALLELISM = 6

    fun run(demoMedia: Path, parallelism: Int = DEFAULT_PARALLELISM) {
        val fixtures = Tsv.locateFixtures().resolve("books.tsv")
        if (fixtures.notExists()) {
            error("missing fixtures file: $fixtures")
        }
        val rows = Tsv.read(fixtures)
        val destRoot = demoMedia.resolve("books").also { Files.createDirectories(it) }

        val skipped = AtomicInteger(0)
        val fetched = AtomicInteger(0)

        println("Fetching ${rows.size} book(s) with parallelism=$parallelism")

        Concurrency.parallel(rows, parallelism) { row ->
            val seId   = row["se_id"].orEmpty()
            val target = row["target_file"].orEmpty()
            require(seId.isNotEmpty() && target.isNotEmpty()) {
                "incomplete books.tsv row: $row"
            }

            val dest = destRoot.resolve(target)
            if (dest.exists() && Files.size(dest) > 0L) {
                logTagged(seId, "SKIP $target")
                skipped.incrementAndGet()
                return@parallel
            }

            val downloadName = seId.replace('/', '_') + ".epub"
            val url = "https://standardebooks.org/ebooks/$seId/downloads/$downloadName?source=download"
            logTagged(seId, "FETCH $url")
            Http.download(url, dest)
            logTagged(seId, "OK $target (${Files.size(dest)} bytes)")
            fetched.incrementAndGet()
        }

        println()
        println("Done — ${rows.size} row(s); ${fetched.get()} downloaded, ${skipped.get()} already present.")
    }
}
