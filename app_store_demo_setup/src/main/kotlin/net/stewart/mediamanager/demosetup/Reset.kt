package net.stewart.mediamanager.demosetup

import java.nio.file.Path

/**
 * Plan-A reset: with the demo server stopped, nuke the H2 DB and
 * image / transcode caches so the next run starts clean. Leaves
 * `demo_media` alone — re-running the seed pipeline against a fresh
 * `demo_storage` re-ingests the still-on-disk fetches without re-
 * downloading.
 *
 * Workflow:
 *   1. Verify the server isn't holding a lock on the H2 file
 *      (refuse to nuke a live database — common foot-gun).
 *   2. Confirm with the operator before deleting (interactive y/N
 *      prompt; --yes to skip).
 *   3. Delete the H2 file + cache dirs.
 *
 * --full <demo_storage> <demo_media> also wipes the media library.
 * Slow to repopulate (~30 min cold-start), so `--full` is rarely
 * the right move — usually a stale DB is the problem and the media
 * is fine.
 */
internal object Reset {
    fun run(demoStorage: Path) {
        TODO("not yet implemented — needs the H2-lock probe + interactive " +
            "y/N prompt + the --full path")
    }
}
