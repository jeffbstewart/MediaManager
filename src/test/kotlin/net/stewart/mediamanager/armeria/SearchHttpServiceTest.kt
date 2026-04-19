package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.service.SearchIndexService
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the unified search endpoint, one test per entity
 * kind the Angular app needs to be able to surface.
 *
 * Stands up a real H2 schema via Flyway, seeds a compact fixture (one of
 * each: book title + author, album title + artist + track), then exercises
 * [SearchHttpService.searchForUser] directly — bypassing the HTTP layer
 * since the request context plays no role in the logic being tested.
 *
 * See docs/MUSIC.md and docs/BOOKS.md for the entity shapes.
 */
class SearchHttpServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:searchtest;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
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

    private val service = SearchHttpService()

    /** Admin user — skips rating-ceiling filtering so the fixture is visible. */
    private val adminUser: AppUser by lazy {
        AppUser.findAll().firstOrNull { it.username == "searchtest-admin" }
            ?: AppUser(
                username = "searchtest-admin",
                display_name = "Search Test",
                password_hash = "x",
                access_level = 2,
                created_at = LocalDateTime.now(),
                updated_at = LocalDateTime.now()
            ).also { it.save() }
    }

    @Before
    fun seedFixture() {
        // Wipe anything the previous test left behind. FK-safe order:
        // tracks → album title → artists; authors → book title.
        Track.deleteAll()
        Title.deleteAll()
        Artist.deleteAll()
        Author.deleteAll()
        SearchIndexService.clear()

        val now = LocalDateTime.now()

        // Book title + author.
        val bookTitle = Title(
            name = "The Odyssey",
            media_type = MediaType.BOOK.name,
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            created_at = now,
            updated_at = now
        ).also { it.save() }
        Author(
            name = "Homer",
            sort_name = "Homer",
            biography = "Ancient Greek poet traditionally credited with the Iliad and the Odyssey.",
            created_at = now,
            updated_at = now
        ).also { it.save() }

        // Album title + artist + track.
        val albumTitle = Title(
            name = "Kind of Blue",
            media_type = MediaType.ALBUM.name,
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            created_at = now,
            updated_at = now
        ).also { it.save() }
        Artist(
            name = "Miles Davis",
            sort_name = "Davis, Miles",
            artist_type = ArtistType.PERSON.name,
            biography = "Pioneering trumpeter whose sessions introduced modal jazz to a wide audience.",
            created_at = now,
            updated_at = now
        ).also { it.save() }
        Track(
            title_id = albumTitle.id!!,
            track_number = 1,
            disc_number = 1,
            name = "So What",
            created_at = now,
            updated_at = now
        ).also { it.save() }

        // Search index only learns about titles via onTitleChanged /
        // rebuild. Rebuild is cheapest here — fixture is tiny.
        SearchIndexService.rebuild()

        // Sanity: make sure the admin user row exists before we reference it.
        adminUser
    }

    private fun runSearch(query: String): List<Map<String, Any?>> =
        service.searchForUser(query, adminUser, limit = 100)

    private fun typesOf(results: List<Map<String, Any?>>): List<String> =
        results.mapNotNull { it["type"] as? String }

    @Test
    fun `finds book title by name`() {
        val results = runSearch("odyssey")
        val book = results.firstOrNull { it["type"] == "book" }
        assertNotNull(book, "book result missing; got types=${typesOf(results)}")
        assertEquals("The Odyssey", book["name"])
    }

    @Test
    fun `finds author by name`() {
        val results = runSearch("homer")
        val author = results.firstOrNull { it["type"] == "author" }
        assertNotNull(author, "author result missing; got types=${typesOf(results)}")
        assertEquals("Homer", author["name"])
    }

    @Test
    fun `finds album by name`() {
        val results = runSearch("kind of blue")
        val album = results.firstOrNull { it["type"] == "album" }
        assertNotNull(album, "album result missing; got types=${typesOf(results)}")
        assertEquals("Kind of Blue", album["name"])
    }

    @Test
    fun `finds artist by name`() {
        val results = runSearch("miles davis")
        val artist = results.firstOrNull { it["type"] == "artist" }
        assertNotNull(artist, "artist result missing; got types=${typesOf(results)}")
        assertEquals("Miles Davis", artist["name"])
    }

    @Test
    fun `finds track by song name`() {
        val results = runSearch("so what")
        val track = results.firstOrNull { it["type"] == "track" }
        assertNotNull(track, "track result missing; got types=${typesOf(results)}")
        assertEquals("So What", track["name"])
        assertEquals("Kind of Blue", track["album_name"])
        // Track results should carry the album's title_id so the UI can
        // route to the album detail page — there's no per-track screen.
        val titleId = track["title_id"]
        assertTrue(titleId is Number && titleId.toLong() > 0,
            "track result should carry album title_id; got $titleId")
    }

    @Test
    fun `finds artist by biography text`() {
        // "Modal" only appears in Miles Davis's biography, not in his name
        // or any title. Exercises the bio-match fallback.
        val results = runSearch("modal jazz")
        val artist = results.firstOrNull { it["type"] == "artist" }
        assertNotNull(artist, "biography-match artist missing; got types=${typesOf(results)}")
        assertEquals("Miles Davis", artist["name"])
    }
}
