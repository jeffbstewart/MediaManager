package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import org.slf4j.LoggerFactory

/**
 * Reactivates legacy media wishes that were previously driven by the old
 * FULFILLED status workflow. Wishlist lifecycle is now derived from title
 * ownership + NAS + playability, so visible media wishes should remain ACTIVE
 * until the user explicitly dismisses them.
 */
class RepairFulfilledWishesUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(RepairFulfilledWishesUpdater::class.java)

    override val name = "repair_fulfilled_wishes"
    override val version = 2

    override fun run() {
        val fulfilledMedia = WishListItem.findAll().filter {
            it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.FULFILLED.name
        }
        if (fulfilledMedia.isEmpty()) return

        fulfilledMedia.forEach { wish ->
            wish.status = WishStatus.ACTIVE.name
            wish.fulfilled_at = null
            wish.save()
            log.info("Reactivated fulfilled media wish: id={} title=\"{}\"", wish.id, wish.tmdb_title)
        }

        log.info("Reactivated {} fulfilled media wish(es)", fulfilledMedia.size)
    }
}
