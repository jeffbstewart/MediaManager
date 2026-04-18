package net.stewart.mediamanager

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.service.*
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.*

/**
 * Integration test for the full scan → UPC lookup → title enrichment pipeline.
 *
 * Stands up an in-memory H2 database with real Flyway migrations, then exercises
 * UpcLookupAgent and TmdbEnrichmentAgent with mocked HTTP services. Verifies that
 * entities are created, linked, cleaned, and enriched correctly through the database.
 */
class EnrichmentIntegrationTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:enrichtest;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    @Before
    fun cleanTables() {
        // Delete in FK-safe order
        EnrichmentAttempt.deleteAll()
        MediaItemTitleSeason.deleteAll()
        TitleSeason.deleteAll()
        MediaItemTitle.deleteAll()
        BarcodeScan.deleteAll()
        MediaItem.deleteAll()
        Title.deleteAll()
        AppConfig.deleteAll()
    }

    // --- Mock services ---

    /** UPC lookup that returns controlled responses for known UPCs. */
    private class TestUpcLookupService(
        private val responses: Map<String, UpcLookupResult>
    ) : UpcLookupService {
        override fun lookup(upc: String): UpcLookupResult {
            return responses[upc] ?: UpcLookupResult(found = false)
        }
    }

    /**
     * MusicBrainz stub that always returns NotFound. Existing tests don't
     * exercise the music path; without this stub, UpcLookupAgent's default
     * [MusicBrainzHttpService] would make real HTTP calls in-test.
     */
    private class StubMusicBrainzService : MusicBrainzService {
        override fun lookupByBarcode(barcode: String) = MusicBrainzResult.NotFound
        override fun lookupByReleaseMbid(releaseMbid: String) = MusicBrainzResult.NotFound
        override fun listArtistReleaseGroups(artistMbid: String, limit: Int) = emptyList<ArtistReleaseGroupRef>()
    }

    /** TMDB service that returns controlled responses without making HTTP calls. */
    private class TestTmdbService(
        private val movieResponses: Map<String, TmdbSearchResult> = emptyMap(),
        private val tvResponses: Map<String, TmdbSearchResult> = emptyMap(),
        private val detailResponses: Map<TmdbId, TmdbSearchResult> = emptyMap()
    ) : TmdbService() {
        override fun searchMovie(title: String) =
            movieResponses[title] ?: TmdbSearchResult(found = false)

        override fun searchTv(title: String) =
            tvResponses[title] ?: TmdbSearchResult(found = false)

        override fun getDetails(tmdbId: TmdbId) =
            detailResponses[tmdbId] ?: TmdbSearchResult(found = false)
    }

    /** TMDB service that always returns API errors. */
    private class AlwaysFailingTmdbService : TmdbService() {
        override fun searchMovie(title: String) =
            TmdbSearchResult(found = false, apiError = true, errorMessage = "Rate limited (429)")

        override fun searchTv(title: String) =
            TmdbSearchResult(found = false, apiError = true, errorMessage = "Rate limited (429)")

        override fun getDetails(tmdbId: TmdbId) =
            TmdbSearchResult(found = false, apiError = true, errorMessage = "Rate limited (429)")
    }

    // --- Helpers ---

    /** Save/restore TMDB_API_KEY system property around a block. */
    private fun withTmdbKey(key: String?, block: () -> Unit) {
        val saved = System.getProperty("TMDB_API_KEY")
        try {
            if (key != null) System.setProperty("TMDB_API_KEY", key)
            else System.clearProperty("TMDB_API_KEY")
            block()
        } finally {
            if (saved != null) System.setProperty("TMDB_API_KEY", saved)
            else System.clearProperty("TMDB_API_KEY")
        }
    }

    /** Insert a BarcodeScan in NOT_LOOKED_UP state. */
    private fun insertScan(upc: String): BarcodeScan {
        val scan = BarcodeScan(
            upc = upc,
            scanned_at = LocalDateTime.now(),
            lookup_status = LookupStatus.NOT_LOOKED_UP.name
        )
        scan.save()
        return scan
    }

    /** Insert a Title in PENDING state, as the UPC agent would create it. */
    private fun insertPendingTitle(rawName: String): Title {
        val now = LocalDateTime.now()
        val title = Title(
            name = rawName,
            raw_upc_title = rawName,
            enrichment_status = EnrichmentStatus.PENDING.name,
            created_at = now,
            updated_at = now
        )
        title.save()
        return title
    }

    // --- Tests ---

    @Test
    fun `full pipeline - scan to UPC lookup to TMDB enrichment`() = withTmdbKey("test-key") {
        // 1. Simulate barcode scan
        insertScan("883929301843")

        // 2. UPC lookup agent processes it (comma-separated article is typical UPCitemdb format)
        val upcAgent = UpcLookupAgent(musicBrainzService = StubMusicBrainzService(), lookupService = TestUpcLookupService(mapOf(
            "883929301843" to UpcLookupResult(
                found = true,
                productName = "Dark Knight, The (Blu-ray)",
                brand = "Warner Bros",
                description = "Batman faces the Joker",
                mediaFormat = "BLURAY",
                releaseYear = 2008,
                rawJson = """{"mock":true}"""
            )
        )))
        upcAgent.processNext()

        // 3. Verify UPC agent created the right records
        val scan = BarcodeScan.findAll().single()
        assertEquals(LookupStatus.FOUND.name, scan.lookup_status)
        assertNotNull(scan.media_item_id)

        val mediaItem = MediaItem.findAll().single()
        assertEquals("883929301843", mediaItem.upc)
        assertEquals("BLURAY", mediaItem.media_format)

        val title = Title.findAll().single()
        assertEquals("Dark Knight, The (Blu-ray)", title.raw_upc_title)
        assertEquals(EnrichmentStatus.PENDING.name, title.enrichment_status)

        val join = MediaItemTitle.findAll().single()
        assertEquals(mediaItem.id, join.media_item_id)
        assertEquals(title.id, join.title_id)

        // 4. Enrichment agent processes the pending title
        val enrichAgent = TmdbEnrichmentAgent(TestTmdbService(
            movieResponses = mapOf(
                "The Dark Knight" to TmdbSearchResult(
                    found = true,
                    tmdbId = 155,
                    title = "The Dark Knight",
                    releaseYear = 2008,
                    overview = "When the menace known as the Joker wreaks havoc...",
                    posterPath = "/qJ2tW6WMUDux911BTUgMe1YdLiI.jpg",
                    mediaType = "MOVIE"
                )
            )
        ))
        enrichAgent.processNext()

        // 5. Verify enrichment results
        val enriched = Title.getById(title.id!!)
        assertEquals("The Dark Knight", enriched.name)
        assertEquals("Dark Knight", enriched.sort_name)
        assertEquals(155, enriched.tmdb_id)
        assertEquals(2008, enriched.release_year)
        assertEquals("/qJ2tW6WMUDux911BTUgMe1YdLiI.jpg", enriched.poster_path)
        assertEquals("MOVIE", enriched.media_type)
        assertEquals(EnrichmentStatus.ENRICHED.name, enriched.enrichment_status)
        assertNull(enriched.retry_after)
        assertEquals("Dark Knight, The (Blu-ray)", enriched.raw_upc_title) // preserved

        // Poster URL construction works end-to-end (routes through cache servlet)
        assertEquals(
            "/posters/w185/${enriched.id}",
            enriched.posterUrl(PosterSize.THUMBNAIL)
        )

        // 6. Enrichment attempt logged
        val attempts = EnrichmentAttempt.findAll()
        assertEquals(1, attempts.size)
        assertTrue(attempts[0].succeeded)
        assertEquals(title.id, attempts[0].title_id)
        assertNull(attempts[0].error_message)
    }

    @Test
    fun `title cleaning works through enrichment pipeline`() = withTmdbKey(null) {
        insertPendingTitle(
            "Golden Compas The: 2-Disc Special Edition (WS/Dbl DB) (Blu-ray Platinum Series) [Blu-ray]"
        )

        TmdbEnrichmentAgent(TestTmdbService()).processNext()

        val title = Title.findAll().single()
        assertEquals(EnrichmentStatus.SKIPPED.name, title.enrichment_status)
        assertEquals("The Golden Compas", title.name)
        assertEquals("Golden Compas", title.sort_name)
        assertEquals(
            "Golden Compas The: 2-Disc Special Edition (WS/Dbl DB) (Blu-ray Platinum Series) [Blu-ray]",
            title.raw_upc_title
        )
    }

    @Test
    fun `enrichment without TMDB key sets SKIPPED`() = withTmdbKey(null) {
        insertPendingTitle("Inception [Blu-ray]")

        TmdbEnrichmentAgent(TestTmdbService()).processNext()

        val title = Title.findAll().single()
        assertEquals(EnrichmentStatus.SKIPPED.name, title.enrichment_status)
        assertEquals("Inception", title.name)
        assertEquals("Inception", title.sort_name)
    }

    @Test
    fun `enrichment with no TMDB match cleans title and sets SKIPPED`() = withTmdbKey("test-key") {
        insertPendingTitle("Some Obscure Movie Nobody Has Heard Of (DVD)")

        // TestTmdbService returns found=false for everything by default
        TmdbEnrichmentAgent(TestTmdbService()).processNext()

        val title = Title.findAll().single()
        assertEquals(EnrichmentStatus.SKIPPED.name, title.enrichment_status)
        assertEquals("Some Obscure Movie Nobody Has Heard Of", title.name)
        assertEquals("Some Obscure Movie Nobody Has Heard Of", title.sort_name)
        assertNull(title.tmdb_id)
        assertNull(title.poster_path)
    }

    @Test
    fun `TMDB API error sets FAILED with retry_after and logs failed attempt`() = withTmdbKey("test-key") {
        insertPendingTitle("The Matrix [Blu-ray]")

        val enrichAgent = TmdbEnrichmentAgent(TestTmdbService(
            movieResponses = mapOf(
                "The Matrix" to TmdbSearchResult(
                    found = false,
                    apiError = true,
                    errorMessage = "Rate limited (429)"
                )
            )
        ))
        enrichAgent.processNext()

        val title = Title.findAll().single()
        assertEquals(EnrichmentStatus.FAILED.name, title.enrichment_status)
        assertNotNull(title.retry_after)
        // Name should still be cleaned despite failure
        assertEquals("The Matrix", title.name)
        assertEquals("Matrix", title.sort_name)

        // Failed attempt logged
        val attempts = EnrichmentAttempt.findAll()
        assertEquals(1, attempts.size)
        assertFalse(attempts[0].succeeded)
        assertEquals("Rate limited (429)", attempts[0].error_message)
    }

    @Test
    fun `TV show found via fallback after no movie match`() = withTmdbKey("test-key") {
        insertPendingTitle("Breaking Bad Complete Series (Blu-ray)")

        // No movie match, but TV match exists
        val enrichAgent = TmdbEnrichmentAgent(TestTmdbService(
            tvResponses = mapOf(
                "Breaking Bad Complete Series" to TmdbSearchResult(
                    found = true,
                    tmdbId = 1396,
                    title = "Breaking Bad",
                    releaseYear = 2008,
                    overview = "A chemistry teacher diagnosed with cancer.",
                    posterPath = "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg",
                    mediaType = "TV"
                )
            )
        ))
        enrichAgent.processNext()

        val title = Title.findAll().single()
        assertEquals(EnrichmentStatus.ENRICHED.name, title.enrichment_status)
        assertEquals("Breaking Bad", title.name)
        assertEquals("Breaking Bad", title.sort_name)
        assertEquals("TV", title.media_type)
        assertEquals(1396, title.tmdb_id)
        assertEquals(2008, title.release_year)
    }

    @Test
    fun `reassignment fetches by TMDB ID and repopulates`() = withTmdbKey("test-key") {
        // A title that was enriched with the wrong match; user has set the correct tmdb_id
        val now = LocalDateTime.now()
        Title(
            name = "Wrong Movie",
            tmdb_id = 550,
            media_type = "MOVIE",
            enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name,
            created_at = now,
            updated_at = now
        ).save()

        val enrichAgent = TmdbEnrichmentAgent(TestTmdbService(
            detailResponses = mapOf(
                TmdbId(550, MediaType.MOVIE) to TmdbSearchResult(
                    found = true,
                    tmdbId = 550,
                    title = "Fight Club",
                    releaseYear = 1999,
                    overview = "An insomniac office worker forms an underground fight club.",
                    posterPath = "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
                    mediaType = "MOVIE"
                )
            )
        ))
        enrichAgent.processNext()

        val title = Title.findAll().single()
        assertEquals(EnrichmentStatus.ENRICHED.name, title.enrichment_status)
        assertEquals("Fight Club", title.name)
        assertEquals("Fight Club", title.sort_name) // no leading article
        assertEquals(550, title.tmdb_id)
        assertEquals(1999, title.release_year)
        assertEquals("/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", title.poster_path)
    }

    @Test
    fun `pending titles processed before failed titles`() = withTmdbKey("test-key") {
        val now = LocalDateTime.now()

        // Failed title with retry_after in the past (eligible for retry)
        Title(
            name = "Failed Title",
            raw_upc_title = "Failed Title",
            enrichment_status = EnrichmentStatus.FAILED.name,
            retry_after = now.minusMinutes(5),
            created_at = now.minusHours(1),
            updated_at = now.minusMinutes(30)
        ).save()

        // Pending title created after the failed one
        Title(
            name = "Pending Title (DVD)",
            raw_upc_title = "Pending Title (DVD)",
            enrichment_status = EnrichmentStatus.PENDING.name,
            created_at = now,
            updated_at = now
        ).save()

        // One call to processNext should pick the PENDING title, not the FAILED one
        TmdbEnrichmentAgent(TestTmdbService()).processNext()

        val titles = Title.findAll()
        val pending = titles.first { it.raw_upc_title == "Pending Title (DVD)" }
        val failed = titles.first { it.raw_upc_title == "Failed Title" }

        // The pending title was processed (now SKIPPED since no TMDB match)
        assertNotEquals(EnrichmentStatus.PENDING.name, pending.enrichment_status)
        // The failed title was NOT processed (still FAILED)
        assertEquals(EnrichmentStatus.FAILED.name, failed.enrichment_status)
    }

    @Test
    fun `multi-pack UPC creates NEEDS_EXPANSION media item with SKIPPED placeholder`() {
        insertScan("111222333444")

        val upcAgent = UpcLookupAgent(musicBrainzService = StubMusicBrainzService(), lookupService = TestUpcLookupService(mapOf(
            "111222333444" to UpcLookupResult(
                found = true,
                productName = "Pulp Fiction / Reservoir Dogs Double Feature",
                brand = "Miramax",
                description = "Two classic films",
                mediaFormat = "DVD",
                rawJson = """{"mock":true}"""
            )
        )))
        upcAgent.processNext()

        val scan = BarcodeScan.findAll().single()
        assertEquals(LookupStatus.FOUND.name, scan.lookup_status)
        assertTrue(scan.notes!!.contains("[MULTI-PACK"))

        val mediaItem = MediaItem.findAll().single()
        assertEquals(ExpansionStatus.NEEDS_EXPANSION.name, mediaItem.expansion_status)
        assertEquals(2, mediaItem.title_count)

        val title = Title.findAll().single()
        assertEquals(EnrichmentStatus.SKIPPED.name, title.enrichment_status)
        assertEquals("Pulp Fiction / Reservoir Dogs Double Feature", title.raw_upc_title)
    }

    @Test
    fun `single-title UPC creates SINGLE media item with PENDING title`() {
        insertScan("999888777666")

        val upcAgent = UpcLookupAgent(musicBrainzService = StubMusicBrainzService(), lookupService = TestUpcLookupService(mapOf(
            "999888777666" to UpcLookupResult(
                found = true,
                productName = "The Dark Knight (Blu-ray)",
                brand = "Warner Bros",
                mediaFormat = "BLURAY",
                rawJson = """{"mock":true}"""
            )
        )))
        upcAgent.processNext()

        val mediaItem = MediaItem.findAll().single()
        assertEquals(ExpansionStatus.SINGLE.name, mediaItem.expansion_status)
        assertEquals(1, mediaItem.title_count)

        val title = Title.findAll().single()
        assertEquals(EnrichmentStatus.PENDING.name, title.enrichment_status)
    }

    @Test
    fun `UPC not found does not create title or media item`() {
        insertScan("000000000000")

        val upcAgent = UpcLookupAgent(musicBrainzService = StubMusicBrainzService(), lookupService = TestUpcLookupService(mapOf(
            "000000000000" to UpcLookupResult(found = false)
        )))
        upcAgent.processNext()

        val scan = BarcodeScan.findAll().single()
        assertEquals(LookupStatus.NOT_FOUND.name, scan.lookup_status)
        assertTrue(Title.findAll().isEmpty())
        assertTrue(MediaItem.findAll().isEmpty())
    }

    // --- Season detection tests ---

    @Test
    fun `season detection populates join row during UPC lookup`() {
        insertScan("555666777888")

        val upcAgent = UpcLookupAgent(musicBrainzService = StubMusicBrainzService(), lookupService = TestUpcLookupService(mapOf(
            "555666777888" to UpcLookupResult(
                found = true,
                productName = "Breaking Bad Season 1 [Blu-ray]",
                brand = "Sony",
                mediaFormat = "BLURAY",
                rawJson = """{"mock":true}"""
            )
        )))
        upcAgent.processNext()

        val scan = BarcodeScan.findAll().single()
        assertEquals(LookupStatus.FOUND.name, scan.lookup_status)
        assertTrue(scan.notes!!.contains("[SEASON: 1]"))

        val join = MediaItemTitle.findAll().single()
        assertEquals("1", join.seasons)
    }

    @Test
    fun `non-season UPC has null seasons on join`() {
        insertScan("999888777666")

        val upcAgent = UpcLookupAgent(musicBrainzService = StubMusicBrainzService(), lookupService = TestUpcLookupService(mapOf(
            "999888777666" to UpcLookupResult(
                found = true,
                productName = "The Dark Knight (Blu-ray)",
                brand = "Warner Bros",
                mediaFormat = "BLURAY",
                rawJson = """{"mock":true}"""
            )
        )))
        upcAgent.processNext()

        val join = MediaItemTitle.findAll().single()
        assertNull(join.seasons)

        val scan = BarcodeScan.findAll().single()
        assertFalse(scan.notes!!.contains("[SEASON"))
    }

    // --- Title dedup tests ---

    @Test
    fun `title dedup merges when two titles enrich to same tmdb_id`() = withTmdbKey("test-key") {
        // Simulate two different UPCs that create two separate Titles for the same show
        val now = LocalDateTime.now()

        // First title — already enriched
        val existingTitle = Title(
            name = "Breaking Bad",
            raw_upc_title = "Breaking Bad Season 1 [Blu-ray]",
            tmdb_id = 1396,
            media_type = "TV",
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            created_at = now.minusHours(1),
            updated_at = now.minusHours(1)
        )
        existingTitle.save()

        val existingMediaItem = MediaItem(
            upc = "111111111111",
            media_format = "BLURAY",
            created_at = now.minusHours(1),
            updated_at = now.minusHours(1)
        )
        existingMediaItem.save()

        MediaItemTitle(
            media_item_id = existingMediaItem.id!!,
            title_id = existingTitle.id!!,
            seasons = "1"
        ).save()

        // Second title — PENDING, about to be enriched to the same tmdb_id
        val duplicateTitle = Title(
            name = "Breaking Bad Season 2 [Blu-ray]",
            raw_upc_title = "Breaking Bad Season 2 [Blu-ray]",
            enrichment_status = EnrichmentStatus.PENDING.name,
            created_at = now,
            updated_at = now
        )
        duplicateTitle.save()

        val duplicateMediaItem = MediaItem(
            upc = "222222222222",
            media_format = "BLURAY",
            created_at = now,
            updated_at = now
        )
        duplicateMediaItem.save()

        MediaItemTitle(
            media_item_id = duplicateMediaItem.id!!,
            title_id = duplicateTitle.id!!,
            seasons = "2"
        ).save()

        // Enrich the duplicate — TMDB returns same tmdb_id as existing
        val enrichAgent = TmdbEnrichmentAgent(TestTmdbService(
            tvResponses = mapOf(
                "Breaking Bad Season 2" to TmdbSearchResult(
                    found = true,
                    tmdbId = 1396,
                    title = "Breaking Bad",
                    releaseYear = 2008,
                    overview = "A chemistry teacher turns to crime.",
                    posterPath = "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg",
                    mediaType = "TV"
                )
            )
        ))
        enrichAgent.processNext()

        // Only one Title should remain (the existing one)
        val titles = Title.findAll()
        assertEquals(1, titles.size, "Should have merged into one title")
        assertEquals(existingTitle.id, titles[0].id)
        assertEquals(1396, titles[0].tmdb_id)

        // Both media items should be linked to the surviving title
        val joins = MediaItemTitle.findAll().sortedBy { it.media_item_id }
        assertEquals(2, joins.size, "Both media items should be linked")
        assertTrue(joins.all { it.title_id == existingTitle.id })
        assertEquals("1", joins[0].seasons)
        assertEquals("2", joins[1].seasons)
    }

    @Test
    fun `single-title enrichment still works normally (no dedup needed)`() = withTmdbKey("test-key") {
        insertPendingTitle("Inception [Blu-ray]")

        val enrichAgent = TmdbEnrichmentAgent(TestTmdbService(
            movieResponses = mapOf(
                "Inception" to TmdbSearchResult(
                    found = true,
                    tmdbId = 27205,
                    title = "Inception",
                    releaseYear = 2010,
                    overview = "A thief who steals corporate secrets through dream-sharing.",
                    posterPath = "/9gk7adHYeDvHkCSEhniVolVkAqR.jpg",
                    mediaType = "MOVIE"
                )
            )
        ))
        enrichAgent.processNext()

        val titles = Title.findAll()
        assertEquals(1, titles.size)
        assertEquals(EnrichmentStatus.ENRICHED.name, titles[0].enrichment_status)
        assertEquals(27205, titles[0].tmdb_id)
        assertEquals("Inception", titles[0].name)
    }

    // --- Clock-controlled failure/retry tests ---

    @Test
    fun `exponential backoff progression to ABANDONED`() = withTmdbKey("test-key") {
        val clock = TestClock()
        insertPendingTitle("Backoff Test Title (Blu-ray)")

        val agent = TmdbEnrichmentAgent(AlwaysFailingTmdbService(), clock = clock)

        // Expected backoff minutes: 30, 60, 120, 240, 480, 960, 1920, 3840, 7680, 10080, 10080
        val expectedBackoffs = listOf(30L, 60L, 120L, 240L, 480L, 960L, 1920L, 3840L, 7680L, 10080L, 10080L)

        for (i in 0 until 11) {
            agent.processNext()

            val title = Title.findAll().single()
            if (i < 10) {
                assertEquals(EnrichmentStatus.FAILED.name, title.enrichment_status,
                    "After failure ${i + 1}, should be FAILED")
                assertNotNull(title.retry_after, "After failure ${i + 1}, retry_after should be set")

                // Verify backoff matches expected
                val expectedRetryAfter = clock.now().plusMinutes(expectedBackoffs[i])
                assertEquals(expectedRetryAfter, title.retry_after,
                    "After failure ${i + 1}, retry_after should be now + ${expectedBackoffs[i]} minutes")

                // Advance clock past retry_after so the title is eligible for retry
                clock.advance(expectedBackoffs[i] + 1)
            } else {
                // 11th failure → ABANDONED
                assertEquals(EnrichmentStatus.ABANDONED.name, title.enrichment_status,
                    "After 11 failures, should be ABANDONED")
                assertNull(title.retry_after, "ABANDONED title should have null retry_after")
            }
        }

        // Verify 11 attempt records, all failed
        val attempts = EnrichmentAttempt.findAll().filter { it.title_id == Title.findAll().single().id }
        assertEquals(11, attempts.size)
        assertTrue(attempts.all { !it.succeeded })
    }

    @Test
    fun `FAILED title not retried before retry_after`() = withTmdbKey("test-key") {
        val clock = TestClock()
        insertPendingTitle("Timing Gate Test (DVD)")

        val agent = TmdbEnrichmentAgent(AlwaysFailingTmdbService(), clock = clock)

        // First failure — becomes FAILED with retry_after = now + 30min
        agent.processNext()
        val afterFirst = Title.findAll().single()
        assertEquals(EnrichmentStatus.FAILED.name, afterFirst.enrichment_status)
        assertNotNull(afterFirst.retry_after)

        // Advance only 15 minutes (before retry_after)
        clock.advance(15)
        agent.processNext()

        // Should still be FAILED with only 1 attempt (not retried)
        val stillFailed = Title.findAll().single()
        assertEquals(EnrichmentStatus.FAILED.name, stillFailed.enrichment_status)
        assertEquals(1, EnrichmentAttempt.findAll().size, "Should still have only 1 attempt")

        // Advance another 20 minutes (now 35min total, past retry_after)
        clock.advance(20)
        agent.processNext()

        // Now it should have been retried (2 attempts total)
        val retried = Title.findAll().single()
        assertEquals(EnrichmentStatus.FAILED.name, retried.enrichment_status, "Still FAILED after 2nd failure")
        assertEquals(2, EnrichmentAttempt.findAll().size, "Should now have 2 attempts")
    }

    @Test
    fun `REASSIGNMENT_REQUESTED after failure sequence recovers`() = withTmdbKey("test-key") {
        val clock = TestClock()
        insertPendingTitle("Recovery Test Movie (Blu-ray)")

        val failingAgent = TmdbEnrichmentAgent(AlwaysFailingTmdbService(), clock = clock)

        // Fail twice
        failingAgent.processNext()
        clock.advance(31) // past first retry_after (30min)
        failingAgent.processNext()

        val afterFails = Title.findAll().single()
        assertEquals(EnrichmentStatus.FAILED.name, afterFails.enrichment_status)
        assertEquals(2, EnrichmentAttempt.findAll().size)

        // User intervention: set tmdb_id and request reassignment
        afterFails.tmdb_id = 550
        afterFails.media_type = "MOVIE"
        afterFails.enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name
        afterFails.save()

        // Create a new agent with a service that succeeds for ID 550
        val succeedingAgent = TmdbEnrichmentAgent(TestTmdbService(
            detailResponses = mapOf(
                TmdbId(550, MediaType.MOVIE) to TmdbSearchResult(
                    found = true,
                    tmdbId = 550,
                    title = "Fight Club",
                    releaseYear = 1999,
                    overview = "An insomniac office worker forms an underground fight club.",
                    posterPath = "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
                    mediaType = "MOVIE"
                )
            )
        ), clock = clock)
        succeedingAgent.processNext()

        // Verify recovery
        val recovered = Title.findAll().single()
        assertEquals(EnrichmentStatus.ENRICHED.name, recovered.enrichment_status)
        assertEquals("Fight Club", recovered.name)
        assertEquals("Fight Club", recovered.sort_name)
        assertEquals(550, recovered.tmdb_id)
        assertEquals(1999, recovered.release_year)
        assertEquals("/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", recovered.poster_path)
        assertNull(recovered.retry_after)

        // 3 total attempts: 2 failed + 1 success
        val attempts = EnrichmentAttempt.findAll().sortedBy { it.attempted_at }
        assertEquals(3, attempts.size)
        assertFalse(attempts[0].succeeded)
        assertFalse(attempts[1].succeeded)
        assertTrue(attempts[2].succeeded)
    }
}
