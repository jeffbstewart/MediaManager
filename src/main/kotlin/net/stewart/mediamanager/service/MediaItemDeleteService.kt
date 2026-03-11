package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory

/**
 * Deletes a MediaItem and cascades to all FK dependents.
 *
 * Title deletion is conditional: only deletes a Title if no other MediaItem references it.
 *
 * Cascade order (respects FK constraints, all RESTRICT unless noted):
 *   For each orphaned Title:
 *     1. transcode_lease       (transcode_id — via transcode)
 *     2. forbrowser_probe      (transcode_id — via transcode, streams cascade)
 *     3. transcode             (title_id) — PlaybackProgress cascades via ON DELETE CASCADE
 *     4. episode               (title_id)
 *     5. discovered_file       (matched_title_id — nullable, nulled out)
 *     6. title_genre           (title_id)
 *     7. cast_member           (title_id)
 *     8. title_tag             (title_id)
 *     9. user_title_flag       (title_id)
 *    10. wish_list_item        (title_id — nullable, nulled out)
 *    11. title_season          (title_id — via media_item_title_season first)
 *    12. title_family_member   (title_id)
 *    13. enrichment_attempt    (title_id)
 *    14. search index removal
 *    15. title                 (the title itself)
 *   Then for the MediaItem:
 *     1. media_item_title_season (media_item_title_id — via media_item_title)
 *     2. media_item_title      (media_item_id)
 *     3. ownership_photo       (media_item_id — files + DB)
 *     4. barcode_scan          (media_item_id — nullable, nulled out)
 *     5. amazon_order          (linked_media_item_id — nullable, nulled out)
 *     6. media_item            (the item itself)
 */
object MediaItemDeleteService {
    private val log = LoggerFactory.getLogger(MediaItemDeleteService::class.java)

    fun delete(mediaItemId: Long) {
        val item = MediaItem.findById(mediaItemId) ?: return

        log.info("Deleting media item id={} upc={} product_name='{}'", mediaItemId, item.upc, item.product_name)

        // Find title links and determine which titles would be orphaned
        val mediaItemTitles = MediaItemTitle.findAll().filter { it.media_item_id == mediaItemId }
        val allLinks = MediaItemTitle.findAll()

        for (mit in mediaItemTitles) {
            val titleId = mit.title_id
            val otherLinks = allLinks.count { it.title_id == titleId && it.media_item_id != mediaItemId }

            // Delete MediaItemTitleSeason for this join
            val mitSeasons = MediaItemTitleSeason.findAll().filter { it.media_item_title_id == mit.id }
            mitSeasons.forEach { it.delete() }

            if (otherLinks == 0) {
                // Title is orphaned — delete it and all dependents
                deleteTitle(titleId)
            }
        }

        // Delete media_item_title joins
        mediaItemTitles.forEach { it.delete() }

        // Delete ownership photos (files + DB)
        val photos = OwnershipPhotoService.findAllForItem(mediaItemId, item.upc)
        for (photo in photos) {
            OwnershipPhotoService.delete(photo.id!!)
        }

        // Null out barcode_scan references
        val scans = BarcodeScan.findAll().filter { it.media_item_id == mediaItemId }
        for (scan in scans) {
            scan.media_item_id = null
            scan.save()
        }

        // Unlink amazon_order references
        val orders = AmazonOrder.findAll().filter { it.linked_media_item_id == mediaItemId }
        for (order in orders) {
            order.linked_media_item_id = null
            order.linked_at = null
            order.save()
        }

        // Delete the media item
        item.delete()

        log.info("Media item deleted: id={} (removed {} title link(s), {} photo(s), nulled {} scan(s), unlinked {} order(s))",
            mediaItemId, mediaItemTitles.size, photos.size, scans.size, orders.size)
    }

    private fun deleteTitle(titleId: Long) {
        val title = Title.findById(titleId) ?: return

        log.info("Deleting orphaned title id={} name='{}'", titleId, title.name)

        // Transcodes and their dependents
        val transcodes = Transcode.findAll().filter { it.title_id == titleId }
        for (transcode in transcodes) {
            val tid = transcode.id!!
            // TranscodeLease (no cascade)
            TranscodeLease.findAll().filter { it.transcode_id == tid }.forEach { it.delete() }
            // ForBrowserProbe (streams cascade via ON DELETE CASCADE)
            ForBrowserProbe.findAll().filter { it.transcode_id == tid }.forEach { it.delete() }
            // PlaybackProgress cascades via ON DELETE CASCADE on transcode
            transcode.delete()
        }

        // Episodes
        Episode.findAll().filter { it.title_id == titleId }.forEach { it.delete() }

        // Null out discovered_file references
        DiscoveredFile.findAll().filter { it.matched_title_id == titleId }.forEach { df ->
            df.matched_title_id = null
            df.matched_episode_id = null
            df.match_status = DiscoveredFileStatus.UNMATCHED.name
            df.match_method = null
            df.save()
        }

        // Title joins
        TitleGenre.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        CastMember.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        TitleTag.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        UserTitleFlag.findAll().filter { it.title_id == titleId }.forEach { it.delete() }

        // Wish list items — null out title_id (nullable FK)
        WishListItem.findAll().filter { it.title_id == titleId }.forEach { wish ->
            wish.title_id = null
            wish.save()
        }

        // TitleSeason and MediaItemTitleSeason
        val titleSeasons = TitleSeason.findAll().filter { it.title_id == titleId }
        for (ts in titleSeasons) {
            MediaItemTitleSeason.findAll().filter { it.title_season_id == ts.id }.forEach { it.delete() }
            ts.delete()
        }

        // TitleFamilyMember
        TitleFamilyMember.findAll().filter { it.title_id == titleId }.forEach { it.delete() }

        // EnrichmentAttempt
        EnrichmentAttempt.findAll().filter { it.title_id == titleId }.forEach { it.delete() }

        // Search index
        SearchIndexService.onTitleDeleted(titleId)

        // Title itself
        title.delete()
    }
}
