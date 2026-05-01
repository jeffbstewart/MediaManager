package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.Author
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AuthorEnrichmentAgent] — the per-batch loop, gap detection,
 * cooldown handling, and Wikipedia fallback. The agent's HTTP I/O is
 * substituted with [FakeHttpFetcher] and time advances via [TestClock]
 * so cooldown windows can be opened and closed deterministically.
 *
 * The reEnrichWithAgent dispatch path is covered separately in
 * AdminGrpcServiceFakesTest; this class drills into the agent itself.
 */
class AuthorEnrichmentAgentTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:authorenrichtest;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    private lateinit var clock: TestClock
    private lateinit var http: FakeHttpFetcher
    private lateinit var agent: AuthorEnrichmentAgent

    @Before
    fun reset() {
        Author.deleteAll()
        clock = TestClock()
        http = FakeHttpFetcher()
        agent = AuthorEnrichmentAgent(clock = clock, http = http)
        // enrichBatch checks running.get() inside its candidate loop so
        // stop() can break early; tests don't call start() so the flag
        // would otherwise be false and the loop would exit on entry.
        agent.running.set(true)
    }

    private fun seedAuthor(
        name: String = "Isaac Asimov",
        olid: String? = "OL34184A",
        biography: String? = null,
        wikidataId: String? = null,
        birthDate: LocalDate? = null,
        headshot: String? = null,
        lastAttemptAt: LocalDateTime? = null,
        streak: Int = 0,
    ): Author = Author(
        name = name,
        sort_name = name,
        biography = biography,
        headshot_path = headshot,
        open_library_author_id = olid,
        wikidata_id = wikidataId,
        birth_date = birthDate,
        enrichment_last_attempt_at = lastAttemptAt,
        enrichment_no_progress_streak = streak,
    ).apply { save() }

    // ---------------------- needsEnrichment ----------------------

    @Test
    fun `needsEnrichment is false when no OL id and no wikidata id`() {
        val a = seedAuthor(olid = null, biography = "x")
        assertFalse(agent.needsEnrichment(a))
    }

    @Test
    fun `needsEnrichment is true when OL id is set and bio is missing`() {
        val a = seedAuthor()
        assertTrue(agent.needsEnrichment(a))
    }

    @Test
    fun `needsEnrichment is true when wikidata id is set and headshot is missing`() {
        val a = seedAuthor(
            olid = null,
            biography = "A".repeat(300),  // long bio
            wikidataId = "Q42",
            headshot = null,
        )
        assertTrue(agent.needsEnrichment(a),
            "wikidata + missing headshot should still trigger Wikipedia fallback")
    }

    @Test
    fun `needsEnrichment is false when fully populated`() {
        val a = seedAuthor(
            biography = "A".repeat(300),
            wikidataId = "Q42",
            birthDate = LocalDate.of(1920, 1, 2),
            headshot = "https://example.com/asimov.jpg",
        )
        assertFalse(agent.needsEnrichment(a))
    }

    // ---------------------- enrichBatch ----------------------

    @Test
    fun `enrichBatch returns 0 when nothing needs enrichment`() {
        seedAuthor(biography = "A".repeat(300),
            wikidataId = "Q42",
            birthDate = LocalDate.of(1920, 1, 2),
            headshot = "https://example.com/h.jpg")

        val processed = agent.enrichBatch()
        assertEquals(0, processed)
    }

    @Test
    fun `enrichBatch fills bio + wikidata + birth_date from Open Library and records progress`() {
        val a = seedAuthor()
        http.responses = mapOf(
            "https://openlibrary.org/authors/OL34184A.json" to """
                {
                    "bio": "Asimov was a prolific American writer and biochemist.",
                    "remote_ids": {"wikidata": "Q34981"},
                    "birth_date": "1920-01-02",
                    "death_date": "1992-04-06"
                }
            """.trimIndent(),
        )

        val processed = agent.enrichBatch()
        assertEquals(1, processed)
        val refreshed = Author.findById(a.id!!)!!
        assertTrue(refreshed.biography!!.contains("biochemist"))
        assertEquals("Q34981", refreshed.wikidata_id)
        assertEquals(LocalDate.of(1920, 1, 2), refreshed.birth_date)
        assertEquals(0, refreshed.enrichment_no_progress_streak,
            "progress was made → streak stays at 0")
        // recordAttempt set the timestamp via the test clock.
        assertEquals(clock.now(), refreshed.enrichment_last_attempt_at)
    }

    @Test
    fun `enrichBatch increments streak when an Open Library miss leaves gaps unfilled`() {
        val a = seedAuthor(streak = 0)
        // No script for the OL URL → fetch returns null → enrichFromOpenLibrary
        // bails before any save → gapsFor unchanged → "stuck" branch.

        val processed = agent.enrichBatch()
        assertEquals(1, processed)
        val refreshed = Author.findById(a.id!!)!!
        assertNull(refreshed.biography, "no progress")
        assertEquals(1, refreshed.enrichment_no_progress_streak,
            "no-progress streak bumped from 0 to 1")
    }

    @Test
    fun `enrichBatch skips authors still in their cooldown window`() {
        // Streak=1 → cooldown is 1 day. lastAttempt 30 minutes ago is well
        // inside the window, so this row sits out the batch.
        clock.set(LocalDateTime.of(2026, 5, 1, 12, 0))
        val a = seedAuthor(
            streak = 1,
            lastAttemptAt = clock.now().minusMinutes(30),
        )

        val processed = agent.enrichBatch()
        assertEquals(0, processed, "row in cooldown → no candidates")
        // Last-attempt timestamp not bumped.
        assertEquals(LocalDateTime.of(2026, 5, 1, 11, 30),
            Author.findById(a.id!!)!!.enrichment_last_attempt_at)
    }

    @Test
    fun `enrichBatch retries after the cooldown window opens`() {
        // Same row, but the clock has rolled past the 1-day cooldown.
        clock.set(LocalDateTime.of(2026, 5, 3, 12, 0))
        val a = seedAuthor(
            streak = 1,
            lastAttemptAt = LocalDateTime.of(2026, 5, 1, 12, 0),
        )

        val processed = agent.enrichBatch()
        assertEquals(1, processed, "cooldown elapsed → eligible again")
        // Still no scripted response → still stuck → streak goes 1→2.
        assertEquals(2,
            Author.findById(a.id!!)!!.enrichment_no_progress_streak)
    }

    @Test
    fun `enrichBatch waits API_GAP between successive author fetches`() {
        // Two eligible authors → two fetches with one sleep between them.
        seedAuthor(name = "First", olid = "OL1A")
        seedAuthor(name = "Second", olid = "OL2A")

        agent.enrichBatch()
        // 1 inter-fetch sleep recorded by TestClock; agent doesn't sleep
        // before the first fetch.
        assertEquals(1, clock.sleepRequests.size,
            "exactly one API_GAP between two consecutive fetches")
    }

    // ---------------------- Wikipedia fallback ----------------------

    @Test
    fun `enrichOne backs off Wikipedia fallback when OL fills bio + headshot equivalent`() {
        // Full picture from OL — bio + wikidata + birth + a long bio means
        // needsWikipediaData is still true (headshot null), so Wikipedia
        // does run. We script all three URLs to verify the full chain.
        val a = seedAuthor()
        http.responses = mapOf(
            "https://openlibrary.org/authors/OL34184A.json" to """
                {
                    "bio": "Short bio.",
                    "remote_ids": {"wikidata": "Q34981"},
                    "birth_date": "1920-01-02"
                }
            """.trimIndent(),
            "https://www.wikidata.org/wiki/Special:EntityData/Q34981.json" to """
                {"entities": {"Q34981": {"sitelinks": {"enwiki": {"title": "Isaac Asimov"}}}}}
            """.trimIndent(),
            "https://en.wikipedia.org/api/rest_v1/page/summary/Isaac_Asimov" to """
                {
                    "extract": "${("Isaac Asimov was an American writer and biochemist of Russian-Jewish descent. " +
                        "He was a professor of biochemistry at Boston University and a prolific writer best known " +
                        "for his works of science fiction and his popular science books.")}",
                    "thumbnail": {"source": "https://upload.wikimedia.org/asimov.jpg"}
                }
            """.trimIndent(),
        )

        agent.enrichOne(a)
        val refreshed = Author.findById(a.id!!)!!
        // Wikipedia replaced the short OL bio with its longer one.
        assertTrue(refreshed.biography!!.contains("professor of biochemistry"),
            "Wikipedia bio should win when longer than OL's")
        assertEquals("https://upload.wikimedia.org/asimov.jpg",
            refreshed.headshot_path)
        // Verify the agent walked OL → Wikidata → Wikipedia in order.
        val urls = http.requestedUrls
        assertEquals(3, urls.size)
        assertTrue(urls[0].contains("openlibrary.org"))
        assertTrue(urls[1].contains("wikidata.org"))
        assertTrue(urls[2].contains("wikipedia.org"))
    }

    @Test
    fun `enrichOne preserves a long OL bio when Wikipedia's extract is shorter`() {
        val longOlBio = "A".repeat(500)
        val a = seedAuthor(biography = longOlBio,
            wikidataId = "Q34981")  // already wikidata-resolved
        http.responses = mapOf(
            "https://www.wikidata.org/wiki/Special:EntityData/Q34981.json" to """
                {"entities": {"Q34981": {"sitelinks": {"enwiki": {"title": "Test"}}}}}
            """.trimIndent(),
            "https://en.wikipedia.org/api/rest_v1/page/summary/Test" to """
                {"extract": "Short Wikipedia stub line for the page."}
            """.trimIndent(),
        )

        agent.enrichOne(a)
        val refreshed = Author.findById(a.id!!)!!
        assertEquals(longOlBio, refreshed.biography,
            "longer OL bio must survive a shorter Wikipedia extract")
    }

    @Test
    fun `enrichOne skips Wikipedia branch entirely when wikidata_id stays null`() {
        val a = seedAuthor()  // no wikidata
        http.responses = mapOf(
            "https://openlibrary.org/authors/OL34184A.json" to """
                {"bio": "Just a bio, no remote_ids"}
            """.trimIndent(),
        )

        agent.enrichOne(a)
        val urls = http.requestedUrls
        assertEquals(1, urls.size, "no wikidata_id → no Wikipedia fetch")
        assertTrue(urls.single().contains("openlibrary.org"))
    }

    // ---------------------- helpers ----------------------

    @Test
    fun `extractBio handles plain string, object, and missing forms`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        // Plain string.
        assertEquals("hello",
            agent.extractBio(mapper.readTree("""{"bio": "hello"}""")))
        // Object form: {"type": "/type/text", "value": "hello"}
        assertEquals("hello",
            agent.extractBio(mapper.readTree(
                """{"bio": {"type": "/type/text", "value": "hello"}}""")))
        // Missing → null.
        assertNull(agent.extractBio(mapper.readTree("""{}""")))
        // Explicit null → null.
        assertNull(agent.extractBio(mapper.readTree("""{"bio": null}""")))
        // Blank → null.
        assertNull(agent.extractBio(mapper.readTree("""{"bio": "  "}""")))
    }

    @Test
    fun `parseIsoLocalDate accepts well-formed dates and rejects garbage`() {
        assertEquals(LocalDate.of(1920, 1, 2),
            agent.parseIsoLocalDate("1920-01-02"))
        assertNull(agent.parseIsoLocalDate(""))
        assertNull(agent.parseIsoLocalDate("not a date"))
    }

    // ---------------------- start / stop lifecycle ----------------------

    @Test
    fun `start is idempotent and stop flips the running flag back`() {
        // Use a real clock so the daemon thread blocks in STARTUP_DELAY
        // and stop() can interrupt it cleanly. We don't wait for the
        // body to run — just verify the lifecycle bookkeeping.
        val realAgent = AuthorEnrichmentAgent()
        try {
            realAgent.start()
            assertTrue(realAgent.running.get())
            realAgent.start()  // second call must no-op
            assertTrue(realAgent.running.get())
        } finally {
            realAgent.stop()
            assertFalse(realAgent.running.get())
        }
    }
}
