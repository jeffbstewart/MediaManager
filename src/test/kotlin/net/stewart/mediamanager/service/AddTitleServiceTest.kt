package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.EntrySource
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaItemTitleSeason
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AddTitleService] — the catalog entry point shared by
 * the admin add-item flow and gRPC. The TMDB lookup is unauthenticated
 * in the test JVM (no TMDB_API_KEY system property is set), so the
 * service falls back to the "Unknown" name path; the test focuses on
 * the dedup, MediaItem creation, season-string handling, and wish
 * fulfillment side effects.
 */
class AddTitleServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:addtitletest;DB_CLOSE_DELAY=-1"
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

    @Before
    fun reset() {
        WishListItem.deleteAll()
        MediaItemTitleSeason.deleteAll()
        MediaItemTitle.deleteAll()
        TitleSeason.deleteAll()
        Title.deleteAll()
        MediaItem.deleteAll()
        AppUser.deleteAll()
    }

    // ---------------------- parseSeasonsInput ----------------------

    @Test
    fun `parseSeasonsInput returns null on empty or whitespace`() {
        assertNull(AddTitleService.parseSeasonsInput(""))
        assertNull(AddTitleService.parseSeasonsInput("   "))
    }

    @Test
    fun `parseSeasonsInput accepts a single integer`() {
        assertEquals("S2", AddTitleService.parseSeasonsInput("2"))
        assertEquals("S2", AddTitleService.parseSeasonsInput(" 2 "))
    }

    @Test
    fun `parseSeasonsInput expands ranges`() {
        assertEquals("S1, S2, S3", AddTitleService.parseSeasonsInput("1-3"))
        assertEquals("S1, S2, S3", AddTitleService.parseSeasonsInput("1 - 3"))
    }

    @Test
    fun `parseSeasonsInput accepts comma lists with optional S prefix`() {
        assertEquals("S1, S3", AddTitleService.parseSeasonsInput("1,3"))
        assertEquals("S1, S2", AddTitleService.parseSeasonsInput("S1, S2"))
        assertEquals("S1, S2", AddTitleService.parseSeasonsInput("s1, s2"))
        // Trailing empty entries from "1," are filtered out before validation.
        assertEquals("S1", AddTitleService.parseSeasonsInput("1,"))
    }

    @Test
    fun `parseSeasonsInput returns null on completely garbage input`() {
        // Garbage list — split returns at least one part, none parses to int,
        // and the trimmed input as a whole isn't an int either.
        assertNull(AddTitleService.parseSeasonsInput("not-a-number"))
    }

    // ---------------------- addFromTmdb ----------------------

    @Test
    fun `addFromTmdb creates a new title with placeholder name when TMDB is unavailable`() {
        val result = AddTitleService.addFromTmdb(
            tmdbId = 12345,
            mediaType = MediaType.MOVIE,
            mediaFormat = MediaFormat.BLURAY,
        )

        assertFalse(result.alreadyExisted)
        // Without an API key the fallback is "Unknown".
        assertEquals("Unknown", result.titleName)

        val title = Title.findById(result.titleId)!!
        assertEquals(12345, title.tmdb_id)
        assertEquals(MediaType.MOVIE.name, title.media_type)
        assertEquals(EnrichmentStatus.REASSIGNMENT_REQUESTED.name, title.enrichment_status,
            "freshly added titles must be flagged for reassignment so the enrichment agent picks them up")

        // MediaItem + join row created.
        val mediaItem = MediaItem.findAll().single()
        assertEquals(MediaFormat.BLURAY.name, mediaItem.media_format)
        assertEquals(EntrySource.MANUAL.name, mediaItem.entry_source)
        val join = MediaItemTitle.findAll().single()
        assertEquals(mediaItem.id, join.media_item_id)
        assertEquals(title.id, join.title_id)
        assertEquals(1, join.disc_number)
        assertNull(join.seasons)
    }

    @Test
    fun `addFromTmdb is idempotent on the title but always creates a MediaItem`() {
        val first = AddTitleService.addFromTmdb(
            tmdbId = 999, mediaType = MediaType.MOVIE, mediaFormat = MediaFormat.BLURAY
        )
        // Second call with the same TMDB key — Title is reused, second
        // MediaItem (+ join) is added.
        val second = AddTitleService.addFromTmdb(
            tmdbId = 999, mediaType = MediaType.MOVIE, mediaFormat = MediaFormat.UHD_BLURAY
        )
        assertTrue(second.alreadyExisted)
        assertEquals(first.titleId, second.titleId)
        assertEquals(1, Title.findAll().size)
        assertEquals(2, MediaItem.findAll().size)
        assertEquals(2, MediaItemTitle.findAll().size)
    }

    @Test
    fun `addFromTmdb does NOT confuse movie and TV with the same numeric tmdb_id`() {
        // TMDB ids are namespaced by media type — movie 253 and tv 253 must
        // become two distinct Title rows.
        val movie = AddTitleService.addFromTmdb(
            tmdbId = 253, mediaType = MediaType.MOVIE, mediaFormat = MediaFormat.DVD
        )
        val tv = AddTitleService.addFromTmdb(
            tmdbId = 253, mediaType = MediaType.TV, mediaFormat = MediaFormat.DVD
        )
        assertFalse(movie.alreadyExisted)
        assertFalse(tv.alreadyExisted)
        // Two separate titles.
        assertEquals(2, Title.findAll().size)
        val titles = Title.findAll().associateBy { it.media_type }
        assertEquals(253, titles[MediaType.MOVIE.name]?.tmdb_id)
        assertEquals(253, titles[MediaType.TV.name]?.tmdb_id)
    }

    @Test
    fun `addFromTmdb stores a normalized seasons string for TV adds`() {
        AddTitleService.addFromTmdb(
            tmdbId = 800,
            mediaType = MediaType.TV,
            mediaFormat = MediaFormat.BLURAY,
            seasonsInput = "1-3"
        )
        val join = MediaItemTitle.findAll().single()
        assertEquals("S1, S2, S3", join.seasons)
    }

    @Test
    fun `addFromTmdb fulfills active media wishes for the same TMDB key`() {
        val u = AppUser(username = "u", display_name = "U", password_hash = "x")
            .apply { save() }.id!!
        // Create a stale FULFILLED wish — the legacy fulfillMediaWishes
        // re-activates these so the new lifecycle can render.
        val wish = WishListService.addMediaWishForUser(
            u, TmdbId(700, MediaType.MOVIE), "Foo", null, null, null
        )!!
        wish.status = WishStatus.FULFILLED.name
        wish.save()

        AddTitleService.addFromTmdb(
            tmdbId = 700, mediaType = MediaType.MOVIE, mediaFormat = MediaFormat.DVD
        )

        // syncPhysicalOwnership creates a season 0 OWNED row for the title,
        // and fulfillMediaWishes flips the legacy FULFILLED back to ACTIVE.
        val refreshed = WishListItem.findById(wish.id!!)!!
        assertEquals(WishStatus.ACTIVE.name, refreshed.status)
        // Movie -> season 0 with OWNED status.
        val title = Title.findAll().single { it.tmdb_id == 700 }
        val season = TitleSeason.findAll().single { it.title_id == title.id }
        assertEquals(0, season.season_number)
    }
}
