package net.stewart.mediamanager.service

import java.time.Duration
import java.time.LocalDateTime

/**
 * Shared retry-cooldown policy for enrichment agents. Called by
 * [ArtistEnrichmentAgent], [AuthorEnrichmentAgent], and anything else
 * that pulls from an upstream source where some entities authoritatively
 * have nothing further to give us.
 *
 * Each entity keeps two fields on its own row (typically called
 * `enrichment_last_attempt_at` and `enrichment_no_progress_streak`) —
 * schema stays per-table so different agents don't have to share a
 * coordination table. This utility just centralizes the ladder and the
 * eligibility check so the agents stop carrying duplicate copies of it.
 *
 * The ladder — roughly "1% of entries per day over a quarter" for
 * entities stuck at the last step:
 *
 *   streak 0 → wait 1 hour     (just made progress; follow-up soon, not in 15 s)
 *   streak 1 → wait 1 day
 *   streak 2 → wait 3 days
 *   streak 3 → wait 7 days
 *   streak 4 → wait 30 days
 *   streak 5+ → wait 90 days
 *
 * The streak=0 floor exists because a "we made progress" attempt
 * tends to be followed by an immediate-no-further-progress attempt
 * 15 seconds later — which churns logs and MB traffic without
 * actually accomplishing anything. The 1 h floor lets remaining gaps
 * settle (Wikipedia / OL / MB sometimes update inside an hour) before
 * we look again, then the no-progress attempt locks the entity into
 * the day-scale ladder.
 *
 * Newly ingested rows (null last-attempt) bypass the floor and are
 * eligible on first pass — we want to fast-track new content.
 */
object EnrichmentBackoff {

    /** How long to wait after an attempt before the next one is allowed. */
    fun cooldownFor(streak: Int): Duration = when (streak) {
        0 -> Duration.ofHours(1)
        1 -> Duration.ofDays(1)
        2 -> Duration.ofDays(3)
        3 -> Duration.ofDays(7)
        4 -> Duration.ofDays(30)
        else -> Duration.ofDays(90)
    }

    /**
     * True when an entity with the given state is due for another
     * enrichment attempt. Newly seeded entities (null last-attempt) are
     * always eligible — fast-track new content. Otherwise the cooldown
     * ladder applies.
     */
    fun isEligibleForRetry(
        lastAttemptAt: LocalDateTime?,
        streak: Int,
        now: LocalDateTime
    ): Boolean {
        if (lastAttemptAt == null) return true
        val cooldown = cooldownFor(streak)
        if (cooldown == Duration.ZERO) return true
        return !lastAttemptAt.plus(cooldown).isAfter(now)
    }

    /**
     * New streak value after an attempt. Progress resets the streak to
     * zero so we re-check such entities every batch; no-progress
     * increments so the cooldown ladder advances.
     */
    fun nextStreak(currentStreak: Int, madeProgress: Boolean): Int =
        if (madeProgress) 0 else (currentStreak + 1)
}
