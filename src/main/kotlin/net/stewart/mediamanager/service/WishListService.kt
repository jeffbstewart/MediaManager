package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/** Aggregated media wish across all users for the admin purchase-wishes view. */
data class MediaWishAggregate(
    val tmdbId: Int,
    val tmdbTitle: String,
    val tmdbMediaType: String?,
    val tmdbPosterPath: String?,
    val tmdbReleaseYear: Int?,
    val tmdbPopularity: Double?,
    val seasonNumber: Int?,
    val voteCount: Int,
    val voters: List<String>   // display names
) {
    /** Returns a type-safe TMDB key, or null if media type is missing. */
    fun tmdbKey(): TmdbId? = TmdbId.of(tmdbId, tmdbMediaType)

    /** Display title including season if present. */
    val displayTitle: String
        get() = if (seasonNumber != null) "$tmdbTitle — Season $seasonNumber" else tmdbTitle
}

object WishListService {
    private val log = LoggerFactory.getLogger(WishListService::class.java)

    /** Returns the current user's ID, or null if not authenticated. */
    private fun currentUserId(): Long? = AuthService.getCurrentUser()?.id

    fun addMediaWish(
        tmdbId: TmdbId,
        title: String,
        posterPath: String?,
        releaseYear: Int?,
        popularity: Double?
    ): WishListItem? {
        val userId = currentUserId() ?: return null
        if (hasActiveMediaWish(tmdbId)) return null

        val wish = WishListItem(
            user_id = userId,
            wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = tmdbId.id,
            tmdb_title = title,
            tmdb_media_type = tmdbId.typeString,
            tmdb_poster_path = posterPath,
            tmdb_release_year = releaseYear,
            tmdb_popularity = popularity,
            created_at = LocalDateTime.now()
        )
        wish.save()
        log.info("Media wish added: user={} tmdb_id={} title=\"{}\"", userId, tmdbId, title)
        return wish
    }

    fun addTranscodeWish(titleId: Long): WishListItem? {
        val userId = currentUserId() ?: return null
        if (hasActiveTranscodeWish(titleId)) return null

        val wish = WishListItem(
            user_id = userId,
            wish_type = WishType.TRANSCODE.name,
            status = WishStatus.ACTIVE.name,
            title_id = titleId,
            created_at = LocalDateTime.now()
        )
        wish.save()
        log.info("Transcode wish added: user={} title_id={}", userId, titleId)
        return wish
    }

    fun removeTranscodeWish(titleId: Long) {
        val userId = currentUserId() ?: return
        val wish = WishListItem.findAll().firstOrNull {
            it.user_id == userId &&
                it.wish_type == WishType.TRANSCODE.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.title_id == titleId
        } ?: return
        wish.delete()
        log.info("Transcode wish removed: user={} title_id={}", userId, titleId)
    }

    fun removeWish(wishId: Long) {
        val userId = currentUserId() ?: return
        val wish = WishListItem.findById(wishId) ?: return
        if (wish.user_id != userId) return
        wish.delete()
        log.info("Wish removed: id={} user={}", wishId, userId)
    }

    fun hasActiveMediaWish(tmdbId: TmdbId): Boolean {
        val userId = currentUserId() ?: return false
        return WishListItem.findAll().any {
            it.user_id == userId &&
                it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.tmdb_id == tmdbId.id &&
                it.tmdb_media_type == tmdbId.typeString
        }
    }

    fun hasActiveTranscodeWish(titleId: Long): Boolean {
        val userId = currentUserId() ?: return false
        return WishListItem.findAll().any {
            it.user_id == userId &&
                it.wish_type == WishType.TRANSCODE.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.title_id == titleId
        }
    }

    fun getActiveMediaWishes(): List<WishListItem> {
        val userId = currentUserId() ?: return emptyList()
        return WishListItem.findAll().filter {
            it.user_id == userId &&
                it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.ACTIVE.name
        }.sortedByDescending { it.created_at }
    }

    fun getActiveTranscodeWishes(): List<WishListItem> {
        val userId = currentUserId() ?: return emptyList()
        return WishListItem.findAll().filter {
            it.user_id == userId &&
                it.wish_type == WishType.TRANSCODE.name &&
                it.status == WishStatus.ACTIVE.name
        }.sortedByDescending { it.created_at }
    }

