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
 *   fetch-movies   <demo_media>     archive.org -> ffmpeg -> movies/
 *   fetch-books    <demo_media>     Standard Ebooks -> books/
 *   fetch-albums   <demo_media>     Musopen / archive.org 78s -> music/
 *   seed-all       <demo_media>     Run the three fetches in order.
 *   seed-users     <demo_media>     Create test accounts via admin API.
 *   link-fixtures  <demo_media>     Pin TMDB / MB / OL ids per fixture.
 *   reset          <demo_storage>   Confirm-then-nuke H2 + image cache.
 *
 * Run:
 *   ./gradlew :app_store_demo_setup:run --args="fetch-movies $DEMO_MEDIA"
 * or after `./gradlew :app_store_demo_setup:installDist`:
 *   ./app_store_demo_setup/build/install/app_store_demo_setup/bin/app_store_demo_setup fetch-movies $DEMO_MEDIA
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) { usage(); exitProcess(2) }
    val cmd = args[0]
    val rest = args.drop(1).toTypedArray()
    try {
        when (cmd) {
            "fetch-movies"  -> FetchMovies.run(requirePath(rest, "demo_media"))
            "fetch-books"   -> FetchBooks.run(requirePath(rest, "demo_media"))
            "fetch-albums"  -> FetchAlbums.run(requirePath(rest, "demo_media"))
            "seed-all"      -> {
                val p = requirePath(rest, "demo_media")
                FetchMovies.run(p)
                FetchBooks.run(p)
                FetchAlbums.run(p)
            }
            "seed-users"    -> SeedUsers.run(requirePath(rest, "demo_media"))
            "link-fixtures" -> LinkFixtures.run(requirePath(rest, "demo_media"))
            "reset"         -> Reset.run(requirePath(rest, "demo_storage"))
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

private fun requirePath(args: Array<String>, name: String): Path {
    if (args.isEmpty()) {
        System.err.println("ERROR: missing argument <$name>")
        exitProcess(2)
    }
    return Path.of(args[0]).toAbsolutePath().normalize()
}

private fun usage() {
    System.err.println(
        """
        |Usage: app_store_demo_setup <subcommand> [args]
        |
        |Subcommands:
        |  fetch-movies   <demo_media>     archive.org -> ffmpeg -> movies/
        |  fetch-books    <demo_media>     Standard Ebooks -> books/
        |  fetch-albums   <demo_media>     Musopen / archive.org 78s -> music/
        |  seed-all       <demo_media>     Run the three fetches in order
        |  seed-users     <demo_media>     Create test accounts via admin API
        |  link-fixtures  <demo_media>     Pin TMDB / MB / OL ids per fixture
        |  reset          <demo_storage>   Confirm-then-nuke H2 + image cache
        |
        |See app_store_demo_setup/README.md for the end-to-end walkthrough.
        """.trimMargin()
    )
}
