package net.stewart.mediamanager.demosetup

import java.nio.file.Path

/**
 * Pin TMDB / MusicBrainz / OpenLibrary identifiers per fixture row
 * via the admin API, after the scanner has ingested the media. This
 * is what makes the demo deterministic — the auto-match is mostly
 * accurate but the App Store screenshot pipeline needs identical
 * results across runs, so we explicitly snap each title to the
 * curated id from the TSV.
 *
 * Inputs: tmdb_id from movies.tsv, mb_release_id from albums.tsv,
 * ol_work_id from books.tsv (where filled in).
 *
 * Outputs: the admin endpoints that the manual scan-detail page
 * fires when an admin clicks "assign TMDB id" or "use this MB
 * release". Driving these from a script is the same shape — log in,
 * POST per fixture row.
 */
internal object LinkFixtures {
    fun run(demoMedia: Path) {
        TODO("not yet implemented — design after seeing which admin " +
            "endpoints the SPA uses for assign-tmdb / link-mb-release / " +
            "link-ol-work")
    }
}
