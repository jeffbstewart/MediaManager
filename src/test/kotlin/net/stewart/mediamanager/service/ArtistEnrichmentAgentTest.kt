package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
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
 * Tests for [ArtistEnrichmentAgent] — the per-batch loop, gap detection,
 * cooldown handling, and Wikipedia fallback. Mirrors AuthorEnrichmentAgentTest.
 */
class ArtistEnrichmentAgentTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:artistenrichtest;DB_CLOSE_DELAY=-1"
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
    private lateinit var agent: ArtistEnrichmentAgent
    private lateinit var mbid: String

    @Before
    fun reset() {
        Artist.deleteAll()
        clock = TestClock()
        http = FakeHttpFetcher()
        agent = ArtistEnrichmentAgent(clock = clock, http = http)
        agent.running.set(true)
        mbid = java.util.UUID.randomUUID().toString()
    }

    private fun seedArtist(
        name: String = "Pink Floyd",
        artistMbid: String? = mbid,
        biography: String? = null,
        wikidataId: String? = null,
        beginDate: LocalDate? = null,
        headshot: String? = null,
        lastAttemptAt: LocalDateTime? = null,
        streak: Int = 0,
    ): Artist = Artist(
        name = name,
        sort_name = name,
        artist_type = ArtistType.GROUP.name,
        biography = biography,
        headshot_path = headshot,
        musicbrainz_artist_id = artistMbid,
        wikidata_id = wikidataId,
        begin_date = beginDate,
        enrichment_last_attempt_at = lastAttemptAt,
        enrichment_no_progress_streak = streak,
    ).apply { save() }

    private fun mbArtistUrl(id: String) =
        "https://musicbrainz.org/ws/2/artist/$id?inc=url-rels&fmt=json"

    // ---------------------- needsEnrichment ----------------------

    @Test
    fun `needsEnrichment is false when neither MB nor wikidata id is set`() {
        val a = seedArtist(artistMbid = null, biography = "x")
        assertFalse(agent.needsEnrichment(a))
    }

    @Test
    fun `needsEnrichment is true when MB id is set and wikidata is missing`() {
        val a = seedArtist()
        assertTrue(agent.needsEnrichment(a))
    }

    @Test
    fun `needsEnrichment is true when wikidata id is set and bio is short`() {
        val a = seedArtist(
            artistMbid = null,
            biography = "tiny",
            wikidataId = "Q42",
            beginDate = LocalDate.of(1965, 1, 1),
            headshot = "https://example.com/pf.jpg",
        )
        assertTrue(agent.needsEnrichment(a),
            "wikidata + short bio should still trigger Wikipedia fallback")
    }

    @Test
    fun `needsEnrichment is false when fully populated`() {
        val a = seedArtist(
            biography = "A".repeat(300),
            wikidataId = "Q42",
            beginDate = LocalDate.of(1965, 1, 1),
            headshot = "https://example.com/pf.jpg",
        )
        assertFalse(agent.needsEnrichment(a))
    }

    // ---------------------- enrichBatch ----------------------

    @Test
    fun `enrichBatch returns 0 when nothing needs enrichment`() {
        seedArtist(
            biography = "A".repeat(300),
            wikidataId = "Q42",
            beginDate = LocalDate.of(1965, 1, 1),
            headshot = "https://example.com/pf.jpg",
        )
        assertEquals(0, agent.enrichBatch())
    }

    @Test
    fun `enrichBatch fills begin_date + wikidata_id + biography from MusicBrainz`() {
        val a = seedArtist()
        http.responses = mapOf(
            mbArtistUrl(mbid) to """
                {
                    "disambiguation": "English progressive rock band",
                    "life-span": {"begin": "1965-04-01", "ended": "false"},
                    "relations": [
                        {"type": "wikidata",
                         "url": {"resource": "https://www.wikidata.org/wiki/Q2306"}}
                    ]
                }
            """.trimIndent(),
        )

        val processed = agent.enrichBatch()
        assertEquals(1, processed)
        val refreshed = Artist.findById(a.id!!)!!
        assertEquals("English progressive rock band", refreshed.biography)
        assertEquals("Q2306", refreshed.wikidata_id)
        assertEquals(LocalDate.of(1965, 4, 1), refreshed.begin_date)
        assertEquals(0, refreshed.enrichment_no_progress_streak,
            "progress made → streak stays at 0")
    }

    @Test
    fun `enrichBatch parses end_date when life-span ended is true`() {
        val a = seedArtist()
        http.responses = mapOf(
            mbArtistUrl(mbid) to """
                {
                    "life-span": {"begin": "1995", "end": "2005", "ended": "true"},
                    "relations": []
                }
            """.trimIndent(),
        )

        agent.enrichBatch()
        val refreshed = Artist.findById(a.id!!)!!
        assertEquals(LocalDate.of(1995, 1, 1), refreshed.begin_date)
        assertEquals(LocalDate.of(2005, 1, 1), refreshed.end_date)
    }

    @Test
    fun `enrichBatch ignores non-wikidata relations`() {
        val a = seedArtist()
        http.responses = mapOf(
            mbArtistUrl(mbid) to """
                {
                    "life-span": {"begin": "1980"},
                    "relations": [
                        {"type": "official homepage",
                         "url": {"resource": "https://example.com"}},
                        {"type": "discogs",
                         "url": {"resource": "https://discogs.com/artist/1234"}}
                    ]
                }
            """.trimIndent(),
        )

        agent.enrichBatch()
        val refreshed = Artist.findById(a.id!!)!!
        assertNull(refreshed.wikidata_id,
            "non-wikidata relations don't fill wikidata_id")
    }

    @Test
    fun `enrichBatch increments streak when MB miss leaves gaps unfilled`() {
        val a = seedArtist(streak = 0)
        // No script for the MB URL → fetch returns null → no progress.
        agent.enrichBatch()
        val refreshed = Artist.findById(a.id!!)!!
        assertNull(refreshed.biography)
        assertEquals(1, refreshed.enrichment_no_progress_streak)
    }

    @Test
    fun `enrichBatch skips artists still in their cooldown window`() {
        clock.set(LocalDateTime.of(2026, 5, 1, 12, 0))
        seedArtist(streak = 1, lastAttemptAt = clock.now().minusMinutes(30))
        assertEquals(0, agent.enrichBatch())
    }

    @Test
    fun `enrichBatch retries after the cooldown window opens`() {
        clock.set(LocalDateTime.of(2026, 5, 3, 12, 0))
        seedArtist(streak = 1, lastAttemptAt = LocalDateTime.of(2026, 5, 1, 12, 0))
        assertEquals(1, agent.enrichBatch())
    }

    @Test
    fun `enrichBatch waits MB_GAP between successive artist fetches`() {
        seedArtist(name = "First", artistMbid = java.util.UUID.randomUUID().toString())
        seedArtist(name = "Second", artistMbid = java.util.UUID.randomUUID().toString())
        agent.enrichBatch()
        assertEquals(1, clock.sleepRequests.size,
            "exactly one MB_GAP between two consecutive fetches")
    }

    // ---------------------- Wikipedia fallback ----------------------

    @Test
    fun `enrichOne walks MB then Wikidata then Wikipedia and fills the headshot`() {
        val a = seedArtist()
        http.responses = mapOf(
            mbArtistUrl(mbid) to """
                {
                    "disambiguation": "Short MB blurb.",
                    "life-span": {"begin": "1965"},
                    "relations": [
                        {"type": "wikidata",
                         "url": {"resource": "https://www.wikidata.org/wiki/Q2306"}}
                    ]
                }
            """.trimIndent(),
            "https://www.wikidata.org/wiki/Special:EntityData/Q2306.json" to """
                {"entities": {"Q2306": {"sitelinks": {"enwiki": {"title": "Pink Floyd"}}}}}
            """.trimIndent(),
            "https://en.wikipedia.org/api/rest_v1/page/summary/Pink_Floyd" to """
                {
                    "extract": "${("Pink Floyd are an English rock band formed in London in 1965. " +
                        "Distinguished by their philosophical lyrics, sonic experimentation, extended " +
                        "compositions, and elaborate live shows, they became a leading band of the " +
                        "progressive and psychedelic rock genres.")}",
                    "thumbnail": {"source": "https://upload.wikimedia.org/pf.jpg"}
                }
            """.trimIndent(),
        )

        agent.enrichOne(a)
        val refreshed = Artist.findById(a.id!!)!!
        assertTrue(refreshed.biography!!.contains("philosophical lyrics"),
            "Wikipedia bio should win when longer than MB's disambiguation")
        assertEquals("https://upload.wikimedia.org/pf.jpg",
            refreshed.headshot_path)
        val urls = http.requestedUrls
        assertEquals(3, urls.size)
        assertTrue(urls[0].contains("musicbrainz.org"))
        assertTrue(urls[1].contains("wikidata.org"))
        assertTrue(urls[2].contains("wikipedia.org"))
    }

    @Test
    fun `enrichOne skips Wikipedia branch when wikidata_id stays null`() {
        val a = seedArtist()
        http.responses = mapOf(
            mbArtistUrl(mbid) to """
                {"life-span": {"begin": "1980"}, "relations": []}
            """.trimIndent(),
        )

        agent.enrichOne(a)
        // Only the MB fetch — no Wikidata, no Wikipedia.
        assertEquals(1, http.requestedUrls.size)
    }

    @Test
    fun `enrichOne preserves a long MB bio when Wikipedia's extract is shorter`() {
        val longBio = "A".repeat(500)
        val a = seedArtist(biography = longBio, wikidataId = "Q2306")
        http.responses = mapOf(
            "https://www.wikidata.org/wiki/Special:EntityData/Q2306.json" to """
                {"entities": {"Q2306": {"sitelinks": {"enwiki": {"title": "Test"}}}}}
            """.trimIndent(),
            "https://en.wikipedia.org/api/rest_v1/page/summary/Test" to """
                {"extract": "Short Wikipedia stub line for the page."}
            """.trimIndent(),
        )

        agent.enrichOne(a)
        assertEquals(longBio, Artist.findById(a.id!!)!!.biography,
            "longer MB bio must survive a shorter Wikipedia extract")
    }

    // ---------------------- start / stop lifecycle ----------------------

    @Test
    fun `start is idempotent and stop flips the running flag back`() {
        val realAgent = ArtistEnrichmentAgent()
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
