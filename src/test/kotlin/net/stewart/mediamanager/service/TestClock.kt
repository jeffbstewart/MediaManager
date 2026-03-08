package net.stewart.mediamanager.service

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.time.Duration

/**
 * Test clock with manually controllable time.
 * sleep() records the requested duration but does NOT actually sleep.
 */
class TestClock(
    private var currentTime: LocalDateTime = LocalDateTime.of(2025, 1, 1, 12, 0, 0)
) : Clock {
    private var currentMillis: Long = 0L
    val sleepRequests: MutableList<Duration> = mutableListOf()

    override fun now(): LocalDateTime = currentTime
    override fun today(): LocalDate = currentTime.toLocalDate()
    override fun currentTimeMillis(): Long = currentMillis
    override fun sleep(duration: Duration) { sleepRequests.add(duration) }

    fun advance(minutes: Long) {
        currentTime = currentTime.plusMinutes(minutes)
        currentMillis += minutes * 60_000
    }

    fun advanceHours(hours: Long) = advance(hours * 60)
    fun advanceDays(days: Long) = advance(days * 24 * 60)

    fun set(time: LocalDateTime) {
        currentTime = time
    }
}
