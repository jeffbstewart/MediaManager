package net.stewart.mediamanager.service

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for [RadioService.RadioSeed] entries. Each active
 * radio session gets a UUID; the client replays the UUID on every
 * `/api/v2/radio/next` call instead of re-sending the seed state
 * (which includes the list of primary artist MBIDs). Entries expire
 * after [TTL] with no activity — cheap book-keeping, no DB table.
 *
 * Not clustered: a second server instance won't see sessions created
 * against this one. MM's deployment is single-process today, so that's
 * a non-issue. If we ever go multi-instance, this graduates to a DB
 * table or a sticky-sessions proxy in front.
 */
object RadioSeedStore {

    private val TTL: Duration = Duration.ofHours(4)

    private data class Entry(val seed: RadioService.RadioSeed, val expiresAt: Instant)

    private val store = ConcurrentHashMap<String, Entry>()

    fun put(seed: RadioService.RadioSeed, now: Instant = Instant.now()): String {
        sweep(now)
        val id = UUID.randomUUID().toString()
        store[id] = Entry(seed, now.plus(TTL))
        return id
    }

    fun get(id: String, now: Instant = Instant.now()): RadioService.RadioSeed? {
        val entry = store[id] ?: return null
        if (entry.expiresAt.isBefore(now)) {
            store.remove(id)
            return null
        }
        // Sliding expiration — every access refreshes the TTL so an
        // actively-playing session stays alive.
        store[id] = entry.copy(expiresAt = now.plus(TTL))
        return entry.seed
    }

    /** Explicit session end (StopRadio RPC). Safe to call on unknown ids. */
    fun remove(id: String) {
        store.remove(id)
    }

    /** Test seam. */
    internal fun clear() { store.clear() }

    private fun sweep(now: Instant) {
        store.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }
}
