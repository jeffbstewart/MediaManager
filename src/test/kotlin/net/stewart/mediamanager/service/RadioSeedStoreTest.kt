package net.stewart.mediamanager.service

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class RadioSeedStoreTest {

    private val sample = RadioService.RadioSeed(
        seedType = "album",
        seedId = 301,
        seedName = "Kind of Blue",
        seedArtistName = "Miles Davis",
        seedArtistMbids = listOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
    )

    @BeforeTest @AfterTest
    fun reset() {
        RadioSeedStore.clear()
    }

    @Test
    fun `put returns a new id and get round-trips the seed`() {
        val now = Instant.parse("2026-04-28T12:00:00Z")
        val id = RadioSeedStore.put(sample, now)
        assertNotNull(id)
        assertEquals(36, id.length, "expected UUID format")

        val recovered = RadioSeedStore.get(id, now)
        assertEquals(sample, recovered)
    }

    @Test
    fun `get returns null for unknown id`() {
        assertNull(RadioSeedStore.get("nonexistent"))
    }

    @Test
    fun `get returns null past the TTL`() {
        val now = Instant.parse("2026-04-28T12:00:00Z")
        val id = RadioSeedStore.put(sample, now)

        // Just past the 4-hour TTL.
        val later = now.plusSeconds(4 * 3600 + 1)
        assertNull(RadioSeedStore.get(id, later))

        // Re-querying after expiry returns null even with a fresh
        // current time — the entry was evicted on the first miss.
        assertNull(RadioSeedStore.get(id, later.plusSeconds(60)))
    }

    @Test
    fun `get sliding TTL keeps an active session alive`() {
        val now = Instant.parse("2026-04-28T12:00:00Z")
        val id = RadioSeedStore.put(sample, now)

        // 3 hours later — within TTL. Access refreshes expiry.
        val tickOne = now.plusSeconds(3 * 3600)
        assertNotNull(RadioSeedStore.get(id, tickOne))

        // 3 more hours later (6 h since put, 3 h since refresh) —
        // sliding TTL keeps it alive.
        val tickTwo = tickOne.plusSeconds(3 * 3600)
        assertNotNull(RadioSeedStore.get(id, tickTwo))
    }

    @Test
    fun `remove evicts the entry`() {
        val now = Instant.parse("2026-04-28T12:00:00Z")
        val id = RadioSeedStore.put(sample, now)
        RadioSeedStore.remove(id)
        assertNull(RadioSeedStore.get(id, now))
    }

    @Test
    fun `remove on unknown id is a no-op`() {
        // Documented in the StopRadio comment; assert it doesn't throw.
        RadioSeedStore.remove("does-not-exist")
    }

    @Test
    fun `put sweep evicts expired entries on subsequent insert`() {
        val now = Instant.parse("2026-04-28T12:00:00Z")
        val firstId = RadioSeedStore.put(sample, now)

        // Insert another well past the first's TTL — the sweep should
        // evict the first entry. Verify by trying to fetch it.
        val later = now.plusSeconds(5 * 3600)
        val secondId = RadioSeedStore.put(sample, later)

        assertNull(RadioSeedStore.get(firstId, later))
        assertNotNull(RadioSeedStore.get(secondId, later))
    }
}
