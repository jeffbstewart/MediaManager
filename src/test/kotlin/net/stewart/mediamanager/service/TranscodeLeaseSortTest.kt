package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.LeaseType
import net.stewart.mediamanager.entity.Transcode
import kotlin.test.*

/**
 * Unit tests for TranscodeLeaseService's work-item sort and pick helpers.
 *
 * Focus is on the priority tiering that governs which transcode the next
 * buddy request gets served. These tests are the contract that `claimWork`'s
 * priority decisions honor.
 *
 * Test scenario convention:
 *   - Titles 100 (Airwolf) and 200 (Xena) throughout.
 *   - Transcode IDs 1-5 = Airwolf episodes, 11-15 = Xena episodes.
 *   - "First-pass" = no ForMobile output yet, isReTranscode = false.
 *   - "Re-transcode" = ForMobile exists at older preset, isReTranscode = true.
 */
class TranscodeLeaseSortTest {

    // Named for readability in the test scenarios.
    private val airwolfTitleId = 100L
    private val xenaTitleId = 200L

    private fun firstPassMobile(id: Long, titleId: Long, requested: Boolean = false) =
        TranscodeLeaseService.WorkItem(
            transcode = Transcode(id = id, title_id = titleId, for_mobile_requested = requested),
            leaseType = LeaseType.MOBILE_TRANSCODE,
            titleId = titleId,
            typeOrder = 4,
            isReTranscode = false
        )

    private fun retranscodeMobile(id: Long, titleId: Long, requested: Boolean = false) =
        TranscodeLeaseService.WorkItem(
            transcode = Transcode(id = id, title_id = titleId, for_mobile_requested = requested),
            leaseType = LeaseType.MOBILE_TRANSCODE,
            titleId = titleId,
            typeOrder = 4,
            isReTranscode = true
        )

    private fun popularity(airwolf: Double, xena: Double) = mapOf(
        airwolfTitleId to airwolf,
        xenaTitleId to xena
    )

    // ---- pickWinnerId: the ship-critical cases ----

    @Test
    fun `empty work list returns null`() {
        assertNull(TranscodeLeaseService.pickWinnerId(
            workItems = emptyList(),
            priorityCounts = emptyMap(),
            popularityByTitle = emptyMap(),
            cachedTranscodeIds = emptySet()
        ))
    }

    @Test
    fun `first-pass beats re-transcode even when re-transcode is more popular`() {
        // Exactly the Airwolf (unpopular, first-pass) vs Xena (popular, re-transcode)
        // case that prompted this test suite.
        val items = listOf(
            firstPassMobile(id = 1, titleId = airwolfTitleId),
            retranscodeMobile(id = 11, titleId = xenaTitleId)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = emptyMap(),
            popularityByTitle = popularity(airwolf = 10.0, xena = 500.0),
            cachedTranscodeIds = emptySet()
        )
        assertEquals(1L, winner, "First-pass Airwolf must beat re-transcode Xena regardless of popularity")
    }

    @Test
    fun `cached re-transcode does NOT jump ahead of uncached first-pass`() {
        // The second-order bug that kept Xena spinning: after a failed
        // re-transcode, the source was cached locally; the picker preferred
        // the cached item across tiers, letting it jump first-pass work.
        val items = listOf(
            firstPassMobile(id = 1, titleId = airwolfTitleId),
            retranscodeMobile(id = 11, titleId = xenaTitleId)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = emptyMap(),
            popularityByTitle = popularity(airwolf = 10.0, xena = 500.0),
            cachedTranscodeIds = setOf(11L)  // Xena is cached
        )
        assertEquals(1L, winner, "Cached re-transcode must not leapfrog uncached first-pass")
    }

    @Test
    fun `cached first-pass wins over uncached first-pass`() {
        // Normal cache preference within the top tier: fine to prefer cached.
        val items = listOf(
            firstPassMobile(id = 1, titleId = airwolfTitleId),
            firstPassMobile(id = 2, titleId = airwolfTitleId),
            firstPassMobile(id = 3, titleId = airwolfTitleId)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = emptyMap(),
            popularityByTitle = popularity(airwolf = 10.0, xena = 500.0),
            cachedTranscodeIds = setOf(3L)
        )
        assertEquals(3L, winner, "Cache preference should win within the top tier")
    }

    @Test
    fun `re-transcode is picked when no first-pass exists`() {
        val items = listOf(
            retranscodeMobile(id = 11, titleId = xenaTitleId),
            retranscodeMobile(id = 12, titleId = xenaTitleId)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = emptyMap(),
            popularityByTitle = popularity(airwolf = 10.0, xena = 500.0),
            cachedTranscodeIds = emptySet()
        )
        // With equal other fields, the first in the sort wins. Popularity is
        // equal between the two, so we don't over-constrain which specific id.
        assertNotNull(winner)
        assertTrue(winner in setOf(11L, 12L))
    }

