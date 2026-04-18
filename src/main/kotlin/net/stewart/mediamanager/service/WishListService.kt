package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaItemTitleSeason
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import org.slf4j.LoggerFactory
import java.io.File
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
    val voters: List<String>,   // display names
    val acquisitionStatus: String? = null,  // current title_season status, if title exists
    val lifecycleStage: WishLifecycleStage = WishLifecycleStage.WISHED_FOR,
    val titleId: Long? = null
) {
    /** Returns a type-safe TMDB key, or null if media type is missing. */
    fun tmdbKey(): TmdbId? = TmdbId.of(tmdbId, tmdbMediaType)

    /** Display title including season if present. */
    val displayTitle: String
        get() = if (seasonNumber != null) "$tmdbTitle — Season $seasonNumber" else tmdbTitle
}

enum class WishLifecycleStage {
    WISHED_FOR,
    NOT_FEASIBLE,
    WONT_ORDER,
    NEEDS_ASSISTANCE,
    ORDERED,
    IN_HOUSE_PENDING_NAS,
    ON_NAS_PENDING_DESKTOP,
    READY_TO_WATCH
}

data class UserMediaWishSummary(
    val wish: WishListItem,
    val voteCount: Int,
    val voters: List<String>,
    val acquisitionStatus: String?,
    val lifecycleStage: WishLifecycleStage,
    val titleId: Long?
)

fun WishLifecycleStage.displayLabel(): String = when (this) {
    WishLifecycleStage.WISHED_FOR -> "Wished for"
    WishLifecycleStage.NOT_FEASIBLE -> "Not feasible"
    WishLifecycleStage.WONT_ORDER -> "Won't order"
    WishLifecycleStage.NEEDS_ASSISTANCE -> "Needs assistance"
    WishLifecycleStage.ORDERED -> "Ordered"
    WishLifecycleStage.IN_HOUSE_PENDING_NAS -> "In house, pending NAS"
    WishLifecycleStage.ON_NAS_PENDING_DESKTOP -> "On NAS, pending desktop"
    WishLifecycleStage.READY_TO_WATCH -> "Ready to watch"
}

object WishListService {
    private val log = LoggerFactory.getLogger(WishListService::class.java)

    private data class WishResolutionContext(
        val titlesByTmdb: Map<Pair<Int, String>, Title>,
        val seasonsByTitle: Map<Long, Map<Int, TitleSeason>>,
        val titleSeasonsById: Map<Long, TitleSeason>,
        val mediaItemTitlesByTitle: Map<Long, List<MediaItemTitle>>,
        val mediaItemTitleSeasonsByJoin: Map<Long, Set<Long>>,
        val transcodesByTitle: Map<Long, List<Transcode>>,
        val episodesById: Map<Long, Episode>,
        val nasRoot: String?
    )

    private data class ResolvedWishState(
        val titleId: Long?,
        val acquisitionStatus: String?,
        val lifecycleStage: WishLifecycleStage
    )

    private fun isVisibleMediaWishStatus(status: String): Boolean {
        return status != WishStatus.CANCELLED.name && status != WishStatus.DISMISSED.name
    }

    private fun sameWishSeason(left: Int?, right: Int?): Boolean = (left ?: 0) == (right ?: 0)

    private fun loadResolutionContext(): WishResolutionContext {
        val titlesByTmdb = Title.findAll()
            .filter { it.tmdb_id != null && !it.media_type.isNullOrBlank() }
            .associateBy { Pair(it.tmdb_id!!, it.media_type) }

        val titleSeasons = TitleSeason.findAll()
        val seasonsByTitle = titleSeasons
            .groupBy { it.title_id }
            .mapValues { (_, seasons) -> seasons.associateBy { it.season_number } }
        val titleSeasonsById = titleSeasons.associateBy { it.id!! }

        val mediaItemTitles = MediaItemTitle.findAll()
        val mediaItemTitlesByTitle = mediaItemTitles.groupBy { it.title_id }
        val mediaItemTitleSeasonsByJoin = MediaItemTitleSeason.findAll()
            .groupBy { it.media_item_title_id }
            .mapValues { (_, rows) -> rows.map { it.title_season_id }.toSet() }

        val transcodesByTitle = Transcode.findAll()
            .filter { it.file_path != null }
            .groupBy { it.title_id }
        val episodesById = Episode.findAll().associateBy { it.id!! }

        return WishResolutionContext(
            titlesByTmdb = titlesByTmdb,
            seasonsByTitle = seasonsByTitle,
            titleSeasonsById = titleSeasonsById,
            mediaItemTitlesByTitle = mediaItemTitlesByTitle,
            mediaItemTitleSeasonsByJoin = mediaItemTitleSeasonsByJoin,
            transcodesByTitle = transcodesByTitle,
            episodesById = episodesById,
            nasRoot = TranscoderAgent.getNasRoot()
        )
    }

