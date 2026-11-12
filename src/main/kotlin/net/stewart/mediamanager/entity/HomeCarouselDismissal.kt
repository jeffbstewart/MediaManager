package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

/**
 * Strong-typed enum for [HomeCarouselDismissal.carousel]. Mirrors the
 * proto `HomeCarousel` enum and the H2 column's allowed values.
 * Strings are stored at the JDBC layer, but no string ever crosses
 * a service boundary — RPCs use the proto enum, the service layer
 * uses [HomeCarouselType], and the only place the string appears is
 * inside this entity's column mapping.
 */
enum class HomeCarouselType {
    RECENTLY_ADDED_ALBUMS,
    RECENTLY_ADDED_BOOKS,
    RECENTLY_ADDED_MOVIES;
}

/**
 * Per-user dismissal of a single title pinned to a home-feed
 * carousel. Used by the iOS Music landing page (and the future
 * book / movie equivalents) to let the user clear specific entries
 * off a "Recently Added" row without affecting other users or the
 * carousel itself.
 */
@Table("home_carousel_dismissal")
data class HomeCarouselDismissal(
    override var id: Long? = null,
    var user_id: Long = 0,
    var title_id: Long = 0,
    var carousel: String = HomeCarouselType.RECENTLY_ADDED_ALBUMS.name,
    var dismissed_at: LocalDateTime? = null,
) : KEntity<Long> {
    companion object : Dao<HomeCarouselDismissal, Long>(HomeCarouselDismissal::class.java)
}
