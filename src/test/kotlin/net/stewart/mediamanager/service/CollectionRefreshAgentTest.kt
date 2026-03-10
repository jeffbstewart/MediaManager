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
 * Tests for CollectionRefreshAgent verifying:
 * a) API error handling (stops batch on errors)
 * b) Request pacing (500ms gaps via clock.sleep)
 * c) Batch sizing (~1% per day across 6 cycles)
 * d) Oldest-first refresh ordering
 * e) Collection data is updated on refresh
 * f) Not-found collections get fetched_at updated
 */
class CollectionRefreshAgentTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:colrefreshtest;DB_CLOSE_DELAY=-1"
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
        TmdbCollectionPart.deleteAll()
        TmdbCollection.deleteAll()
        Title.deleteAll()
    }

    // --- Mock TMDB service ---

    private class FakeTmdbService(
        private val collectionResults: Map<Int, TmdbCollectionResult> = emptyMap(),
        private val failOnIds: Set<Int> = emptySet()
    ) : TmdbService() {
        val calls = mutableListOf<Int>()

        override fun fetchCollection(collectionId: Int): TmdbCollectionResult {
            calls.add(collectionId)
            if (collectionId in failOnIds) {
                return TmdbCollectionResult(found = false, errorMessage = "HTTP 429")
            }
            return collectionResults[collectionId]
                ?: TmdbCollectionResult(found = false, errorMessage = "Not found")
        }
    }

    // --- Helpers ---

    private fun testAgent(tmdb: FakeTmdbService, clock: TestClock = TestClock()): CollectionRefreshAgent {
        return CollectionRefreshAgent(tmdb, clock).also { it.running.set(true) }
    }

    private fun insertCollection(
        tmdbCollectionId: Int,
        name: String,
        fetchedAt: LocalDateTime? = null
    ): TmdbCollection {
        return TmdbCollection(
            tmdb_collection_id = tmdbCollectionId,
            name = name,
            fetched_at = fetchedAt
        ).also { it.save() }
    }

    private fun insertPart(
        collectionId: Long,
        tmdbMovieId: Int,
        title: String,
        position: Int,
        posterPath: String? = null
    ): TmdbCollectionPart {
        return TmdbCollectionPart(
            collection_id = collectionId,
            tmdb_movie_id = tmdbMovieId,
            title = title,
            position = position,
            poster_path = posterPath
        ).also { it.save() }
    }

    private fun makeCollectionResult(
        collectionId: Int,
        name: String,
        parts: List<TmdbCollectionPartResult>
    ): TmdbCollectionResult {
        return TmdbCollectionResult(
            found = true,
            collectionId = collectionId,
            name = name,
            parts = parts
        )
    }

    // ========== Batch size tests ==========

    @Test
    fun `batch size is 1 for small collection counts`() {
        val agent = CollectionRefreshAgent(FakeTmdbService(), TestClock())
        assertEquals(1, agent.computeBatchSize(1))
        assertEquals(1, agent.computeBatchSize(100))
        assertEquals(1, agent.computeBatchSize(600))
    }

    @Test
    fun `batch size scales with collection count`() {
        val agent = CollectionRefreshAgent(FakeTmdbService(), TestClock())
        assertEquals(2, agent.computeBatchSize(601))
        assertEquals(2, agent.computeBatchSize(1200))
        assertEquals(5, agent.computeBatchSize(3000))
    }

    @Test
    fun `batch size ensures full cycle in about 100 days`() {
        val agent = CollectionRefreshAgent(FakeTmdbService(), TestClock())
        for (total in listOf(1, 50, 100, 500, 1000, 5000)) {
            val batchSize = agent.computeBatchSize(total)
            val totalProcessed = batchSize * 600
            assertTrue(totalProcessed >= total,
                "For $total collections, $batchSize × 600 = $totalProcessed should cover all")
        }
    }

    // ========== Request pacing tests ==========

    @Test
    fun `refresh sleeps 500ms between each API call`() {
        val clock = TestClock()
        val col = insertCollection(100, "Test Collection")

        val tmdb = FakeTmdbService(collectionResults = mapOf(
            100 to makeCollectionResult(100, "Test Collection", listOf(
                TmdbCollectionPartResult(1, "Movie 1", "2020-01-01", 1)
            ))
        ))

        val agent = testAgent(tmdb, clock)
        agent.refreshCollections()

        assertEquals(1, tmdb.calls.size)
        assertEquals(1, clock.sleepRequests.size)
        assertEquals(500.milliseconds, clock.sleepRequests[0])
    }

    @Test
    fun `multiple collections in batch each get a 500ms sleep`() {
        val clock = TestClock()
        val results = mutableMapOf<Int, TmdbCollectionResult>()
        for (i in 1..700) {
            insertCollection(i, "Collection $i")
            results[i] = makeCollectionResult(i, "Collection $i", listOf(
                TmdbCollectionPartResult(i * 10, "Movie $i", "2020-01-01", 1)
            ))
        }

        val tmdb = FakeTmdbService(collectionResults = results)
        val agent = testAgent(tmdb, clock)
        agent.refreshCollections()

        // 700 collections → batch size = ceil(700/600) = 2
        assertEquals(2, tmdb.calls.size)
        assertEquals(2, clock.sleepRequests.size)
        assertTrue(clock.sleepRequests.all { it == 500.milliseconds })
    }

    // ========== Error handling tests ==========

    @Test
    fun `stops batch on API error`() {
        val clock = TestClock()
        for (i in 1..700) {
            insertCollection(i, "Collection $i")
        }
        // Fail on all IDs
        val tmdb = FakeTmdbService(failOnIds = (1..700).toSet())

        val agent = testAgent(tmdb, clock)
        agent.refreshCollections()

        assertEquals(1, tmdb.calls.size, "Should stop after first API error")
    }

    @Test
    fun `collection not found on TMDB still updates fetched_at`() {
        val clock = TestClock()
        insertCollection(999, "Gone Collection")

        val tmdb = FakeTmdbService()  // returns not-found for all
        val agent = testAgent(tmdb, clock)
        agent.refreshCollections()

        val col = TmdbCollection.findAll().single()
        assertNotNull(col.fetched_at,
            "Not-found collection should still get fetched_at updated")
    }

    // ========== Oldest-first ordering tests ==========

    @Test
    fun `collections with null fetched_at are picked first`() {
        val clock = TestClock()
        insertCollection(100, "Recently Fetched",
            fetchedAt = clock.now().minusDays(1))
        insertCollection(200, "Never Fetched",
            fetchedAt = null)

        val tmdb = FakeTmdbService(collectionResults = mapOf(
            200 to makeCollectionResult(200, "Never Fetched", listOf(
                TmdbCollectionPartResult(2000, "Movie", "2020-01-01", 1)
            ))
        ))

        val agent = testAgent(tmdb, clock)
        agent.refreshCollections()

        assertEquals(1, tmdb.calls.size)
        assertEquals(200, tmdb.calls[0],
            "Should pick the never-fetched collection first")
    }

    @Test
    fun `oldest fetched_at is picked before recent ones`() {
        val clock = TestClock()
        insertCollection(100, "Recent", fetchedAt = clock.now().minusDays(1))
        insertCollection(200, "Oldest", fetchedAt = clock.now().minusDays(30))
        insertCollection(300, "Middle", fetchedAt = clock.now().minusDays(15))

        val tmdb = FakeTmdbService(collectionResults = mapOf(
            200 to makeCollectionResult(200, "Oldest", listOf(
                TmdbCollectionPartResult(2000, "Movie", "2020-01-01", 1)
            ))
        ))

        val agent = testAgent(tmdb, clock)
        agent.refreshCollections()

        assertEquals(1, tmdb.calls.size)
        assertEquals(200, tmdb.calls[0],
            "Should pick the oldest-fetched collection")
    }

    // ========== Data update tests ==========

    @Test
    fun `refresh updates collection parts`() {
        val clock = TestClock()
        val col = insertCollection(100, "Test Collection",
            fetchedAt = clock.now().minusDays(10))
        insertPart(col.id!!, 1, "Old Movie", 1)

        // Return updated parts with a new movie added
        val tmdb = FakeTmdbService(collectionResults = mapOf(
            100 to makeCollectionResult(100, "Test Collection", listOf(
                TmdbCollectionPartResult(1, "Old Movie", "2020-01-01", 1, "/old.jpg"),
                TmdbCollectionPartResult(2, "New Sequel", "2024-06-01", 2, "/new.jpg")
            ))
        ))

        val agent = testAgent(tmdb, clock)
        agent.refreshCollections()

        val parts = TmdbCollectionPart.findAll()
            .filter { it.collection_id == col.id }
            .sortedBy { it.position }
        assertEquals(2, parts.size, "Should now have 2 parts")
        assertEquals("Old Movie", parts[0].title)
        assertEquals("/old.jpg", parts[0].poster_path)
        assertEquals("New Sequel", parts[1].title)
        assertEquals("/new.jpg", parts[1].poster_path)
    }

    @Test
    fun `refresh updates fetched_at timestamp`() {
        val clock = TestClock()
        val oldTime = clock.now().minusDays(30)
        insertCollection(100, "Test Collection", fetchedAt = oldTime)

        val tmdb = FakeTmdbService(collectionResults = mapOf(
            100 to makeCollectionResult(100, "Test Collection", listOf(
                TmdbCollectionPartResult(1, "Movie", "2020-01-01", 1)
            ))
        ))

        val agent = testAgent(tmdb, clock)
        agent.refreshCollections()

        val col = TmdbCollection.findAll().single()
        assertNotNull(col.fetched_at)
        assertTrue(col.fetched_at!! > oldTime,
            "fetched_at should be updated to current time")
    }

    // ========== Empty catalog tests ==========

    @Test
    fun `empty collection list makes zero API calls`() {
        val clock = TestClock()
        val tmdb = FakeTmdbService()
        val agent = testAgent(tmdb, clock)

        agent.refreshCollections()

        assertEquals(0, tmdb.calls.size)
        assertEquals(0, clock.sleepRequests.size)
    }

    // ========== Daemon lifecycle tests ==========

    @Test
    fun `agent starts and stops cleanly`() {
        val clock = TestClock()
        val agent = CollectionRefreshAgent(FakeTmdbService(), clock)

        agent.start()
        agent.start()  // idempotent

        agent.stop()
        Thread.sleep(50)
    }
}