    private fun resolveWishState(tmdbKey: TmdbId?, seasonNumber: Int?, context: WishResolutionContext): ResolvedWishState {
        if (tmdbKey == null) {
            return ResolvedWishState(null, null, WishLifecycleStage.WISHED_FOR)
        }

        val title = context.titlesByTmdb[Pair(tmdbKey.id, tmdbKey.typeString)]
        val titleId = title?.id
        val normalizedSeason = seasonNumber ?: 0
        val acquisitionStatus = titleId?.let { context.seasonsByTitle[it]?.get(normalizedSeason)?.acquisition_status }

        val hasPlayable = titleId?.let { hasPlayableContent(title, normalizedSeason, context) } ?: false
        val hasNasSource = titleId?.let { hasLinkedNasSource(title, normalizedSeason, context) } ?: false
        val hasPhysicalOwnership = titleId?.let { hasPhysicalOwnership(title, normalizedSeason, context) } ?: false

        val lifecycleStage = when {
            hasPlayable -> WishLifecycleStage.READY_TO_WATCH
            hasNasSource -> WishLifecycleStage.ON_NAS_PENDING_DESKTOP
            hasPhysicalOwnership || acquisitionStatus == AcquisitionStatus.OWNED.name ->
                WishLifecycleStage.IN_HOUSE_PENDING_NAS
            acquisitionStatus == AcquisitionStatus.ORDERED.name -> WishLifecycleStage.ORDERED
            acquisitionStatus == AcquisitionStatus.NOT_AVAILABLE.name -> WishLifecycleStage.NOT_FEASIBLE
            acquisitionStatus == AcquisitionStatus.REJECTED.name -> WishLifecycleStage.WONT_ORDER
            acquisitionStatus == AcquisitionStatus.NEEDS_ASSISTANCE.name -> WishLifecycleStage.NEEDS_ASSISTANCE
            else -> WishLifecycleStage.WISHED_FOR
        }

        return ResolvedWishState(titleId, acquisitionStatus, lifecycleStage)
    }

    private fun hasPhysicalOwnership(title: Title?, seasonNumber: Int, context: WishResolutionContext): Boolean {
        val titleId = title?.id ?: return false
        val joins = context.mediaItemTitlesByTitle[titleId].orEmpty()
        if (joins.isEmpty()) return false

        if (title.media_type == MediaType.MOVIE.name || seasonNumber == 0) {
            return joins.isNotEmpty()
        }

        return joins.any { join ->
            val structuredMatches = context.mediaItemTitleSeasonsByJoin[join.id]
                .orEmpty()
                .mapNotNull { context.titleSeasonsById[it] }
                .any { it.title_id == titleId && it.season_number == seasonNumber }
            if (structuredMatches) {
                true
            } else {
                val parsed = join.seasons?.let { MissingSeasonService.parseSeasonText(it) }.orEmpty()
                seasonNumber in parsed
            }
        }
    }

    private fun hasLinkedNasSource(title: Title?, seasonNumber: Int, context: WishResolutionContext): Boolean {
        return matchingTranscodes(title, seasonNumber, context).any { !it.file_path.isNullOrBlank() }
    }

    private fun hasPlayableContent(title: Title?, seasonNumber: Int, context: WishResolutionContext): Boolean {
        return matchingTranscodes(title, seasonNumber, context).any { transcode ->
            val filePath = transcode.file_path ?: return@any false
            if (!File(filePath).exists()) return@any false
            if (!TranscoderAgent.needsTranscoding(filePath)) return@any true
            val nasRoot = context.nasRoot ?: return@any false
            TranscoderAgent.isTranscoded(nasRoot, filePath)
        }
    }

