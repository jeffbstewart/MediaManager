package net.stewart.mediamanager.service

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.time.Duration

/** Abstraction over system time, enabling test-controllable clocks. */
interface Clock {
    /** Current date-time. Default: [LocalDateTime.now]. */
    fun now(): LocalDateTime

    /** Current date. Default: [LocalDate.now]. */
    fun today(): LocalDate

    /** Epoch milliseconds. Default: [System.currentTimeMillis]. */
    fun currentTimeMillis(): Long

    /** Suspend the current thread. Default: [Thread.sleep]. */
    fun sleep(duration: Duration)
}

/** Production clock — delegates to real system calls. */
object SystemClock : Clock {
    override fun now(): LocalDateTime = LocalDateTime.now()
    override fun today(): LocalDate = LocalDate.now()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun sleep(duration: Duration) = Thread.sleep(duration.inWholeMilliseconds)
}
