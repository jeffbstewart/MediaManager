package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Data class for Continue Watching entries — contains everything the UI needs
 * to render a card without further DB lookups.
 */
data class ContinueWatchingItem(
    val transcodeId: Long,
    val titleId: Long,
    val titleName: String,
    val posterUrl: String?,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val updatedAt: LocalDateTime?,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeName: String? = null
) {
    /** True when this item is a TV episode rather than a movie. */
    val isEpisode: Boolean get() = seasonNumber != null

    /** Formatted episode label, e.g. "S02E05 — The One Where...". */
    val episodeLabel: String?
        get() {
            if (seasonNumber == null) return null
            val tag = "S%02dE%02d".format(seasonNumber, episodeNumber ?: 0)
            return if (episodeName != null) "$tag \u2014 $episodeName" else tag
        }
    /** Percentage watched (0.0–1.0). */
    val progressFraction: Double
        get() = if (durationSeconds > 0) (positionSeconds / durationSeconds).coerceIn(0.0, 1.0) else 0.0

    /** Human-readable time remaining, e.g. "42 min left". */
    val timeRemaining: String
        get() {
            val remaining = ((durationSeconds - positionSeconds) / 60).toInt().coerceAtLeast(1)
            return "$remaining min left"
        }

    /** Position formatted as MM:SS. */
    val formattedPosition: String
        get() {
            val totalSeconds = positionSeconds.toInt()
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return "%d:%02d".format(mins, secs)
        }
}

object PlaybackProgressService {
    private val log = LoggerFactory.getLogger(PlaybackProgressService::class.java)

    /** Returns the current Vaadin session user's ID, or null if not authenticated. */
    private fun currentUserId(): Long? = AuthService.getCurrentUser()?.id

    /**
     * Records playback progress for the current user.
     * If near the end (>= 95% or within 120s of end), deletes the record (auto-clear).
     * Sets VIEWED flag when user watches > 25%.
     */
    fun recordProgress(transcodeId: Long, positionSeconds: Double, durationSeconds: Double?) {
        val userId = currentUserId() ?: return
        recordProgressForUser(userId, transcodeId, positionSeconds, durationSeconds)
    }

    /**
     * Records playback progress for an explicit user ID (used by servlet for Roku path).
     */
    fun recordProgressForUser(userId: Long, transcodeId: Long, positionSeconds: Double, durationSeconds: Double?) {
        // Check if near end — auto-clear
        if (durationSeconds != null && durationSeconds > 0) {
            val pct = positionSeconds / durationSeconds
            val remaining = durationSeconds - positionSeconds
            if (pct >= 0.95 || remaining <= 120) {
                deleteProgressForUser(userId, transcodeId)
                log.debug("Auto-cleared progress: user={} transcode={} ({}%)", userId, transcodeId, "%.0f".format(pct * 100))
                // Still set VIEWED flag
                setViewedFlag(userId, transcodeId)
                return
            }

            // Set VIEWED flag at > 25%
            if (pct > 0.25) {
                setViewedFlag(userId, transcodeId)
            }
        }

        // Upsert
        val existing = findProgressForUser(userId, transcodeId)
        if (existing != null) {
            existing.position_seconds = positionSeconds
            if (durationSeconds != null) existing.duration_seconds = durationSeconds
            existing.updated_at = LocalDateTime.now()
            existing.save()
        } else {
            PlaybackProgress(
                user_id = userId,
                transcode_id = transcodeId,
                position_seconds = positionSeconds,
                duration_seconds = durationSeconds,
                updated_at = LocalDateTime.now()
            ).save()
        }
    }

    /** Returns saved progress for the current user and transcode, or null. */
    fun getProgress(transcodeId: Long): PlaybackProgress? {
        val userId = currentUserId() ?: return null
        return getProgressForUser(userId, transcodeId)
    }

    /** Returns saved progress for an explicit user and transcode, or null. */
    fun getProgressForUser(userId: Long, transcodeId: Long): PlaybackProgress? {
        return findProgressForUser(userId, transcodeId)
    }

    /**
     * Returns in-progress titles for the current user, sorted by most recently watched.
     * Each item contains all data needed for a Continue Watching card.
     */
    fun getContinueWatching(limit: Int = 5): List<ContinueWatchingItem> {
        val userId = currentUserId() ?: return emptyList()
        return getContinueWatchingForUser(userId, limit)
    }