    private fun matchingTranscodes(title: Title?, seasonNumber: Int, context: WishResolutionContext): List<Transcode> {
        val titleId = title?.id ?: return emptyList()
        val all = context.transcodesByTitle[titleId].orEmpty()
        if (title.media_type != MediaType.TV.name || seasonNumber == 0) return all

        return all.filter { tc ->
            val episodeId = tc.episode_id ?: return@filter false
            context.episodesById[episodeId]?.season_number == seasonNumber
        }
    }

    /**
     * Looks up the acquisition status for a wish's title+season.
     * Returns null if the title doesn't exist in our DB yet.
     */
    fun getAcquisitionStatus(wish: WishListItem): String? {
        val state = resolveWishState(wish, loadResolutionContext())
        return state.acquisitionStatus
    }

    fun getLifecycleStage(wish: WishListItem): WishLifecycleStage {
        val state = resolveWishState(wish, loadResolutionContext())
        return state.lifecycleStage
    }

    fun getResolvedTitleId(wish: WishListItem): Long? {
        val state = resolveWishState(wish, loadResolutionContext())
        return state.titleId
    }

    private fun resolveWishState(wish: WishListItem, context: WishResolutionContext): ResolvedWishState {
        return resolveWishState(wish.tmdbKey(), wish.season_number, context)
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

    /**
     * Legacy hook kept for older call sites. Media wish lifecycle is now derived
     * from title ownership / NAS / playability instead of mutating wish status.
     * If a stale media wish is still marked FULFILLED, reactivate it so the new
     * lifecycle can be shown until the user explicitly dismisses it.
     */
    fun fulfillMediaWishes(tmdbId: TmdbId) {
        val wishes = WishListItem.findAll().filter {
            it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.FULFILLED.name &&
                it.tmdb_id == tmdbId.id &&
                it.tmdb_media_type == tmdbId.typeString
        }
        if (wishes.isEmpty()) return

        wishes.forEach { wish ->
            wish.status = WishStatus.ACTIVE.name
            wish.fulfilled_at = null
            wish.save()
        }
        log.info("Reactivated {} legacy fulfilled media wish(es) for tmdb_id={}", wishes.size, tmdbId)
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

    /** Aggregates visible media wishes across all users, sorted by vote count descending. */
    fun getMediaWishVoteCounts(): List<MediaWishAggregate> {
        val visibleMedia = WishListItem.findAll().filter {
            it.wish_type == WishType.MEDIA.name &&
                isVisibleMediaWishStatus(it.status) &&
                it.tmdb_id != null
        }
        if (visibleMedia.isEmpty()) return emptyList()

        val userMap = AppUser.findAll().associateBy { it.id }
        val context = loadResolutionContext()

        return visibleMedia
            .groupBy { Pair(it.tmdbKey()!!, it.season_number) }
            .map { (key, wishes) ->
                val (tmdbKey, seasonNumber) = key
                val first = wishes.first()
                val state = resolveWishState(tmdbKey, seasonNumber, context)

                MediaWishAggregate(
                    tmdbId = tmdbKey.id,
                    tmdbTitle = first.tmdb_title ?: "Unknown",
                    tmdbMediaType = first.tmdb_media_type,
                    tmdbPosterPath = first.tmdb_poster_path,
                    tmdbReleaseYear = first.tmdb_release_year,
                    tmdbPopularity = first.tmdb_popularity,
                    seasonNumber = seasonNumber,
                    voteCount = wishes.size,
                    voters = wishes.mapNotNull { w -> userMap[w.user_id]?.display_name },
                    acquisitionStatus = state.acquisitionStatus,
                    lifecycleStage = state.lifecycleStage,
                    titleId = state.titleId
                )
            }
            .sortedByDescending { it.voteCount }
    }

    /**
     * Sets the acquisition status for a wished title+season. Creates the Title and
     * TitleSeason records if they don't exist yet (common when admin orders something
     * that hasn't been scanned via barcode).
     */
    fun setAcquisitionStatus(agg: MediaWishAggregate, status: AcquisitionStatus) {
        val tmdbKey = agg.tmdbKey() ?: return

        // Find or create the title
        var title = Title.findAll().firstOrNull {
            it.tmdb_id == tmdbKey.id && it.media_type == tmdbKey.typeString
        }
        if (title == null) {
            title = Title(
                name = agg.tmdbTitle,
                media_type = tmdbKey.typeString,
                tmdb_id = tmdbKey.id,
                release_year = agg.tmdbReleaseYear,
                poster_path = agg.tmdbPosterPath,
                enrichment_status = "REASSIGNMENT_REQUESTED",
                created_at = LocalDateTime.now(),
                updated_at = LocalDateTime.now()
            )
            title.save()
            log.info("Created title '{}' (tmdb_id={}) from wish aggregate", title.name, tmdbKey)
        }

        // Find or create the title_season
        val seasonNum = agg.seasonNumber ?: 0
        var season = TitleSeason.findAll().firstOrNull {
            it.title_id == title.id && it.season_number == seasonNum
        }
        if (season == null) {
            season = TitleSeason(
                title_id = title.id!!,
                season_number = seasonNum
            )
        }
        season.acquisition_status = status.name
        season.save()

        log.info("Set acquisition status {} for '{}' season {} (title_season_id={})",
            status, title.name, seasonNum, season.id)
    }

    fun syncPhysicalOwnership(titleId: Long) {
        val title = Title.findById(titleId) ?: return
        val joins = MediaItemTitle.findAll().filter { it.title_id == titleId }
        if (joins.isEmpty()) return

        if (title.media_type == MediaType.MOVIE.name) {
            var movieSeason = TitleSeason.findAll().firstOrNull {
                it.title_id == titleId && it.season_number == 0
            }
            if (movieSeason == null) {
                movieSeason = TitleSeason(
                    title_id = titleId,
                    season_number = 0,
                    acquisition_status = AcquisitionStatus.OWNED.name
                )
                movieSeason.save()
            } else if (movieSeason.acquisition_status != AcquisitionStatus.OWNED.name) {
                movieSeason.acquisition_status = AcquisitionStatus.OWNED.name
                movieSeason.save()
            }

            joins.forEach { join ->
                val exists = MediaItemTitleSeason.findAll().any {
                    it.media_item_title_id == join.id && it.title_season_id == movieSeason.id
                }
                if (!exists) {
                    MediaItemTitleSeason(
                        media_item_title_id = join.id!!,
                        title_season_id = movieSeason.id!!
                    ).save()
                }
            }
            return
        }

        joins.forEach { join ->
            if (!join.seasons.isNullOrBlank()) {
                MissingSeasonService.syncStructuredSeasons(join.id!!, titleId, join.seasons)
            }
        }
    }

    fun getRipPriorityCounts(): Map<Long, Int> {
        return combinePriorityCounts(
            lifecycleCounts = getLifecyclePriorityCounts(setOf(WishLifecycleStage.IN_HOUSE_PENDING_NAS)),
            explicitCounts = getTranscodeWishCounts()
        )
    }

    fun getDesktopTranscodePriorityCounts(): Map<Long, Int> {
        return combinePriorityCounts(
            lifecycleCounts = getLifecyclePriorityCounts(setOf(WishLifecycleStage.ON_NAS_PENDING_DESKTOP)),
            explicitCounts = getTranscodeWishCounts()
        )
    }

    private fun combinePriorityCounts(
        lifecycleCounts: Map<Long, Int>,
        explicitCounts: Map<Long, Int>
    ): Map<Long, Int> {
        return (lifecycleCounts.keys + explicitCounts.keys).associateWith { titleId ->
            (lifecycleCounts[titleId] ?: 0) + (explicitCounts[titleId] ?: 0)
        }.filterValues { it > 0 }
    }

    private fun getLifecyclePriorityCounts(targetStages: Set<WishLifecycleStage>): Map<Long, Int> {
        val visibleMedia = WishListItem.findAll().filter {
            it.wish_type == WishType.MEDIA.name && isVisibleMediaWishStatus(it.status) && it.tmdb_id != null
        }
        if (visibleMedia.isEmpty()) return emptyMap()

        val context = loadResolutionContext()
        return visibleMedia
            .groupBy { Pair(it.tmdbKey()!!, it.season_number) }
            .mapNotNull { (key, wishes) ->
                val (tmdbKey, seasonNumber) = key
                val state = resolveWishState(tmdbKey, seasonNumber, context)
                val titleId = state.titleId
                if (titleId == null || state.lifecycleStage !in targetStages) null
                else titleId to wishes.size
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, counts) -> counts.sum() }
    }

    // --- API methods (explicit userId, no VaadinSession dependency) ---

    fun getVisibleMediaWishesForUser(userId: Long): List<WishListItem> {
        return WishListItem.findAll().filter {
            it.user_id == userId &&
                it.wish_type == WishType.MEDIA.name &&
                isVisibleMediaWishStatus(it.status)
        }.sortedByDescending { it.created_at }
    }

    fun getVisibleMediaWishSummariesForUser(userId: Long): List<UserMediaWishSummary> {
        val userWishes = getVisibleMediaWishesForUser(userId)
        if (userWishes.isEmpty()) return emptyList()

        val allVisible = WishListItem.findAll().filter {
            it.wish_type == WishType.MEDIA.name &&
                isVisibleMediaWishStatus(it.status) &&
                it.tmdb_id != null
        }
        val votesByKey = allVisible.groupBy { Pair(it.tmdbKey()!!, it.season_number) }
        val userMap = AppUser.findAll().associateBy { it.id }
        val context = loadResolutionContext()

        return userWishes.mapNotNull { wish ->
            val tmdbKey = wish.tmdbKey() ?: return@mapNotNull null
            val grouped = votesByKey[Pair(tmdbKey, wish.season_number)].orEmpty()
            val state = resolveWishState(wish, context)
            UserMediaWishSummary(
                wish = wish,
                voteCount = grouped.size,
                voters = grouped.mapNotNull { vote -> userMap[vote.user_id]?.display_name ?: userMap[vote.user_id]?.username },
                acquisitionStatus = state.acquisitionStatus,
                lifecycleStage = state.lifecycleStage,
                titleId = state.titleId
            )
        }.sortedByDescending { it.wish.created_at }
    }

    fun getReadyToWatchWishCountForUser(userId: Long): Int {
        return getVisibleMediaWishSummariesForUser(userId)
            .count { it.lifecycleStage == WishLifecycleStage.READY_TO_WATCH }
    }

    fun addMediaWishForUser(
        userId: Long,
        tmdbId: TmdbId,
        title: String,
        posterPath: String?,
        releaseYear: Int?,
        popularity: Double?,
        seasonNumber: Int? = null
    ): WishListItem? {
        val exists = WishListItem.findAll().any {
            it.user_id == userId &&
                it.wish_type == WishType.MEDIA.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.tmdb_id == tmdbId.id &&
                it.tmdb_media_type == tmdbId.typeString &&
                sameWishSeason(it.season_number, seasonNumber)
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
        log.info("API media wish added: user={} tmdb_id={} title=\"{}\"", userId, tmdbId, title)
        return wish
    }

    // --- ForUser overloads for transcode wishes (API context, no VaadinSession) ---

    fun addTranscodeWishForUser(userId: Long, titleId: Long): WishListItem? {
        if (hasActiveTranscodeWishForUser(userId, titleId)) return null
        val wish = WishListItem(
            user_id = userId,
            wish_type = WishType.TRANSCODE.name,
            status = WishStatus.ACTIVE.name,
            title_id = titleId,
            created_at = LocalDateTime.now()
        )
        wish.save()
        log.info("API transcode wish added: user={} title_id={}", userId, titleId)
        return wish
    }

    fun removeTranscodeWishForUser(userId: Long, titleId: Long): Boolean {
        val wish = WishListItem.findAll().firstOrNull {
            it.user_id == userId &&
                it.wish_type == WishType.TRANSCODE.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.title_id == titleId
        } ?: return false
        wish.delete()
        log.info("API transcode wish removed: user={} title_id={}", userId, titleId)
        return true
    }

    fun hasActiveTranscodeWishForUser(userId: Long, titleId: Long): Boolean {
        return WishListItem.findAll().any {
            it.user_id == userId &&
                it.wish_type == WishType.TRANSCODE.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.title_id == titleId
        }
    }

    fun getActiveTranscodeWishesForUser(userId: Long): List<WishListItem> {
        return WishListItem.findAll().filter {
            it.user_id == userId &&
                it.wish_type == WishType.TRANSCODE.name &&
                it.status == WishStatus.ACTIVE.name
        }.sortedByDescending { it.created_at }
    }

    fun cancelWishForUser(wishId: Long, userId: Long): Boolean {
        val wish = WishListItem.findById(wishId) ?: return false
        if (wish.user_id != userId) return false
        if (wish.status != WishStatus.ACTIVE.name) return false
        wish.status = WishStatus.CANCELLED.name
        wish.save()
        log.info("API wish cancelled: id={} user={}", wishId, userId)
        return true
    }

    // --- Book wishes (M3). Keyed on open_library_work_id. See docs/BOOKS.md. ---

    data class BookWishInput(
        val openLibraryWorkId: String,
        val title: String,
        val author: String?,
        val coverIsbn: String?,
        val seriesId: Long?,
        val seriesNumber: java.math.BigDecimal?
    )

    /**
     * Idempotent add. If an ACTIVE wish exists for this (user, work), returns
     * it unchanged. If a CANCELLED or FULFILLED wish exists, resurrect it to
     * ACTIVE rather than inserting a duplicate — matches the unique index on
     * `(user_id, open_library_work_id)`.
     */
    fun addBookWishForUser(userId: Long, input: BookWishInput): WishListItem {
        val existing = findBookWish(userId, input.openLibraryWorkId)
        if (existing != null) {
            val needsResurrect = existing.status != WishStatus.ACTIVE.name
            if (needsResurrect) {
                existing.status = WishStatus.ACTIVE.name
                existing.fulfilled_at = null
            }
            // Refresh display fields from the latest input — OL may have
            // updated the canonical title or cover since the wish was created.
            existing.book_title = input.title
            existing.book_author = input.author
            existing.book_cover_isbn = input.coverIsbn
            existing.book_series_id = input.seriesId
            existing.book_series_number = input.seriesNumber
            existing.save()
            if (needsResurrect) {
                log.info("Book wish resurrected: user={} work={}", userId, input.openLibraryWorkId)
            }
            return existing
        }

        val wish = WishListItem(
            user_id = userId,
            wish_type = WishType.BOOK.name,
            status = WishStatus.ACTIVE.name,
            open_library_work_id = input.openLibraryWorkId,
            book_title = input.title,
            book_author = input.author,
            book_cover_isbn = input.coverIsbn,
            book_series_id = input.seriesId,
            book_series_number = input.seriesNumber,
            created_at = LocalDateTime.now()
        )
        wish.save()
        log.info("Book wish added: user={} work={} title=\"{}\"",
            userId, input.openLibraryWorkId, input.title)
        return wish
    }

    fun removeBookWishForUser(userId: Long, openLibraryWorkId: String): Boolean {
        val wish = findBookWish(userId, openLibraryWorkId) ?: return false
        if (wish.status != WishStatus.ACTIVE.name) return false
        wish.status = WishStatus.CANCELLED.name
        wish.save()
        log.info("Book wish cancelled: user={} work={}", userId, openLibraryWorkId)
        return true
    }

    fun getActiveBookWishesForUser(userId: Long): List<WishListItem> =
        WishListItem.findAll()
            .filter {
                it.user_id == userId &&
                    it.wish_type == WishType.BOOK.name &&
                    it.status == WishStatus.ACTIVE.name
            }
            .sortedByDescending { it.created_at }

    /** Fast membership check for AuthorScreen / SeriesScreen rendering. */
    fun activeBookWishWorkIdsForUser(userId: Long): Set<String> =
        WishListItem.findAll()
            .asSequence()
            .filter {
                it.user_id == userId &&
                    it.wish_type == WishType.BOOK.name &&
                    it.status == WishStatus.ACTIVE.name
            }
            .mapNotNull { it.open_library_work_id }
            .toSet()

    /**
     * On book scan, flip any ACTIVE BOOK wish for this work across all users to
     * FULFILLED. Mirrors [fulfillTranscodeWishes] for the movie side. Called by
     * [BookIngestionService.ingest] after a title is created/reused.
     */
    fun fulfillBookWishes(openLibraryWorkId: String) {
        val wishes = WishListItem.findAll().filter {
            it.wish_type == WishType.BOOK.name &&
                it.status == WishStatus.ACTIVE.name &&
                it.open_library_work_id == openLibraryWorkId
        }
        if (wishes.isEmpty()) return

        val now = LocalDateTime.now()
        for (wish in wishes) {
            wish.status = WishStatus.FULFILLED.name
            wish.fulfilled_at = now
            wish.save()
        }
        log.info("Fulfilled {} book wish(es) for work {}", wishes.size, openLibraryWorkId)
    }

    private fun findBookWish(userId: Long, openLibraryWorkId: String): WishListItem? =
        WishListItem.findAll().firstOrNull {
            it.user_id == userId &&
                it.wish_type == WishType.BOOK.name &&
                it.open_library_work_id == openLibraryWorkId
        }

}