    /** Returns title IDs that any user has an active transcode wish for — used by TranscoderAgent. */
    fun getTranscodeWishedTitleIds(): Set<Long> {
        return WishListItem.findAll()
            .filter {
                it.wish_type == WishType.TRANSCODE.name &&
                    it.status == WishStatus.ACTIVE.name &&
                    it.title_id != null
            }
            .map { it.title_id!! }
            .toSet()
    }

    /** Returns title_id -> count of active transcode wishes across all users (for backlog sort). */
    fun getTranscodeWishCounts(): Map<Long, Int> {
        return WishListItem.findAll()
            .filter {
                it.wish_type == WishType.TRANSCODE.name &&
                    it.status == WishStatus.ACTIVE.name &&
                    it.title_id != null
            }
            .groupBy { it.title_id!! }
            .mapValues { it.value.size }
    }

    /** Marks all ACTIVE media wishes matching this tmdb_id + media_type as FULFILLED. */
    fun fulfillMediaWishes(tmdbId: TmdbId) {
        val wishes = WishListItem.findAll().filter {
            it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.tmdb_id == tmdbId.id &&
                it.tmdb_media_type == tmdbId.typeString
        }
        if (wishes.isEmpty()) return

        val now = LocalDateTime.now()
        for (wish in wishes) {
            wish.status = WishStatus.FULFILLED.name
            wish.fulfilled_at = now
            wish.save()
        }
        log.info("Fulfilled {} media wish(es) for tmdb_id={}", wishes.size, tmdbId)
    }

    /** Marks all ACTIVE transcode wishes matching this title_id as FULFILLED. */
    fun fulfillTranscodeWishes(titleId: Long) {
        val wishes = WishListItem.findAll().filter {
            it.wish_type == WishType.TRANSCODE.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.title_id == titleId
        }
        if (wishes.isEmpty()) return

        val now = LocalDateTime.now()
        for (wish in wishes) {
            wish.status = WishStatus.FULFILLED.name
            wish.fulfilled_at = now
            wish.save()
        }
        log.info("Fulfilled {} transcode wish(es) for title_id={}", wishes.size, titleId)
    }

    /** Aggregates ACTIVE media wishes across all users, sorted by vote count descending. */
    fun getMediaWishVoteCounts(): List<MediaWishAggregate> {
        val activeMedia = WishListItem.findAll().filter {
            it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.tmdb_id != null
        }

        val userMap = AppUser.findAll().associateBy { it.id }

        return activeMedia
            .groupBy { Pair(it.tmdbKey()!!, it.season_number) }
            .map { (key, wishes) ->
                val (tmdbKey, seasonNumber) = key
                val first = wishes.first()
                MediaWishAggregate(
                    tmdbId = tmdbKey.id,
                    tmdbTitle = first.tmdb_title ?: "Unknown",
                    tmdbMediaType = first.tmdb_media_type,
                    tmdbPosterPath = first.tmdb_poster_path,
                    tmdbReleaseYear = first.tmdb_release_year,
                    tmdbPopularity = first.tmdb_popularity,
                    seasonNumber = seasonNumber,
                    voteCount = wishes.size,
                    voters = wishes.mapNotNull { w -> userMap[w.user_id]?.display_name }
                )
            }
            .sortedByDescending { it.voteCount }
    }

    /** Add a media wish for a specific season of a TV show. */
    fun addSeasonWish(
        tmdbId: TmdbId,
        title: String,
        posterPath: String?,
        releaseYear: Int?,
        popularity: Double?,
        seasonNumber: Int
    ): WishListItem? {
        val userId = currentUserId() ?: return null
        // Check for existing wish for same show+season
        val exists = WishListItem.findAll().any {
            it.user_id == userId &&
                it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.tmdb_id == tmdbId.id &&
                it.tmdb_media_type == tmdbId.typeString &&
                it.season_number == seasonNumber
        }
        if (exists) return null

        val wish = WishListItem(
            user_id = userId,
            wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = tmdbId.id,
            tmdb_title = title,
            tmdb_media_type = tmdbId.typeString,
            tmdb_poster_path = posterPath,
            tmdb_release_year = releaseYear,
            tmdb_popularity = popularity,
            season_number = seasonNumber,
            created_at = LocalDateTime.now()
        )
        wish.save()
        log.info("Season wish added: user={} tmdb_id={} title=\"{}\" season={}", userId, tmdbId, title, seasonNumber)
        return wish
    }

    fun userHasAnyMediaWish(): Boolean {
        val userId = currentUserId() ?: return false
        return WishListItem.findAll().any {
            it.user_id == userId && it.wish_type == WishType.MEDIA.name
        }
    }
}