    /** Returns in-progress titles for an explicit user ID. */
    fun getContinueWatchingForUser(userId: Long, limit: Int = 5): List<ContinueWatchingItem> {
        val progressList = PlaybackProgress.findAll()
            .filter { it.user_id == userId }
            .sortedByDescending { it.updated_at }
            .take(limit)

        if (progressList.isEmpty()) return emptyList()

        // Pre-load lookups
        val transcodeIds = progressList.map { it.transcode_id }.toSet()
        val transcodes = Transcode.findAll().filter { it.id in transcodeIds }.associateBy { it.id }
        val titleIds = transcodes.values.map { it.title_id }.toSet()
        val titles = Title.findAll().filter { it.id in titleIds }.associateBy { it.id }

        // Pre-load episodes for any transcodes that reference one
        val episodeIds = transcodes.values.mapNotNull { it.episode_id }.toSet()
        val episodes = if (episodeIds.isNotEmpty()) {
            Episode.findAll().filter { it.id in episodeIds }.associateBy { it.id }
        } else emptyMap()

        return progressList.mapNotNull { progress ->
            val transcode = transcodes[progress.transcode_id] ?: return@mapNotNull null
            val title = titles[transcode.title_id] ?: return@mapNotNull null
            val episode = transcode.episode_id?.let { episodes[it] }
            ContinueWatchingItem(
                transcodeId = progress.transcode_id,
                titleId = title.id!!,
                titleName = title.name,
                posterUrl = title.posterUrl(PosterSize.THUMBNAIL),
                positionSeconds = progress.position_seconds,
                durationSeconds = progress.duration_seconds ?: 0.0,
                updatedAt = progress.updated_at,
                seasonNumber = episode?.season_number,
                episodeNumber = episode?.episode_number,
                episodeName = episode?.name
            )
        }
    }

    /** Clears progress for the current user on a specific transcode. */
    fun clearProgress(transcodeId: Long) {
        val userId = currentUserId() ?: return
        deleteProgressForUser(userId, transcodeId)
    }

    /**
     * Returns a map of transcode_id → PlaybackProgress for the current user,
     * filtered to the given transcode IDs. Useful for batch lookups in views.
     */
    fun getProgressForTranscodes(transcodeIds: Set<Long>): Map<Long, PlaybackProgress> {
        val userId = currentUserId() ?: return emptyMap()
        return PlaybackProgress.findAll()
            .filter { it.user_id == userId && it.transcode_id in transcodeIds }
            .associateBy { it.transcode_id }
    }

    /**
     * Returns a map of title_id → PlaybackProgress for the current user,
     * picking the most recent progress per title. Useful for catalog view overlays.
     */
    fun getProgressByTitle(): Map<Long, PlaybackProgress> {
        val userId = currentUserId() ?: return emptyMap()
        return getProgressByTitleForUser(userId)
    }

