package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import org.slf4j.LoggerFactory

/**
 * Reverts wishes that were incorrectly marked FULFILLED when the title
 * was enriched but not actually OWNED. This fixes a bug where
 * fulfillMediaWishes() fired unconditionally on enrichment.
 */
class RepairFulfilledWishesUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(RepairFulfilledWishesUpdater::class.java)

    override val name = "repair_fulfilled_wishes"
    override val version = 1

    override fun run() {
        val fulfilledMedia = WishListItem.findAll().filter {
            it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.FULFILLED.name
        }
        if (fulfilledMedia.isEmpty()) return

        val titlesByTmdb = Title.findAll()
            .filter { it.tmdb_id != null && it.media_type != null }
            .groupBy { Pair(it.tmdb_id!!, it.media_type!!) }
        val seasonsByTitle = TitleSeason.findAll().groupBy { it.title_id }

        var reverted = 0
        for (wish in fulfilledMedia) {
            val tmdbKey = wish.tmdbKey() ?: continue
            val title = titlesByTmdb[Pair(tmdbKey.id, tmdbKey.typeString)]?.firstOrNull()

            val isActuallyOwned = if (title != null) {
                val seasonNum = wish.season_number ?: 0
                seasonsByTitle[title.id]?.firstOrNull { it.season_number == seasonNum }
                    ?.acquisition_status == AcquisitionStatus.OWNED.name
            } else false

            if (!isActuallyOwned) {
                wish.status = WishStatus.ACTIVE.name
                wish.fulfilled_at = null
                wish.save()
                reverted++
                log.info("Reverted incorrectly fulfilled wish: id={} title=\"{}\"",
                    wish.id, wish.tmdb_title)
            }
        }

        if (reverted > 0) {
            log.info("Reverted {} incorrectly fulfilled wish(es)", reverted)
        }
    }
}
