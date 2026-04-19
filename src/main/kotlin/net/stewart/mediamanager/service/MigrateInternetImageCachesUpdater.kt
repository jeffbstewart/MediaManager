package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Phase-2 migration for the internet-image side of the cache migration
 * (see docs/IMAGE_CACHE_MIGRATION.md). Deletes the seven legacy
 * per-service cache directories outright. The bytes they hold are
 * re-fetchable from upstream on the next request; the new
 * [InternetImageStore] layout takes over from there.
 *
 * This is destructive and irreversible. It's safe because:
 *  - every image handled here has an upstream source that can rebuild it;
 *  - the DB still carries the identifying `*_cache_id` / entity ids;
 *  - the new store sits at a separate root (`data/internet-images/`)
 *    so there's no path overlap with the directories being deleted.
 *
 * First-party caches (`data/ownership-photos/`, `data/local-images/`)
 * are explicitly NOT touched by this updater. Those migrate under
 * phase 3 with a non-destructive dual-read policy.
 *
 * Bump [version] if we need to re-sweep (e.g., a partial delete on a
 * previous run left orphan directories behind).
 */
class MigrateInternetImageCachesUpdater : SchemaUpdater {
    override val name: String = "migrate_internet_image_caches_to_unified_store"
    override val version: Int = 1

    private val log = LoggerFactory.getLogger(MigrateInternetImageCachesUpdater::class.java)

    /**
     * Legacy roots we're clearing out. If we add a new internet cache
     * later, it should land directly in [InternetImageStore] from day
     * one — not in a sibling directory that'd need another migration.
     */
    private val legacyRoots: List<Path> = listOf(
        Paths.get("data", "poster-cache"),
        Paths.get("data", "backdrop-cache"),
        Paths.get("data", "headshot-cache"),
        Paths.get("data", "collection-poster-cache"),
        Paths.get("data", "artist-headshot-cache"),
        Paths.get("data", "author-headshot-cache"),
        Paths.get("data", "image-proxy-cache")
    )

    override fun run() {
        var directories = 0
        var filesDeleted = 0
        for (root in legacyRoots) {
            if (!Files.exists(root)) continue
            val count = deleteTree(root)
            filesDeleted += count
            directories++
            log.info("Cleared legacy image cache {} ({} entries removed)", root, count)
        }
        log.info("Phase-2 internet-image cache migration: {} directories cleared, {} entries removed",
            directories, filesDeleted)
    }

    /**
     * Recursively deletes a directory tree. Counts regular files removed;
     * missing entries / partial trees are tolerated so a re-run is
     * idempotent.
     */
    private fun deleteTree(root: Path): Int {
        if (!Files.isDirectory(root)) {
            // Root is a file? Shouldn't happen but don't crash.
            return if (safeDelete(root)) 1 else 0
        }
        var count = 0
        Files.walk(root).use { stream ->
            // Reverse order so we delete files before their parent dirs.
            val entries = stream.toList().sortedByDescending { it.toString().length }
            for (entry in entries) {
                if (Files.isRegularFile(entry)) {
                    if (safeDelete(entry)) count++
                } else if (Files.isDirectory(entry)) {
                    safeDelete(entry) // ignore failures; may still have non-regular entries
                }
            }
        }
        return count
    }

    private fun safeDelete(path: Path): Boolean {
        return try {
            Files.deleteIfExists(path)
        } catch (e: Exception) {
            log.warn("Could not delete {}: {}", path, e.message)
            false
        }
    }
}
