package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.*
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for PopularityRefreshAgent verifying:
 * a) API error handling (stops batch on errors)
 * b) Request pacing (500ms gaps via clock.sleep)
 * c) Batch sizing (~1% per day across 6 cycles)
 * d) Oldest-first refresh ordering
 * e) Cast member refresh by distinct person ID
 * f) Personal video exclusion
 */
class PopularityRefreshAgentTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:poprefreshtest;DB_CLOSE_DELAY=-1"
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
        CastMember.deleteAll()
        Title.deleteAll()
    }

    // --- Mock TMDB services ---

    private class FakeTmdbService(
        private val titlePopularity: Map<TmdbId, Double> = emptyMap(),
        private val personPopularity: Map<Int, Double> = emptyMap(),
        private val failOnTitleIds: Set<TmdbId> = emptySet(),
        private val failOnPersonIds: Set<Int> = emptySet()
    ) : TmdbService() {
        val titleCalls = mutableListOf<TmdbId>()
        val personCalls = mutableListOf<Int>()

        override fun getDetails(tmdbId: TmdbId): TmdbSearchResult {
            titleCalls.add(tmdbId)
            if (tmdbId in failOnTitleIds) {
                return TmdbSearchResult(found = false, apiError = true, errorMessage = "Rate limited (429)")
            }
            val pop = titlePopularity[tmdbId]
            return if (pop != null) {
                TmdbSearchResult(found = true, tmdbId = tmdbId.id, popularity = pop, mediaType = tmdbId.typeString)
            } else {
                TmdbSearchResult(found = false)
            }
        }

        override fun fetchPersonDetails(personId: Int): TmdbPersonResult {
            personCalls.add(personId)
            if (personId in failOnPersonIds) {
                return TmdbPersonResult(found = false, errorMessage = "HTTP 429")
            }
            val pop = personPopularity[personId]
            return if (pop != null) {
                TmdbPersonResult(found = true, popularity = pop)
            } else {
                TmdbPersonResult(found = false)
            }
        }
    }

    // --- Helpers ---

    /** Creates an agent with running=true so refreshTitles/refreshCastMembers process items. */
    private fun testAgent(tmdb: FakeTmdbService, clock: TestClock = TestClock()): PopularityRefreshAgent {
        return PopularityRefreshAgent(tmdb, clock).also { it.running.set(true) }
    }

    private fun insertTitle(
        name: String,
        tmdbId: Int,
        mediaType: String = "MOVIE",
        popularity: Double? = 50.0,
        refreshedAt: LocalDateTime? = null
    ): Title {
        val now = LocalDateTime.now()
        return Title(
            name = name,
            tmdb_id = tmdbId,
            media_type = mediaType,
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            popularity = popularity,
            popularity_refreshed_at = refreshedAt,
            created_at = now,
            updated_at = now
        ).also { it.save() }
    }

    private fun insertCastMember(
        titleId: Long,
        personId: Int,
        name: String,
        popularity: Double? = 10.0,
        refreshedAt: LocalDateTime? = null
    ): CastMember {
        return CastMember(
            title_id = titleId,
            tmdb_person_id = personId,
            name = name,
            popularity = popularity,
            popularity_refreshed_at = refreshedAt,
            created_at = LocalDateTime.now()
        ).also { it.save() }
    }

    // ========== Batch size tests ==========

    @Test
    fun `batch size is 1 for small catalogs`() {
        val agent = PopularityRefreshAgent(FakeTmdbService(), TestClock())
        assertEquals(1, agent.computeBatchSize(1))
        assertEquals(1, agent.computeBatchSize(100))
        assertEquals(1, agent.computeBatchSize(600))
    }

    @Test
    fun `batch size scales with catalog size`() {
        val agent = PopularityRefreshAgent(FakeTmdbService(), TestClock())
        assertEquals(2, agent.computeBatchSize(601))
        assertEquals(2, agent.computeBatchSize(1200))
        assertEquals(5, agent.computeBatchSize(3000))
        assertEquals(10, agent.computeBatchSize(6000))
    }

    @Test
    fun `batch size ensures full cycle in about 100 days`() {
        val agent = PopularityRefreshAgent(FakeTmdbService(), TestClock())
        for (catalogSize in listOf(100, 500, 1000, 5000, 10000)) {
            val batchSize = agent.computeBatchSize(catalogSize)
            val totalProcessed = batchSize * 600  // 6 cycles/day × 100 days
            assertTrue(totalProcessed >= catalogSize,
                "For catalog of $catalogSize, $batchSize × 600 = $totalProcessed should cover the catalog")
            val minBatch = maxOf(1, (catalogSize + 599) / 600)
            assertEquals(minBatch, batchSize,
                "Batch size for $catalogSize should be ceil($catalogSize/600)")
        }
    }

    // ========== Request pacing tests ==========

    @Test
    fun `title refresh sleeps 500ms between each API call`() {
        val clock = TestClock()
        val tmdb = FakeTmdbService(titlePopularity = mapOf(
            TmdbId(100, MediaType.MOVIE) to 75.0,
            TmdbId(200, MediaType.MOVIE) to 80.0,
            TmdbId(300, MediaType.MOVIE) to 90.0
        ))

        insertTitle("Movie A", 100)
        insertTitle("Movie B", 200)
        insertTitle("Movie C", 300)

        // 3 titles → batch size 1 → 1 API call with 1 sleep
        val agent = testAgent(tmdb, clock)
        agent.refreshTitles()

        assertEquals(1, tmdb.titleCalls.size)
        assertEquals(1, clock.sleepRequests.size)
        assertEquals(500.milliseconds, clock.sleepRequests[0])
    }

    @Test
    fun `multiple titles in batch each get a 500ms sleep`() {
        val clock = TestClock()
        val titlePops = mutableMapOf<TmdbId, Double>()
        for (i in 1..700) {
            insertTitle("Movie $i", i)
            titlePops[TmdbId(i, MediaType.MOVIE)] = i * 1.0
        }

        val tmdb = FakeTmdbService(titlePopularity = titlePops)
        val agent = testAgent(tmdb, clock)
        agent.refreshTitles()

        // 700 titles → batch size = ceil(700/600) = 2
        assertEquals(2, tmdb.titleCalls.size)
        assertEquals(2, clock.sleepRequests.size)
        assertTrue(clock.sleepRequests.all { it == 500.milliseconds },
            "Each API call should be preceded by a 500ms sleep")
    }

    @Test
    fun `cast member refresh sleeps 500ms between each person lookup`() {
        val clock = TestClock()
        val title = insertTitle("Movie A", 100)
        insertCastMember(title.id!!, 1001, "Actor A")
        insertCastMember(title.id!!, 1002, "Actor B")

        val tmdb = FakeTmdbService(personPopularity = mapOf(1001 to 25.0, 1002 to 30.0))
        val agent = testAgent(tmdb, clock)
        clock.sleepRequests.clear()
        agent.refreshCastMembers()

        // 2 distinct persons, batch size = 1, so only 1 API call
        assertEquals(1, tmdb.personCalls.size)
        assertEquals(1, clock.sleepRequests.size)
        assertEquals(500.milliseconds, clock.sleepRequests[0])
    }

    // ========== Error handling tests ==========

    @Test
    fun `title refresh stops batch on API error`() {
        val clock = TestClock()
        Title.deleteAll()

        val titlePops = mutableMapOf<TmdbId, Double>()
        for (i in 1..700) {
            insertTitle("Movie $i", i)
            titlePops[TmdbId(i, MediaType.MOVIE)] = i * 1.0
        }
        // 700 titles → batch size 2
        // Fail on all IDs — first call fails, should stop before second
        val tmdb = FakeTmdbService(
            titlePopularity = titlePops,
            failOnTitleIds = (1..700).map { TmdbId(it, MediaType.MOVIE) }.toSet()
        )

        val agent = testAgent(tmdb, clock)
        agent.refreshTitles()

        assertEquals(1, tmdb.titleCalls.size, "Should stop after first API error")
        // The failed title should NOT be marked as refreshed
        val calledId = tmdb.titleCalls[0]
        val failedTitle = Title.findAll().first { it.tmdb_id == calledId.id }
        assertNull(failedTitle.popularity_refreshed_at,
            "Failed title should not be marked as refreshed")
    }

    @Test
    fun `cast member refresh stops batch on API error`() {
        val clock = TestClock()
        val titles = (1..5).map { insertTitle("Movie $it", it) }
        for (i in 1..700) {
            insertCastMember(titles[i % 5].id!!, i, "Actor $i")
        }
        // 700 distinct persons → batch size 2
        // Fail on all — first call fails, stops
        val tmdb = FakeTmdbService(
            personPopularity = (1..700).associate { it to it * 1.0 },
            failOnPersonIds = (1..700).toSet()
        )

        val agent = testAgent(tmdb, clock)
        clock.sleepRequests.clear()
        agent.refreshCastMembers()

        assertEquals(1, tmdb.personCalls.size, "Should stop after first person API error")
    }

    @Test
    fun `title not found on TMDB still gets marked as refreshed`() {
        val clock = TestClock()
        insertTitle("Gone Movie", 999, popularity = 10.0)

        val tmdb = FakeTmdbService()  // returns found=false for all
        val agent = testAgent(tmdb, clock)
        agent.refreshTitles()

        val title = Title.findAll().single()
        assertNotNull(title.popularity_refreshed_at,
            "Title not found on TMDB should still be marked as refreshed")
        assertEquals(10.0, title.popularity,
            "Popularity should remain unchanged when title not found")
    }

    // ========== Oldest-first ordering tests ==========

    @Test
    fun `titles with null refreshedAt are picked first`() {
        val clock = TestClock()
        insertTitle("Old Movie", 100, refreshedAt = clock.now().minusDays(10))
        insertTitle("Never Refreshed", 200, refreshedAt = null)

        val tmdb = FakeTmdbService(titlePopularity = mapOf(
            TmdbId(100, MediaType.MOVIE) to 60.0,
            TmdbId(200, MediaType.MOVIE) to 70.0
        ))

        val agent = testAgent(tmdb, clock)
        agent.refreshTitles()

        assertEquals(1, tmdb.titleCalls.size)
        assertEquals(TmdbId(200, MediaType.MOVIE), tmdb.titleCalls[0],
            "Should pick the never-refreshed title first")
    }

    @Test
    fun `titles with oldest refreshedAt are picked before recent ones`() {
        val clock = TestClock()
        insertTitle("Recent", 100, refreshedAt = clock.now().minusDays(1))
        insertTitle("Oldest", 200, refreshedAt = clock.now().minusDays(30))
        insertTitle("Middle", 300, refreshedAt = clock.now().minusDays(15))

        val tmdb = FakeTmdbService(titlePopularity = mapOf(
            TmdbId(100, MediaType.MOVIE) to 60.0,
            TmdbId(200, MediaType.MOVIE) to 70.0,
            TmdbId(300, MediaType.MOVIE) to 80.0
        ))

        val agent = testAgent(tmdb, clock)
        agent.refreshTitles()

        assertEquals(1, tmdb.titleCalls.size)
        assertEquals(TmdbId(200, MediaType.MOVIE), tmdb.titleCalls[0],
            "Should pick the oldest-refreshed title")
    }

    // ========== Personal video exclusion ==========

    @Test
    fun `personal videos are excluded from refresh`() {
        val clock = TestClock()
        insertTitle("Home Movie", 100, mediaType = MediaType.PERSONAL.name)
        insertTitle("Real Movie", 200, mediaType = "MOVIE")

        val tmdb = FakeTmdbService(titlePopularity = mapOf(
            TmdbId(200, MediaType.MOVIE) to 70.0
        ))

        val agent = testAgent(tmdb, clock)
        agent.refreshTitles()

        assertEquals(1, tmdb.titleCalls.size)
        assertEquals(TmdbId(200, MediaType.MOVIE), tmdb.titleCalls[0],
            "Should only refresh the non-personal title")
    }

    // ========== Cast member refresh tests ==========

    @Test
    fun `cast refresh updates all rows for same person`() {
        val clock = TestClock()
        val t1 = insertTitle("Movie A", 100)
        val t2 = insertTitle("Movie B", 200)

        insertCastMember(t1.id!!, 42, "Actor X", popularity = 10.0)
        insertCastMember(t2.id!!, 42, "Actor X", popularity = 10.0)

        val tmdb = FakeTmdbService(personPopularity = mapOf(42 to 99.0))
        val agent = testAgent(tmdb, clock)
        clock.sleepRequests.clear()
        agent.refreshCastMembers()

        assertEquals(1, tmdb.personCalls.size, "Should make only 1 API call for person 42")
        assertEquals(42, tmdb.personCalls[0])

        val members = CastMember.findAll().filter { it.tmdb_person_id == 42 }
        assertEquals(2, members.size)
        assertTrue(members.all { it.popularity == 99.0 },
            "All rows for same person should get updated popularity")
        assertTrue(members.all { it.popularity_refreshed_at != null },
            "All rows should be marked as refreshed")
    }

    // ========== Popularity value update tests ==========

    @Test
    fun `title popularity is updated to new TMDB value`() {
        val clock = TestClock()
        insertTitle("Movie", 100, popularity = 50.0)

        val tmdb = FakeTmdbService(titlePopularity = mapOf(
            TmdbId(100, MediaType.MOVIE) to 150.0
        ))

        val agent = testAgent(tmdb, clock)
        agent.refreshTitles()

        val title = Title.findAll().single()
        assertEquals(150.0, title.popularity)
        assertNotNull(title.popularity_refreshed_at)
    }

    // ========== Daemon lifecycle tests ==========

    @Test
    fun `agent starts and stops cleanly`() {
        val clock = TestClock()
        val agent = PopularityRefreshAgent(FakeTmdbService(), clock)

        agent.start()
        agent.start()  // idempotent

        agent.stop()
        Thread.sleep(50)
    }

    // ========== Empty catalog tests ==========

    @Test
    fun `empty catalog makes zero API calls`() {
        val clock = TestClock()
        val tmdb = FakeTmdbService()
        val agent = testAgent(tmdb, clock)

        agent.refreshTitles()
        agent.refreshCastMembers()

        assertEquals(0, tmdb.titleCalls.size, "No title API calls for empty catalog")
        assertEquals(0, tmdb.personCalls.size, "No person API calls for empty catalog")
        assertEquals(0, clock.sleepRequests.size, "No sleeps for empty catalog")
    }
}
