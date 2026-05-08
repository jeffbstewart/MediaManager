package net.stewart.mediamanager.demosetup

import java.nio.file.Path

/**
 * Music fetchers — two source paths in the same TSV (`source` column
 * dispatches): Musopen (PD classical) and archive.org's `78rpm`
 * collection (1920s jazz / blues / tango). Output layout
 * `<demoMedia>/music/<Artist>/<Album>/<NN-Track>.<ext>` matches
 * MusicScannerAgent's expectations.
 *
 * BPM-mix targeting is documented in fixtures/albums.tsv — Strauss
 * waltzes for Slow / Viennese Waltz, 1920s big-band for Foxtrot,
 * 1920s tango for Argentine Tango.
 */
internal object FetchAlbums {
    fun run(demoMedia: Path) {
        TODO("not yet implemented — Musopen + archive.org 78s have " +
            "different download shapes; design after FetchMovies " +
            "and FetchBooks land")
    }
}
