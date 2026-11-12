package net.stewart.mediamanager.demosetup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Minimal TSV reader for the curated fixture lists. Lines beginning
 * with '#' (comments) and blank lines are skipped. The first non-
 * comment line is treated as a header row and dropped — fixture
 * files document column meanings inline at the top so the header is
 * for human reference, not consumption here.
 *
 * Returns a list of `Map<columnName, cellValue>` keyed by header.
 * Cells are returned trimmed; empty cells become an empty string,
 * NOT null, since most consumers want "did the user fill this in?"
 * via `isBlank()` rather than null-checking.
 */
internal object Tsv {

    /** Read [path], dropping comments + blanks, returning rows keyed by header column name. */
    fun read(path: Path): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()
        var headers: List<String>? = null
        Files.lines(path).use { stream ->
            for (raw in stream) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cells = raw.split("\t").map { it.trim() }
                if (headers == null) {
                    headers = cells
                    continue
                }
                val row = LinkedHashMap<String, String>()
                for (i in headers!!.indices) {
                    row[headers!![i]] = cells.getOrElse(i) { "" }
                }
                rows.add(row)
            }
        }
        return rows
    }

    /**
     * Locate `app_store_demo_setup/fixtures/`. When run via
     * `./gradlew :app_store_demo_setup:run` the cwd is the repo
     * root; via `installDist` the cwd is wherever the operator
     * invoked from. Probe a few likely locations relative to cwd
     * before giving up. Throws with an actionable message if
     * neither location resolves.
     */
    fun locateFixtures(): Path {
        val candidates = listOf(
            Path.of("app_store_demo_setup", "fixtures"),
            Path.of("..", "app_store_demo_setup", "fixtures"),
            Path.of("fixtures"),
        )
        for (rel in candidates) {
            val abs = rel.toAbsolutePath().normalize()
            if (abs.exists()) return abs
        }
        error(
            "could not find app_store_demo_setup/fixtures/. Run from the " +
            "repo root (./gradlew :app_store_demo_setup:run --args=\"...\") " +
            "or set cwd to the directory containing fixtures/."
        )
    }
}
