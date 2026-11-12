package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.ReadingProgress
import java.time.Duration
import java.time.LocalDateTime

/**
 * Book-reading position tracking. Parallels [PlaybackProgressService] on
 * the video side, but the position is opaque text (an EPUB CFI, or a
 * `"/page/N"` convention for PDFs) since books have no duration.
 *
 * **Most-recent-wins on `client_recorded_at`.** Each write carries the
 * client's wall-clock at the moment the relocation fired. If we see an
 * incoming write whose `clientRecordedAt` is strictly older than the
 * existing row's, we drop it — that's the "an offline iOS write got
 * flushed five minutes after the user already moved further on web"
 * case. Equal timestamps are treated as duplicates and dropped.
 *
 * Clients are tolerated up to [FUTURE_SKEW_TOLERANCE] into the future
 * (clock-skew on the client side); writes timestamped further out get
 * clamped to "now" before storage so a misconfigured client can't
 * pin a row at year 2099 and prevent any future writes.
 */
object ReadingProgressService {

    /** Cap on how far into the future a client clock may be trusted. */
    private val FUTURE_SKEW_TOLERANCE: Duration = Duration.ofMinutes(5)

    fun get(userId: Long, mediaItemId: Long): ReadingProgress? =
        ReadingProgress.findAll().firstOrNull {
            it.user_id == userId && it.media_item_id == mediaItemId
        }

    /**
     * Upsert with most-recent-wins on `clientRecordedAt`. Returns the
     * row that's now in the database — either freshly written, or the
     * pre-existing row if the incoming write was older / duplicate.
     *
     * `clientRecordedAt == null` means "old client, no timestamp" —
     * we accept the write and stamp the row with server-now, so the
     * pre-rollout semantics still hold for clients that haven't been
     * upgraded.
     */
    fun save(
        userId: Long,
        mediaItemId: Long,
        cfi: String,
        percent: Double,
        clientRecordedAt: LocalDateTime? = null,
    ): ReadingProgress {
        val existing = get(userId, mediaItemId)
        val now = LocalDateTime.now()
        // Clamp far-future client timestamps so a wrong client clock
        // can't park a row at year-2099 and lock out subsequent writes.
        val clamped = clientRecordedAt?.let {
            if (it.isAfter(now.plus(FUTURE_SKEW_TOLERANCE))) now else it
        }

        if (existing != null) {
            // Most-recent-wins: drop strictly-older or equal incoming.
            // Existing row has no client time → treat as oldest, accept.
            // Incoming has no client time → fall back to "always accept",
            // matching pre-rollout behaviour.
            val existingCT = existing.client_recorded_at
            if (clamped != null && existingCT != null) {
                if (!clamped.isAfter(existingCT)) return existing
            }

            existing.cfi = cfi
            existing.percent = percent.coerceIn(0.0, 1.0)
            existing.updated_at = now
            existing.client_recorded_at = clamped
            existing.save()
            return existing
        }

        val created = ReadingProgress(
            user_id = userId,
            media_item_id = mediaItemId,
            cfi = cfi,
            percent = percent.coerceIn(0.0, 1.0),
            updated_at = now,
            client_recorded_at = clamped,
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
