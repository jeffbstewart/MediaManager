package net.stewart.mediamanager.util

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Converts a server-local [LocalDateTime] to a UTC ISO-8601 string with offset
 * (e.g., "2026-04-03T13:45:00Z"). Clients parse the Z suffix and format in
 * their local timezone.
 */
fun toIsoUtc(dt: LocalDateTime?): String? {
    if (dt == null) return null
    return dt.atZone(ZoneId.systemDefault())
        .withZoneSameInstant(ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
