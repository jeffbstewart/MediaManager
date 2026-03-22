package net.stewart.mediamanager.service

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Central display timezone for all UI timestamps.
 * Reads from TZ environment variable, defaults to America/New_York.
 *
 * Timestamps in the DB are stored as server-local LocalDateTime.
 * This converts them to the configured display timezone for rendering.
 */
object DisplayTimezone {

    val zone: ZoneId = try {
        ZoneId.of(System.getenv("TZ") ?: "America/New_York")
    } catch (_: Exception) {
        ZoneId.of("America/New_York")
    }

    private val serverZone: ZoneId = ZoneId.systemDefault()

    /**
     * Formats a server-local LocalDateTime in the display timezone.
     * Returns the fallback string if the input is null.
     */
    fun format(dt: LocalDateTime?, pattern: String, fallback: String = ""): String {
        if (dt == null) return fallback
        val zonedServer = dt.atZone(serverZone)
        val zonedDisplay = zonedServer.withZoneSameInstant(zone)
        return zonedDisplay.format(DateTimeFormatter.ofPattern(pattern))
    }

    /** Short time: "HH:mm" */
    fun formatTime(dt: LocalDateTime?, fallback: String = ""): String =
        format(dt, "HH:mm", fallback)

    /** Date + time: "MM-dd HH:mm" */
    fun formatDateTime(dt: LocalDateTime?, fallback: String = ""): String =
        format(dt, "MM-dd HH:mm", fallback)

    /** Full: "yyyy-MM-dd HH:mm" */
    fun formatFull(dt: LocalDateTime?, fallback: String = ""): String =
        format(dt, "yyyy-MM-dd HH:mm", fallback)
}
