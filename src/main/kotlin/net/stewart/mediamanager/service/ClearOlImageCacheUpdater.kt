package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * One-time sweep of `data/image-proxy-cache/ol/` after we appended
 * `?default=false` to every Open Library cover URL. The URL change moved
 * every entry to a new SHA-256 hash, so files cached before this point
 * are now orphaned — many of them are the 1x1 "no cover" placeholder OL
 * used to serve silently. Deleting them frees the bytes and guarantees
 * cache consistency with current ImageProxyService behaviour.
 *
 * No-op on a fresh install: the directory won't exist yet.
 */
class ClearOlImageCacheUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(ClearOlImageCacheUpdater::class.java)

    override val name = "clear_ol_image_proxy_cache_v1"
    override val version = 1

    override fun run() {
        val root: Path = Path.of("data", "image-proxy-cache", "ol")
        if (!Files.isDirectory(root)) {
            log.info("OL image proxy cache absent, nothing to clear")
            return
        }

        var deleted = 0
        Files.walk(root).use { stream ->
            // Depth-first so children are removed before their parents.
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                if (p == root) return@forEach
                try {
                    Files.delete(p)
                    if (Files.isRegularFile(p)) deleted++
                } catch (e: Exception) {
                    log.warn("Failed to delete stale OL cache entry {}: {}", p, e.message)
                }
            }
        }
        log.info("Cleared {} stale OL image-proxy cache file(s) under {}", deleted, root)
    }
}