    /** Returns a map of title_id → PlaybackProgress for an explicit user ID. */
    fun getProgressByTitleForUser(userId: Long): Map<Long, PlaybackProgress> {
        val allProgress = PlaybackProgress.findAll().filter { it.user_id == userId }
        if (allProgress.isEmpty()) return emptyMap()

        val transcodeIds = allProgress.map { it.transcode_id }.toSet()
        val transcodes = Transcode.findAll().filter { it.id in transcodeIds }
        val transcodeToTitle = transcodes.associate { it.id!! to it.title_id }

        // Group by title, pick the most recently updated progress per title
        return allProgress
            .mapNotNull { p ->
                val titleId = transcodeToTitle[p.transcode_id] ?: return@mapNotNull null
                titleId to p
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, progresses) -> progresses.maxByOrNull { it.updated_at ?: LocalDateTime.MIN }!! }
    }

    /**
     * Gets or creates the Roku system user account.
     * Access level 1 (viewer), invalid password hash so it can never log in directly.
     */
    fun getOrCreateRokuUser(): AppUser {
        return AppUser.findAll().firstOrNull { it.username == "roku" }
            ?: AppUser(
                username = "roku",
                display_name = "Roku",
                access_level = 1,
                password_hash = "!nologin",
                created_at = LocalDateTime.now()
            ).apply { save() }
    }

    /**
     * Returns titles with the VIEWED flag for the current user, sorted by most
     * recently viewed (based on the flag's created_at). Limited to [limit] items.
     */
    fun getRecentlyWatched(limit: Int = 10): List<Title> {
        val userId = currentUserId() ?: return emptyList()
        return getRecentlyWatchedForUser(userId, limit)
    }

    /** Returns recently watched titles for an explicit user ID. */
    fun getRecentlyWatchedForUser(userId: Long, limit: Int = 10): List<Title> {
        val viewedFlags = UserTitleFlag.findAll()
            .filter { it.user_id == userId && it.flag == UserFlagType.VIEWED.name }
            .sortedByDescending { it.created_at }
            .take(limit)
        if (viewedFlags.isEmpty()) return emptyList()

        val titleIds = viewedFlags.map { it.title_id }.toSet()
        val titles = Title.findAll().filter { it.id in titleIds }.associateBy { it.id }

        // Exclude titles that are currently in Continue Watching (have active progress)
        val activeProgress = PlaybackProgress.findAll()
            .filter { it.user_id == userId }
            .map { it.transcode_id }
            .toSet()
        val activeTranscodes = if (activeProgress.isNotEmpty()) {
            Transcode.findAll().filter { it.id in activeProgress }.map { it.title_id }.toSet()
        } else emptySet()

        return viewedFlags.mapNotNull { flag ->
            if (flag.title_id in activeTranscodes) return@mapNotNull null
            titles[flag.title_id]
        }
    }

    /**
     * Returns the most recently linked transcodes with their titles, for the
     * "Recently Added" row on the home screen. Limited to [limit] items.
     */
    fun getRecentlyAdded(limit: Int = 10): List<Pair<Title, Transcode>> {
        val user = AuthService.getCurrentUser()
        return getRecentlyAddedForUser(user, limit)
    }

    /** Returns recently added titles for an explicit user (used for rating/hidden filtering). */
    fun getRecentlyAddedForUser(user: AppUser?, limit: Int = 10): List<Pair<Title, Transcode>> {
        // Get unique titles from most recent transcodes
        val recentTranscodes = Transcode.findAll()
            .filter { it.file_path != null && it.created_at != null }
            .sortedByDescending { it.created_at }

        val seenTitles = mutableSetOf<Long>()
        val results = mutableListOf<Pair<Title, Transcode>>()
        val allTitles = Title.findAll().associateBy { it.id }

        for (tc in recentTranscodes) {
            if (tc.title_id in seenTitles) continue
            val title = allTitles[tc.title_id] ?: continue
            // Skip hidden/rating-restricted titles
            if (title.tmdb_id == null) continue
            if (user != null && !user.canSeeRating(title.content_rating)) continue
            if (user != null && UserTitleFlag.findAll().any {
                    it.user_id == user.id && it.title_id == title.id && it.flag == UserFlagType.HIDDEN.name
                }) continue
            seenTitles.add(tc.title_id)
            results.add(title to tc)
            if (results.size >= limit) break
        }
        return results
    }

    // --- Private helpers ---

    private fun findProgressForUser(userId: Long, transcodeId: Long): PlaybackProgress? {
        return PlaybackProgress.findAll().firstOrNull {
            it.user_id == userId && it.transcode_id == transcodeId
        }
    }

    fun deleteProgressForUser(userId: Long, transcodeId: Long) {
        PlaybackProgress.findAll()
            .filter { it.user_id == userId && it.transcode_id == transcodeId }
            .forEach { it.delete() }
    }

    private fun setViewedFlag(userId: Long, transcodeId: Long) {
        val transcode = Transcode.findById(transcodeId) ?: return
        val titleId = transcode.title_id
        // Check if already flagged
        val exists = UserTitleFlag.findAll().any {
            it.user_id == userId && it.title_id == titleId && it.flag == UserFlagType.VIEWED.name
        }
        if (exists) return
        UserTitleFlag(
            user_id = userId,
            title_id = titleId,
            flag = UserFlagType.VIEWED.name,
            created_at = LocalDateTime.now()
        ).save()
        log.info("VIEWED flag set: user={} title={}", userId, titleId)
    }
}
