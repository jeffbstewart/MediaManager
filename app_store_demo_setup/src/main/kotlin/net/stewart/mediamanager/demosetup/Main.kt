package net.stewart.mediamanager.demosetup

import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Demo-server seed tool — populates the `demo_media` directory used
 * by the App Store review server and screenshot pipelines, plus a
 * couple of helpers for managing the matching `demo_storage` after
 * the server has run against it.
 *
 * Subcommands:
 *   fetch-movies     <demo_media>   [--parallel=N]
 *   fetch-tv-series  <demo_media>   [--parallel=N]
 *   fetch-books      <demo_media>   [--parallel=N]
 *   fetch-albums     <demo_media>   [--parallel=N]
 *   seed-all         <demo_media>   [--parallel=N]
 *   seed-users       <demo_media>
 *   link-fixtures    <demo_media>
 *   reset            <demo_storage>
 *
 * `--parallel=N` (also `-p N`) sets the bounded worker pool for the
 * fetcher. Each fetcher has a sensible default tuned for its source:
 * movies are CPU-bound (libx264) so default 2; books and albums are
 * I/O-bound so default 6. Override either way with the flag.
 *
 * Run:
 *   ./gradlew :app_store_demo_setup:run --args="fetch-movies $DEMO_MEDIA"
 *   ./gradlew :app_store_demo_setup:run --args="fetch-movies $DEMO_MEDIA --parallel=4"
 * or after `./gradlew :app_store_demo_setup:installDist`:
 *   ./app_store_demo_setup/build/install/app_store_demo_setup/bin/app_store_demo_setup \
 *       fetch-movies $DEMO_MEDIA --parallel=4
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) { usage(); exitProcess(2) }
    val cmd = args[0]
    val (positional, flags) = parseArgs(args.drop(1))
    val parallelism = flags["parallel"]?.toIntOrNull()
        ?.also { require(it >= 1) { "--parallel must be >= 1" } }

    try {
        when (cmd) {
            "fetch-movies"    -> FetchMovies.run(requirePath(positional, "demo_media"),
                parallelism ?: FetchMovies.DEFAULT_PARALLELISM)
            "fetch-tv-series" -> FetchTvSeries.run(requirePath(positional, "demo_media"),
                parallelism ?: FetchTvSeries.DEFAULT_PARALLELISM)
            "fetch-books"     -> FetchBooks.run(requirePath(positional, "demo_media"),
                parallelism ?: FetchBooks.DEFAULT_PARALLELISM)
            "fetch-albums"    -> FetchAlbums.run(requirePath(positional, "demo_media"),
                parallelism ?: FetchAlbums.DEFAULT_PARALLELISM)
            "seed-all"        -> {
                val p = requirePath(positional, "demo_media")
                FetchMovies.run(p, parallelism ?: FetchMovies.DEFAULT_PARALLELISM)
                FetchTvSeries.run(p, parallelism ?: FetchTvSeries.DEFAULT_PARALLELISM)
                FetchBooks.run(p, parallelism ?: FetchBooks.DEFAULT_PARALLELISM)
                FetchAlbums.run(p, parallelism ?: FetchAlbums.DEFAULT_PARALLELISM)
            }
            "seed-users"    -> SeedUsers.run(requirePath(positional, "demo_media"))
            "seed-wishes"   -> SeedWishes.run(requirePath(positional, "demo_media"))
            "link-fixtures" -> LinkFixtures.run(requirePath(positional, "demo_media"))
            "reset"         -> Reset.run(requirePath(positional, "demo_storage"))
            "-h", "--help", "help" -> usage()
            else -> {
                System.err.println("ERROR: unknown subcommand '$cmd'\n")
                usage()
                exitProcess(2)
            }
        }
    } catch (e: Throwable) {
        System.err.println("ERROR: ${e.message}")
        exitProcess(1)
    }
}

private fun requirePath(args: List<String>, name: String): Path {
    if (args.isEmpty()) {
        System.err.println("ERROR: missing argument <$name>")
        exitProcess(2)
    }
    return Path.of(args[0]).toAbsolutePath().normalize()
}

/**
 * Tiny CLI parser — splits args into positional values and flags.
 * Recognized flag forms:
 *   --parallel=N        long, equals-style
 *   --parallel N        long, space-separated
 *   -p N                short, space-separated
 *
 * Unknown flags are passed through as positional so a future
 * subcommand-specific arg doesn't have to round-trip through here.
 */
private fun parseArgs(args: List<String>): Pair<List<String>, Map<String, String>> {
    val positional = mutableListOf<String>()
    val flags = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a.startsWith("--") && a.contains('=') -> {
                val eq = a.indexOf('=')
                flags[a.substring(2, eq)] = a.substring(eq + 1)
            }
            a == "--parallel" && i + 1 < args.size -> {
                flags["parallel"] = args[i + 1]; i++
            }
            a == "-p" && i + 1 < args.size -> {
                flags["parallel"] = args[i + 1]; i++
            }
            else -> positional += a
        }
        i++
    }
    return positional to flags
}

private fun usage() {
    System.err.println(
        """
        |Usage: app_store_demo_setup <subcommand> [args] [--parallel=N]
        |
        |Subcommands:
        |  fetch-movies     <demo_media>     archive.org -> ffmpeg -> movies/        (default --parallel=${FetchMovies.DEFAULT_PARALLELISM})
        |  fetch-tv-series  <demo_media>     archive.org -> ffmpeg -> movies/<show>/ (default --parallel=${FetchTvSeries.DEFAULT_PARALLELISM})
        |  fetch-books      <demo_media>     Standard Ebooks -> books/               (default --parallel=${FetchBooks.DEFAULT_PARALLELISM})
        |  fetch-albums     <demo_media>     archive.org 78rpm -> music/             (default --parallel=${FetchAlbums.DEFAULT_PARALLELISM})
        |  seed-all         <demo_media>     Run all four fetches in order
        |  seed-users       <demo_media>     Create test accounts via admin API
        |  seed-wishes      <demo_media>     Populate viewer wish list w/ mixed-status fixture
        |  link-fixtures    <demo_media>     Pin TMDB / MB / OL ids per fixture
        |  reset            <demo_storage>   Confirm-then-nuke H2 + image cache
        |
        |--parallel=N (or -p N) overrides the per-fetcher default worker count.
        |
        |See app_store_demo_setup/README.md for the end-to-end walkthrough.
        """.trimMargin()
    )
}
