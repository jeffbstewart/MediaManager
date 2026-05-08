package net.stewart.mediamanager.demosetup

import java.nio.file.Files
import java.nio.file.Path
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
 * Idempotent — rows whose target already exists at non-zero size
 * are skipped. Loud failures: any non-2xx HTTP throws.
 */
internal object FetchBooks {

    fun run(demoMedia: Path) {
        val fixtures = Tsv.locateFixtures().resolve("books.tsv")
        if (fixtures.notExists()) {
            error("missing fixtures file: $fixtures")
        }
        val rows = Tsv.read(fixtures)
        val destRoot = demoMedia.resolve("books").also { Files.createDirectories(it) }

        var processed = 0
        var skipped = 0
        var fetched = 0
        for (row in rows) {
            val seId   = row["se_id"].orEmpty()
            val target = row["target_file"].orEmpty()
            require(seId.isNotEmpty() && target.isNotEmpty()) {
                "incomplete books.tsv row: $row"
            }
            processed++

            val dest = destRoot.resolve(target)
            if (dest.exists() && Files.size(dest) > 0L) {
                println("SKIP   $seId -> $target")
                skipped++
                continue
            }

            val downloadName = seId.replace('/', '_') + ".epub"
            val url = "https://standardebooks.org/ebooks/$seId/downloads/$downloadName?source=download"
            println("FETCH  $seId")
            println("         $url")
            Http.download(url, dest)
            println("OK     $dest (${Files.size(dest)} bytes)")
            fetched++
        }

        println()
        println("Done — $processed row(s); $fetched downloaded, $skipped already present.")
    }
}
