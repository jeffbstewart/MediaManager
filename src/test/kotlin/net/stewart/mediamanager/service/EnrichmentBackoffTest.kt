package net.stewart.mediamanager.service

import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnrichmentBackoffTest {

    // ---------------------- cooldownFor ----------------------

    @Test
    fun `cooldownFor steps through the ladder`() {
        assertEquals(Duration.ofHours(1), EnrichmentBackoff.cooldownFor(0))
        assertEquals(Duration.ofDays(1), EnrichmentBackoff.cooldownFor(1))
        assertEquals(Duration.ofDays(3), EnrichmentBackoff.cooldownFor(2))
        assertEquals(Duration.ofDays(7), EnrichmentBackoff.cooldownFor(3))
        assertEquals(Duration.ofDays(30), EnrichmentBackoff.cooldownFor(4))
    }

    @Test
    fun `cooldownFor stays at 90 days past streak 5`() {
        assertEquals(Duration.ofDays(90), EnrichmentBackoff.cooldownFor(5))
        assertEquals(Duration.ofDays(90), EnrichmentBackoff.cooldownFor(6))
        assertEquals(Duration.ofDays(90), EnrichmentBackoff.cooldownFor(99))
    }

    // ---------------------- isEligibleForRetry ----------------------

    @Test
    fun `isEligibleForRetry true when never attempted`() {
        // Newly-seeded rows fast-track regardless of streak.
        val now = LocalDateTime.of(2026, 1, 1, 12, 0)
        assertTrue(EnrichmentBackoff.isEligibleForRetry(null, 0, now))
        assertTrue(EnrichmentBackoff.isEligibleForRetry(null, 5, now))
    }

    @Test
    fun `isEligibleForRetry false within cooldown window`() {
        val attempted = LocalDateTime.of(2026, 1, 1, 12, 0)
        // 30 minutes later, streak 0 (1 h cooldown) → not yet eligible.
        val checkedAt = attempted.plusMinutes(30)
        assertFalse(EnrichmentBackoff.isEligibleForRetry(attempted, 0, checkedAt))
    }

    @Test
    fun `isEligibleForRetry true exactly at the cooldown boundary`() {
        // The check is "not after now" so equal-to-cooldown is eligible.
        val attempted = LocalDateTime.of(2026, 1, 1, 12, 0)
        val checkedAt = attempted.plusHours(1)  // streak=0 cooldown is 1 h
        assertTrue(EnrichmentBackoff.isEligibleForRetry(attempted, 0, checkedAt))
    }

    @Test
    fun `isEligibleForRetry true past the cooldown window`() {
        val attempted = LocalDateTime.of(2026, 1, 1, 12, 0)
        val checkedAt = attempted.plusDays(2)  // streak=1 cooldown is 1 day
        assertTrue(EnrichmentBackoff.isEligibleForRetry(attempted, 1, checkedAt))
    }

    @Test
    fun `isEligibleForRetry honours the 90-day cap`() {
        val attempted = LocalDateTime.of(2026, 1, 1, 12, 0)
        val checkedAt = attempted.plusDays(89)
        assertFalse(EnrichmentBackoff.isEligibleForRetry(attempted, 5, checkedAt))
        assertTrue(EnrichmentBackoff.isEligibleForRetry(attempted, 5, attempted.plusDays(90)))
    }

    // ---------------------- nextStreak ----------------------

    @Test
    fun `nextStreak resets on progress`() {
        assertEquals(0, EnrichmentBackoff.nextStreak(0, madeProgress = true))
        assertEquals(0, EnrichmentBackoff.nextStreak(5, madeProgress = true))
        assertEquals(0, EnrichmentBackoff.nextStreak(99, madeProgress = true))
    }

    @Test
    fun `nextStreak increments on no progress`() {
        assertEquals(1, EnrichmentBackoff.nextStreak(0, madeProgress = false))
        assertEquals(2, EnrichmentBackoff.nextStreak(1, madeProgress = false))
        assertEquals(6, EnrichmentBackoff.nextStreak(5, madeProgress = false))
    }
}
