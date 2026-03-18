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
 *   1. transcode             (title_id, episode_id)
 *   2. episode               (title_id)
 *   3. discovered_file       (matched_title_id — nullable, nulled out)
 *   4. title_genre           (title_id)
 *   5. title_tag             (title_id)
 *   6. cast_member           (title_id)
 *   7. title_season          (title_id)
 *   8. media_item_title_season (FK to media_item_title)
 *   9. media_item_title      (title_id)
 *  10. enrichment_attempt    (title_id)
 *  11. user_title_flag       (title_id)
 *  12. title_family_member   (title_id)
 *  13. wish_list_item        (title_id — nullable, nulled out)
 *  14. title                 (the hidden title itself)
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

            // 5. Delete title_tag joins
            val titleTags = TitleTag.findAll().filter { it.title_id == titleId }
            titleTags.forEach { it.delete() }

            // 6. Delete cast members
            val castMembers = CastMember.findAll().filter { it.title_id == titleId }
            castMembers.forEach { it.delete() }

            // 7. Delete media_item_title_season, then media_item_title, then title_season
            // Order matters: media_item_title_season has FKs to both media_item_title and title_season
            val mediaItemTitles = MediaItemTitle.findAll().filter { it.title_id == titleId }
            for (mit in mediaItemTitles) {
                MediaItemTitleSeason.findAll()
                    .filter { it.media_item_title_id == mit.id }
                    .forEach { it.delete() }
                mit.delete()
            }

            // Now safe to delete title_season rows — also clean any remaining
            // media_item_title_season rows that reference these seasons from other titles
            val titleSeasons = TitleSeason.findAll().filter { it.title_id == titleId }
            for (ts in titleSeasons) {
                MediaItemTitleSeason.findAll()
                    .filter { it.title_season_id == ts.id }
                    .forEach { it.delete() }
                ts.delete()
            }

            // 10. Delete enrichment_attempts
            val enrichmentAttempts = EnrichmentAttempt.findAll().filter { it.title_id == titleId }
            enrichmentAttempts.forEach { it.delete() }

            // 11. Delete user_title_flags
            val userFlags = UserTitleFlag.findAll().filter { it.title_id == titleId }
            userFlags.forEach { it.delete() }

            // 12. Delete title_family_members
            val familyMembers = TitleFamilyMember.findAll().filter { it.title_id == titleId }
            familyMembers.forEach { it.delete() }

            // 13. Null out wish_list_item references (nullable FK)
            val wishItems = WishListItem.findAll().filter { it.title_id == titleId }
            for (wi in wishItems) {
                wi.title_id = null
                wi.save()
            }

            // 14. Delete the title itself
            title.delete()

            log.info("Deleted hidden title id={} name=\"{}\"", titleId, titleName)
        }

        log.info("Hidden title cleanup complete")
    }
}
