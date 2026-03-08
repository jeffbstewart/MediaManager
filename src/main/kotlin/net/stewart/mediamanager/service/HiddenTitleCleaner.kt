package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory

/**
 * Deletes hidden titles and all their FK dependents on every startup.
 *
 * Hidden titles are created when multi-packs are expanded — the original
 * placeholder title is hidden to preserve UPC lookup history. Once hidden,
 * they serve no further purpose and can be safely removed.
 *
 * Deletion order respects FK constraints (all RESTRICT, no cascades):
 *   1. transcode        (title_id, episode_id)
 *   2. episode           (title_id)
 *   3. discovered_file   (matched_title_id — nullable, nulled out)
 *   4. title_genre       (title_id)
 *   5. media_item_title  (title_id)
 *   6. enrichment_attempt(title_id)
 *   7. title             (the hidden title itself)
 */
object HiddenTitleCleaner {
    private val log = LoggerFactory.getLogger(HiddenTitleCleaner::class.java)

    fun clean() {
        val hiddenTitles = Title.findAll().filter { it.hidden }
        if (hiddenTitles.isEmpty()) {
            log.info("No hidden titles to clean up")
            return
        }

        log.info("Cleaning {} hidden title(s)", hiddenTitles.size)

        for (title in hiddenTitles) {
            val titleId = title.id!!
            val titleName = title.name

            // 1. Delete transcodes referencing this title
            val transcodes = Transcode.findAll().filter { it.title_id == titleId }
            transcodes.forEach { it.delete() }

            // 2. Delete episodes referencing this title
            val episodes = Episode.findAll().filter { it.title_id == titleId }
            episodes.forEach { it.delete() }

            // 3. Null out discovered_file references (nullable FK)
            val discoveredFiles = DiscoveredFile.findAll().filter { it.matched_title_id == titleId }
            for (df in discoveredFiles) {
                df.matched_title_id = null
                df.matched_episode_id = null
                df.match_status = DiscoveredFileStatus.UNMATCHED.name
                df.match_method = null
                df.save()
            }

            // 4. Delete title_genre joins
            val titleGenres = TitleGenre.findAll().filter { it.title_id == titleId }
            titleGenres.forEach { it.delete() }

            // 5. Delete media_item_title joins
            val mediaItemTitles = MediaItemTitle.findAll().filter { it.title_id == titleId }
            mediaItemTitles.forEach { it.delete() }

            // 6. Delete enrichment_attempts
            val enrichmentAttempts = EnrichmentAttempt.findAll().filter { it.title_id == titleId }
            enrichmentAttempts.forEach { it.delete() }

            // 7. Delete the title itself
            title.delete()

            log.info("Deleted hidden title id={} name=\"{}\" (removed {} transcode(s), {} episode(s), " +
                    "nulled {} discovered file(s), {} genre link(s), {} media-item link(s), {} enrichment attempt(s))",
                titleId, titleName, transcodes.size, episodes.size, discoveredFiles.size,
                titleGenres.size, mediaItemTitles.size, enrichmentAttempts.size)
        }

        log.info("Hidden title cleanup complete")
    }
}