    @Test
    fun `priority bump on a re-transcode does NOT beat first-pass`() {
        // Re-transcodes are ALWAYS in the lowest tier. A title's
        // transcode_priority bump (from wish votes or manual UI action) was
        // originally about getting first-pass work done; it must not cause
        // the title's already-done re-transcodes to cut in front of other
        // titles' first-pass work. This is the regression captured in
        // claude.log 2026-04-12 when 27 Airwolf first-passes lost to 2542
        // Xena re-transcodes because Xena had positive priorityCounts.
        val items = listOf(
            firstPassMobile(id = 1, titleId = airwolfTitleId),
            retranscodeMobile(id = 11, titleId = xenaTitleId)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = mapOf(xenaTitleId to 5),  // Xena bumped, still a re-transcode
            popularityByTitle = popularity(airwolf = 10.0, xena = 10.0),
            cachedTranscodeIds = emptySet()
        )
        assertEquals(1L, winner, "First-pass must beat even a priority-bumped re-transcode")
    }

    @Test
    fun `priority bump wins within first-pass tier`() {
        // Manual priority still has meaning — it prioritizes among first-pass
        // items. Airwolf E01 (unbumped) vs Airwolf E02 (bumped) — bumped wins.
        val items = listOf(
            firstPassMobile(id = 1, titleId = airwolfTitleId),
            firstPassMobile(id = 2, titleId = xenaTitleId)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = mapOf(xenaTitleId to 5),
            popularityByTitle = popularity(airwolf = 100.0, xena = 10.0),
            cachedTranscodeIds = emptySet()
        )
        assertEquals(2L, winner, "Within first-pass tier, priority bump wins over popularity")
    }

    @Test
    fun `priority bump still orders within re-transcode tier`() {
        // When only re-transcodes exist, priority bumps still matter for
        // ordering within the tier.
        val items = listOf(
            retranscodeMobile(id = 1, titleId = airwolfTitleId),
            retranscodeMobile(id = 11, titleId = xenaTitleId)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = mapOf(xenaTitleId to 5),
            popularityByTitle = popularity(airwolf = 100.0, xena = 10.0),
            cachedTranscodeIds = emptySet()
        )
        assertEquals(11L, winner, "Within re-transcode tier, priority bump wins")
    }

    @Test
    fun `for_mobile_requested first-pass beats unrequested first-pass at same popularity`() {
        val items = listOf(
            firstPassMobile(id = 1, titleId = airwolfTitleId, requested = false),
            firstPassMobile(id = 2, titleId = airwolfTitleId, requested = true)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = emptyMap(),
            popularityByTitle = popularity(airwolf = 10.0, xena = 0.0),
            cachedTranscodeIds = emptySet()
        )
        assertEquals(2L, winner, "User-requested mobile jumps ahead within its tier")
    }

    @Test
    fun `for_mobile_requested re-transcode does NOT beat unrequested first-pass`() {
        // The requested-mobile tier only applies within the same
        // first-pass-vs-re-transcode bucket.
        val items = listOf(
            firstPassMobile(id = 1, titleId = airwolfTitleId, requested = false),
            retranscodeMobile(id = 11, titleId = xenaTitleId, requested = true)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = emptyMap(),
            popularityByTitle = popularity(airwolf = 10.0, xena = 500.0),
            cachedTranscodeIds = emptySet()
        )
        assertEquals(1L, winner)
    }

    @Test
    fun `within first-pass tier, more popular title wins`() {
        val items = listOf(
            firstPassMobile(id = 1, titleId = airwolfTitleId),
            firstPassMobile(id = 11, titleId = xenaTitleId)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = emptyMap(),
            popularityByTitle = popularity(airwolf = 10.0, xena = 500.0),
            cachedTranscodeIds = emptySet()
        )
        assertEquals(11L, winner, "Within same tier, popular title wins")
    }

    @Test
    fun `within re-transcode tier, more popular title wins`() {
        val items = listOf(
            retranscodeMobile(id = 1, titleId = airwolfTitleId),
            retranscodeMobile(id = 11, titleId = xenaTitleId)
        )
        val winner = TranscodeLeaseService.pickWinnerId(
            workItems = items,
            priorityCounts = emptyMap(),
            popularityByTitle = popularity(airwolf = 10.0, xena = 500.0),
            cachedTranscodeIds = emptySet()
        )
        assertEquals(11L, winner)
    }

    // ---- sortWorkItems: more direct contract checks ----

    @Test
    fun `sort places first-pass before re-transcode`() {
        val items = listOf(
            retranscodeMobile(id = 11, titleId = xenaTitleId),
            firstPassMobile(id = 1, titleId = airwolfTitleId)
        )
        val sorted = TranscodeLeaseService.sortWorkItems(
            items, emptyMap(), popularity(airwolf = 10.0, xena = 500.0)
        )
        assertFalse(sorted.first().isReTranscode,
            "First-pass item must come before re-transcode in sort order")
        assertEquals(1L, sorted.first().transcode.id)
    }

    @Test
    fun `sort places manually-prioritized title first within a tier, not across tiers`() {
        // Within the first-pass tier, a priority bump on one title does raise
        // it above unbumped titles. But across tiers, re-transcodes always lose.
        val items = listOf(
            firstPassMobile(id = 1, titleId = airwolfTitleId),
            firstPassMobile(id = 2, titleId = xenaTitleId)
        )
        val sorted = TranscodeLeaseService.sortWorkItems(
            items,
            priorityCounts = mapOf(xenaTitleId to 3),
            popularityByTitle = popularity(airwolf = 10.0, xena = 10.0)
        )
        assertEquals(2L, sorted.first().transcode.id,
            "Priority bump wins WITHIN a tier (both first-pass here)")
    }
}
