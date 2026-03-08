package net.stewart.mediamanager.service

import java.time.LocalDateTime
import java.util.ArrayDeque

/**
 * In-memory circular buffer of recent HTTP requests for debugging.
 * Thread-safe. Entries are evicted FIFO when the buffer is full.
 */
object RequestLogBuffer {

    data class RequestLogEntry(
        val timestamp: LocalDateTime,
        val clientIp: String,
        val username: String,
        val method: String,
        val uri: String,
        val protocol: String,
        val status: Int,
        val responseSize: Long,
        val userAgent: String,
        val elapsedMs: Long
    )

    private const val MAX_ENTRIES = 200
    private val buffer = ArrayDeque<RequestLogEntry>(MAX_ENTRIES)
    private val lock = Any()

    fun add(entry: RequestLogEntry) {
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.pollFirst()
            }
            buffer.addLast(entry)
        }
    }

    fun getAll(): List<RequestLogEntry> {
        synchronized(lock) {
            return buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }
}
