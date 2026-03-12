package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.entity.DismissedNotification
import net.stewart.mediamanager.entity.MediaItemTitleSeason
import net.stewart.mediamanager.entity.TitleSeason
import org.slf4j.LoggerFactory

/** A TV title with seasons above the highest owned that we don't have. */
data class MissingSeasonSummary(
    val titleId: Long,
    val titleName: String,
    val posterPath: String?,
    val tmdbId: Int?,
    val tmdbMediaType: String?,
    val missingSeasons: List<TitleSeason>
)

object MissingSeasonService {
    private val log = LoggerFactory.getLogger(MissingSeasonService::class.java)

    /**
     * Stores TMDB season data for a TV title. Upserts — existing rows are updated,
     * new seasons are inserted. Does not remove seasons that TMDB no longer lists
     * (they may have been renumbered or our data is stale).
     */
    fun storeSeasons(titleId: Long, seasons: List<TmdbSeasonInfo>) {
        val existing = TitleSeason.findAll().filter { it.title_id == titleId }
            .associateBy { it.season_number }

        for (s in seasons) {
            val row = existing[s.seasonNumber]
            if (row != null) {
                row.name = s.name
                row.episode_count = s.episodeCount
                row.air_date = s.airDate
                row.save()
            } else {
                TitleSeason(
                    title_id = titleId,
                    season_number = s.seasonNumber,
                    name = s.name,
                    episode_count = s.episodeCount,
                    air_date = s.airDate
                ).save()
            }
        }
    }

    /**
     * Updates acquisition_status on title_season rows by checking which seasons
     * have at least one transcode with an episode. Only promotes UNKNOWN → OWNED;
     * does not demote seasons that were manually set to ORDERED, etc.
     * Call after NAS scan completes.
     */
    fun refreshOwnership() {
        // Build a set of (title_id, season_number) pairs that have transcodes
        val ownedPairs = JdbiOrm.jdbi().withHandle<Set<Pair<Long, Int>>, Exception> { handle ->
            handle.createQuery(
                """SELECT DISTINCT e.title_id, e.season_number
                   FROM episode e
                   JOIN transcode t ON t.episode_id = e.id"""
            ).map { rs, _ -> rs.getLong("title_id") to rs.getInt("season_number") }
                .set()
        }

        var updated = 0
        for (row in TitleSeason.findAll()) {
            val hasTranscodes = (row.title_id to row.season_number) in ownedPairs
            if (hasTranscodes && row.acquisition_status == AcquisitionStatus.UNKNOWN.name) {
                row.acquisition_status = AcquisitionStatus.OWNED.name
                row.save()
                updated++
            }
        }
        if (updated > 0) {
            log.info("Season ownership refreshed: {} rows updated", updated)
        }
    }

    /**
     * Returns all TV titles where we own at least one season but are missing
     * seasons with numbers higher than our highest owned season.
     * Excludes seasons dismissed by the given user.
     */
    fun getMissingSeasonsForUser(userId: Long, limit: Int = 10): List<MissingSeasonSummary> {
        val dismissed = DismissedNotification.findAll()
            .filter { it.user_id == userId }
            .map { it.notification_key }
            .toSet()

        // Group all title_season rows by title
        val byTitle = TitleSeason.findAll().groupBy { it.title_id }

        val results = mutableListOf<MissingSeasonSummary>()

        for ((titleId, seasons) in byTitle) {
            val owned = seasons
                .filter { it.acquisition_status == AcquisitionStatus.OWNED.name }
                .map { it.season_number }
            if (owned.isEmpty()) continue // we don't own any seasons of this show

            val highestOwned = owned.max()
            val missing = seasons
                .filter { it.acquisition_status != AcquisitionStatus.OWNED.name && it.season_number > highestOwned }
                .filter { s -> "missing_season:${titleId}:${s.season_number}" !in dismissed }
                .sortedBy { it.season_number }

            if (missing.isEmpty()) continue

            val title = net.stewart.mediamanager.entity.Title.findById(titleId) ?: continue
            if (title.hidden) continue

            results.add(MissingSeasonSummary(
                titleId = titleId,
                titleName = title.name,
                posterPath = title.poster_path,
                tmdbId = title.tmdb_id,
                tmdbMediaType = title.media_type,
                missingSeasons = missing
            ))
        }

        // Sort by TMDB popularity (most popular shows first)
        return results
            .sortedByDescending { summary ->
                net.stewart.mediamanager.entity.Title.findById(summary.titleId)?.popularity ?: 0.0
            }
            .take(limit)
    }

    /**
     * Parses freetext seasons (e.g. "2", "1,3", "S1, S2") and creates structured
     * title_season + media_item_title_season rows. Call after saving freetext seasons.
     */
    fun syncStructuredSeasons(mediaItemTitleId: Long, titleId: Long, freetext: String?) {
        // Remove old structured rows for this join
        MediaItemTitleSeason.findAll()
            .filter { it.media_item_title_id == mediaItemTitleId }
            .forEach { it.delete() }

        if (freetext.isNullOrBlank()) return

        val seasonNumbers = parseSeasonText(freetext) ?: return

        for (num in seasonNumbers) {
            // Find or create title_season row
            var ts = TitleSeason.findAll().firstOrNull {
                it.title_id == titleId && it.season_number == num
            }
            if (ts == null) {
                ts = TitleSeason(title_id = titleId, season_number = num,
                    acquisition_status = AcquisitionStatus.OWNED.name)
                ts.save()
            } else if (ts.acquisition_status == AcquisitionStatus.UNKNOWN.name) {
                ts.acquisition_status = AcquisitionStatus.OWNED.name
                ts.save()
            }

            MediaItemTitleSeason(
                media_item_title_id = mediaItemTitleId,
                title_season_id = ts.id!!
            ).save()
        }
    }

    /** Parses freetext like "2", "1,3", "S1, S2", "1-3", "all" into season numbers. */
    private fun parseSeasonText(text: String): List<Int>? {
        val trimmed = text.trim()
        // Single number
        trimmed.toIntOrNull()?.let { return listOf(it) }
        // Range: "1-3"
        val rangeMatch = Regex("""^(\d+)\s*-\s*(\d+)$""").matchEntire(trimmed)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toInt()
            val end = rangeMatch.groupValues[2].toInt()
            if (start <= end && end - start < 50) return (start..end).toList()
        }
        // Comma-separated with optional S prefix
        val parts = trimmed.split(",").map { it.trim().removePrefix("S").removePrefix("s") }
        if (parts.all { it.toIntOrNull() != null }) {
            return parts.map { it.toInt() }
        }
        return null
    }

    /** Dismiss a specific missing season notification for a user. */
    fun dismiss(userId: Long, titleId: Long, seasonNumber: Int) {
        val key = "missing_season:${titleId}:${seasonNumber}"
        val exists = DismissedNotification.findAll().any {
            it.user_id == userId && it.notification_key == key
        }
        if (!exists) {
            DismissedNotification(
                user_id = userId,
                notification_key = key,
                dismissed_at = java.time.LocalDateTime.now()
            ).save()
        }
    }

    /** Dismiss all missing seasons for a title for a user. */
    fun dismissAllForTitle(userId: Long, titleId: Long) {
        val seasons = TitleSeason.findAll().filter {
            it.title_id == titleId && it.acquisition_status != AcquisitionStatus.OWNED.name
        }
        for (s in seasons) {
            dismiss(userId, titleId, s.season_number)
        }
    }
}
