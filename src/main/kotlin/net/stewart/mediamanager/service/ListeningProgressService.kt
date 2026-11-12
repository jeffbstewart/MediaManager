package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.ListeningProgress
import java.time.LocalDateTime

/**
 * Per-user, per-track listening position tracking. Parallels
 * [PlaybackProgressService] on the video side and [ReadingProgressService]
 * on the books side — same upsert semantics, one row per (user, track).
 */
object ListeningProgressService {

    fun get(userId: Long, trackId: Long): ListeningProgress? =
        ListeningProgress.findAll().firstOrNull {
            it.user_id == userId && it.track_id == trackId
        }

    /** Upsert: create on first report, bump position / duration / timestamp thereafter. */
    fun save(
        userId: Long,
        trackId: Long,
        positionSeconds: Int,
        durationSeconds: Int?
    ): ListeningProgress {
        val existing = get(userId, trackId)
        val now = LocalDateTime.now()
        if (existing != null) {
            existing.position_seconds = positionSeconds.coerceAtLeast(0)
            if (durationSeconds != null) existing.duration_seconds = durationSeconds
            existing.updated_at = now
            existing.save()
            return existing
        }
        val created = ListeningProgress(
            user_id = userId,
            track_id = trackId,
            position_seconds = positionSeconds.coerceAtLeast(0),
            duration_seconds = durationSeconds,
            updated_at = now
        )
        created.save()
        return created
    }

    fun delete(userId: Long, trackId: Long) {
        get(userId, trackId)?.delete()
    }

    /** Most recently active listening sessions for the Continue Listening carousel. */
    fun recentForUser(userId: Long, limit: Int = 10): List<ListeningProgress> =
        ListeningProgress.findAll()
            .filter { it.user_id == userId }
            .sortedByDescending { it.updated_at ?: LocalDateTime.MIN }
            .take(limit)
}
