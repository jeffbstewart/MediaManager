package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import org.slf4j.LoggerFactory

/**
 * One-time sweep of UNMATCHED rows in `unmatched_audio` that were staged
 * before [AudioTagReader] learned to read UPC / ISRC / catalog number /
 * label tags. Those rows have null `parsed_upc` / `parsed_isrc` /
 * `parsed_catalog_number` / `parsed_label`, so [MusicScannerAgent.reprocessUnmatched]
 * has no identifiers to retry with. Deleting the rows lets the next scan
 * cycle re-discover each file via the filesystem walk and re-stage it
 * with the full tag set.
 *
 * LINKED / IGNORED rows are preserved — those are admin-decision history.
 */
class ClearUnmatchedAudioUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(ClearUnmatchedAudioUpdater::class.java)

    override val name = "clear_unmatched_audio_for_rescan"
    override val version = 1

    override fun run() {
        val rows = UnmatchedAudio.findAll()
            .filter { it.match_status == UnmatchedAudioStatus.UNMATCHED.name }
        if (rows.isEmpty()) {
            log.info("No UNMATCHED audio rows to clear")
            return
        }
        for (row in rows) {
            try {
                row.delete()
            } catch (e: Exception) {
                log.warn("Failed to delete unmatched_audio id={} path={}: {}",
                    row.id, row.file_path, e.message)
            }
        }
        log.info("Cleared {} UNMATCHED audio row(s) for rescan with current tag reader",
            rows.size)
    }
}
