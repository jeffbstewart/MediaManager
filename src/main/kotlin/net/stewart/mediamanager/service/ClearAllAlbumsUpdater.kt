package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.UnmatchedAudio
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * One-time wipe of every ALBUM in the catalog so the music scanner can
 * rediscover and re-ingest from disk with current logic (tiered MB
 * matching, embedded cover-art extraction, full parsed-identifier set
 * on unmatched rows). The FK cascades set up in V077 take care of
 * track, title_artist, track_artist, and recording_credit rows; this
 * updater only needs to remove:
 *
 *   - Every row in `unmatched_audio` (any status) so the discovery log
 *     starts empty and every file gets re-evaluated.
 *   - Every `Title` row with `media_type = ALBUM`, which cascades the
 *     dependent music tables.
 *   - Each deleted album's poster cache file, so the ALBUM/CAA art we'd
 *     previously pulled doesn't linger and mask freshly extracted
 *     embedded art.
 *
 * Safe on a fresh install: no ALBUM rows, no unmatched_audio rows, no-op.
 */
class ClearAllAlbumsUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(ClearAllAlbumsUpdater::class.java)

    override val name = "clear_all_albums_for_rescan"
    override val version = 2

    override fun run() {
        val unmatchedRows = UnmatchedAudio.findAll()
        val albums = Title.findAll().filter { it.media_type == MediaType.ALBUM.name }

        // Remove any cached poster files for the about-to-be-deleted albums
        // so a refreshed rip with different embedded art produces a fresh
        // image next time around.
        var postersDeleted = 0
        for (t in albums) {
            val cacheId = t.poster_cache_id ?: continue
            val path = PosterCacheService.resolve(cacheId) ?: continue
            try {
                Files.deleteIfExists(path)
                postersDeleted++
            } catch (e: Exception) {
                log.warn("Failed to delete poster cache {} for title {}: {}",
                    path, t.id, e.message)
            }
        }

        for (row in unmatchedRows) {
            try { row.delete() } catch (e: Exception) {
                log.warn("Failed to delete unmatched_audio id={}: {}", row.id, e.message)
            }
        }

        // media_item_title has no ON DELETE CASCADE from title — drop the
        // join rows pointing at our soon-to-be-deleted ALBUM titles first.
        // The MediaItem itself survives (a physical CD row tied to an album
        // Title is legitimate ownership history even after we clear the
        // album catalog); the join link is what blocks the Title delete.
        val albumIds = albums.mapNotNull { it.id }.toSet()
        var joinRowsDeleted = 0
        if (albumIds.isNotEmpty()) {
            for (mit in MediaItemTitle.findAll().filter { it.title_id in albumIds }) {
                try {
                    mit.delete()
                    joinRowsDeleted++
                } catch (e: Exception) {
                    log.warn("Failed to delete media_item_title id={} for title {}: {}",
                        mit.id, mit.title_id, e.message)
                }
            }
        }

        for (t in albums) {
            try { t.delete() } catch (e: Exception) {
                log.warn("Failed to delete album title id={}: {}", t.id, e.message)
            }
        }

        log.info("Cleared {} ALBUM title(s), {} unmatched_audio row(s), {} media_item_title row(s), {} poster cache file(s)",
            albums.size, unmatchedRows.size, joinRowsDeleted, postersDeleted)
    }
}
