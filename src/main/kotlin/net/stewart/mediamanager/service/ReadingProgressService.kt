package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.ReadingProgress
import java.time.LocalDateTime

/**
 * Book-reading position tracking. Parallels [PlaybackProgressService] on
 * the video side, but the position is opaque text (an EPUB CFI, or a
 * `"/page/N"` convention for PDFs) since books have no duration.
 */
object ReadingProgressService {

    fun get(userId: Long, mediaItemId: Long): ReadingProgress? =
        ReadingProgress.findAll().firstOrNull {
            it.user_id == userId && it.media_item_id == mediaItemId
        }

    /** Upsert: create the row on first report, update cfi / percent / updated_at thereafter. */
    fun save(userId: Long, mediaItemId: Long, cfi: String, percent: Double): ReadingProgress {
        val existing = get(userId, mediaItemId)
        val now = LocalDateTime.now()
        if (existing != null) {
            existing.cfi = cfi
            existing.percent = percent.coerceIn(0.0, 1.0)
            existing.updated_at = now
            existing.save()
            return existing
        }
        val created = ReadingProgress(
            user_id = userId,
            media_item_id = mediaItemId,
            cfi = cfi,
            percent = percent.coerceIn(0.0, 1.0),
            updated_at = now
        )
        created.save()
        return created
    }

    fun delete(userId: Long, mediaItemId: Long) {
        get(userId, mediaItemId)?.delete()
    }

    /** Most recently active reading sessions for the Resume Reading carousel. */
    fun recentForUser(userId: Long, limit: Int = 10): List<ReadingProgress> =
        ReadingProgress.findAll()
            .filter { it.user_id == userId }
            .sortedByDescending { it.updated_at ?: LocalDateTime.MIN }
            .take(limit)
}
