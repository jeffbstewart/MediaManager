package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaItemTitleSeason
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
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
 * Tests for the non-book paths of [WishListService] — transcode wishes,
 * media (movie / TV) wishes, album wishes, and the cross-cutting
 * priority / fulfillment / acquisition paths. Companion to
 * [WishListServiceBookTest], which covers the book paths.
 */
class WishListServiceMediaTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:wishmediatest;DB_CLOSE_DELAY=-1"
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

    private var userId: Long = 0
    private var otherUserId: Long = 0
    private var movieTitleId: Long = 0

    @Before
    fun reset() {
        WishListItem.deleteAll()
        MediaItemTitleSeason.deleteAll()
        MediaItemTitle.deleteAll()
        TitleSeason.deleteAll()
        Title.deleteAll()
        MediaItem.deleteAll()
        AppUser.deleteAll()

        userId = AppUser(username = "u", display_name = "User One",
            password_hash = "x").apply { save() }.id!!
        otherUserId = AppUser(username = "o", display_name = "User Two",
            password_hash = "x").apply { save() }.id!!

        movieTitleId = Title(name = "The Movie", media_type = MediaType.MOVIE.name,
            tmdb_id = 4242).apply { save() }.id!!
    }

    // ---------------------- transcode wishes ----------------------

    @Test
    fun `addTranscodeWish creates a wish on first call`() {
        val wish = WishListService.addTranscodeWishForUser(userId, movieTitleId)
        assertNotNull(wish)
        assertEquals(userId, wish.user_id)
        assertEquals(movieTitleId, wish.title_id)
        assertEquals(WishType.TRANSCODE.name, wish.wish_type)
        assertEquals(WishStatus.ACTIVE.name, wish.status)
    }

    @Test
    fun `addTranscodeWish is idempotent — second call returns null`() {
        val first = WishListService.addTranscodeWishForUser(userId, movieTitleId)
        assertNotNull(first)
        val second = WishListService.addTranscodeWishForUser(userId, movieTitleId)
        assertNull(second)
        assertEquals(1, WishListItem.findAll().size)
    }

    @Test
    fun `removeTranscodeWish hard-deletes the row and signals success`() {
        WishListService.addTranscodeWishForUser(userId, movieTitleId)
        assertTrue(WishListService.removeTranscodeWishForUser(userId, movieTitleId))
        assertEquals(0, WishListItem.findAll().size)
        assertFalse(WishListService.removeTranscodeWishForUser(userId, movieTitleId),
            "second call returns false")
    }

    @Test
    fun `hasActiveTranscodeWish reflects insert and removal`() {
        assertFalse(WishListService.hasActiveTranscodeWishForUser(userId, movieTitleId))
        WishListService.addTranscodeWishForUser(userId, movieTitleId)
        assertTrue(WishListService.hasActiveTranscodeWishForUser(userId, movieTitleId))
        WishListService.removeTranscodeWishForUser(userId, movieTitleId)
        assertFalse(WishListService.hasActiveTranscodeWishForUser(userId, movieTitleId))
    }

    @Test
    fun `getActiveTranscodeWishes returns only this user's active rows newest-first`() {
        val titleA = Title(name = "A", media_type = MediaType.MOVIE.name)
            .apply { save() }.id!!
        val titleB = Title(name = "B", media_type = MediaType.MOVIE.name)
            .apply { save() }.id!!
        WishListService.addTranscodeWishForUser(userId, titleA)
        Thread.sleep(10)
        WishListService.addTranscodeWishForUser(userId, titleB)
        WishListService.addTranscodeWishForUser(otherUserId, titleA)

        val mine = WishListService.getActiveTranscodeWishesForUser(userId)
        assertEquals(2, mine.size)
        assertEquals(titleB, mine[0].title_id, "newest first")
        assertEquals(titleA, mine[1].title_id)
    }

    @Test
    fun `getTranscodeWishCounts and getTranscodeWishedTitleIds aggregate across users`() {
        WishListService.addTranscodeWishForUser(userId, movieTitleId)
        WishListService.addTranscodeWishForUser(otherUserId, movieTitleId)
        val titleB = Title(name = "B", media_type = MediaType.MOVIE.name)
            .apply { save() }.id!!
        WishListService.addTranscodeWishForUser(userId, titleB)

        val counts = WishListService.getTranscodeWishCounts()
        assertEquals(2, counts[movieTitleId])
        assertEquals(1, counts[titleB])

        val ids = WishListService.getTranscodeWishedTitleIds()
        assertEquals(setOf(movieTitleId, titleB), ids)
    }

    @Test
    fun `fulfillTranscodeWishes flips ACTIVE rows to FULFILLED with timestamp`() {
        val wish = WishListService.addTranscodeWishForUser(userId, movieTitleId)!!
        WishListService.addTranscodeWishForUser(otherUserId, movieTitleId)
        // Unrelated title — should NOT be touched.
        val titleB = Title(name = "B", media_type = MediaType.MOVIE.name)
            .apply { save() }.id!!
        val unrelated = WishListService.addTranscodeWishForUser(userId, titleB)!!

        WishListService.fulfillTranscodeWishes(movieTitleId)

        val refreshed = WishListItem.findById(wish.id!!)!!
        assertEquals(WishStatus.FULFILLED.name, refreshed.status)
        assertNotNull(refreshed.fulfilled_at)
        // Other-user wish for the same title is also fulfilled.
        val otherActive = WishListItem.findAll().count {
            it.user_id == otherUserId && it.title_id == movieTitleId &&
                it.status == WishStatus.ACTIVE.name
        }
        assertEquals(0, otherActive)
        // Unrelated wish stays ACTIVE.
        assertEquals(WishStatus.ACTIVE.name, WishListItem.findById(unrelated.id!!)!!.status)
    }

    @Test
    fun `fulfillTranscodeWishes is a no-op when nothing matches`() {
        WishListService.fulfillTranscodeWishes(99_999)
        assertEquals(0, WishListItem.findAll().size)
    }

    @Test
    fun `cancelWishForUser flips ACTIVE to CANCELLED`() {
        val wish = WishListService.addTranscodeWishForUser(userId, movieTitleId)!!
        assertTrue(WishListService.cancelWishForUser(wish.id!!, userId))
        assertEquals(WishStatus.CANCELLED.name, WishListItem.findById(wish.id!!)!!.status)
    }

    @Test
    fun `cancelWishForUser refuses other users and non-active wishes`() {
        val wish = WishListService.addTranscodeWishForUser(userId, movieTitleId)!!
        // Wrong user.
        assertFalse(WishListService.cancelWishForUser(wish.id!!, otherUserId))
        // Already cancelled.
        WishListService.cancelWishForUser(wish.id!!, userId)
        assertFalse(WishListService.cancelWishForUser(wish.id!!, userId))
        // Unknown id.
        assertFalse(WishListService.cancelWishForUser(987_654, userId))
    }

    // ---------------------- media (movie / TV) wishes ----------------------

    @Test
    fun `addMediaWishForUser inserts a movie wish then dedupes on second call`() {
        val tmdb = TmdbId(123, MediaType.MOVIE)
        val first = WishListService.addMediaWishForUser(
            userId, tmdb, "Foo", posterPath = "/foo.jpg",
            releaseYear = 2020, popularity = 12.5
        )
        assertNotNull(first)
        assertEquals(123, first.tmdb_id)
        assertEquals(MediaType.MOVIE.name, first.tmdb_media_type)
        assertEquals(2020, first.tmdb_release_year)

        val second = WishListService.addMediaWishForUser(
            userId, tmdb, "Foo", null, null, null
        )
        assertNull(second, "duplicate active wish should be rejected")
        assertEquals(1, WishListItem.findAll().size)
    }

    @Test
    fun `addMediaWishForUser treats season_number as part of the dedup key`() {
        val tmdb = TmdbId(500, MediaType.TV)
        WishListService.addMediaWishForUser(userId, tmdb, "Show", null, null, null,
            seasonNumber = 1)
        // Same show, different season — must be allowed.
        val second = WishListService.addMediaWishForUser(userId, tmdb, "Show", null, null,
            null, seasonNumber = 2)
        assertNotNull(second)
        assertEquals(2, WishListItem.findAll().size)
        // Same show + season — rejected.
        assertNull(WishListService.addMediaWishForUser(userId, tmdb, "Show",
            null, null, null, seasonNumber = 1))
    }

    @Test
    fun `getVisibleMediaWishesForUser excludes other users and CANCELLED status`() {
        val tmdb = TmdbId(1, MediaType.MOVIE)
        val mine = WishListService.addMediaWishForUser(userId, tmdb, "X",
            null, null, null)!!
        WishListService.addMediaWishForUser(otherUserId, tmdb, "X", null, null, null)

        // Cancel mine — visible-list should drop it (CANCELLED is non-visible).
        WishListService.cancelWishForUser(mine.id!!, userId)

        assertEquals(0, WishListService.getVisibleMediaWishesForUser(userId).size)
        // Other user's wish still visible to them.
        assertEquals(1, WishListService.getVisibleMediaWishesForUser(otherUserId).size)
    }

    @Test
    fun `fulfillMediaWishes reactivates legacy FULFILLED wishes`() {
        val tmdb = TmdbId(7, MediaType.MOVIE)
        val wish = WishListService.addMediaWishForUser(userId, tmdb, "X",
            null, null, null)!!
        // Manually flip to FULFILLED to simulate a legacy state — the fulfill
        // method only acts on FULFILLED rows.
        wish.status = WishStatus.FULFILLED.name
        wish.save()

        WishListService.fulfillMediaWishes(tmdb)

        val refreshed = WishListItem.findById(wish.id!!)!!
        assertEquals(WishStatus.ACTIVE.name, refreshed.status)
        assertNull(refreshed.fulfilled_at)
    }

    // ---------------------- album wishes ----------------------

    private fun albumInput(rgid: String = "rg-1") = WishListService.AlbumWishInput(
        musicBrainzReleaseGroupId = rgid,
        title = "Album",
        primaryArtist = "Artist",
        year = 2010,
        coverReleaseId = "cov-1",
        isCompilation = false
    )

    @Test
    fun `addAlbumWish is idempotent and refreshes display fields`() {
        val first = WishListService.addAlbumWishForUser(userId, albumInput())
        // Re-add with updated metadata — must update the same row, not insert.
        val updated = albumInput().copy(title = "Album (Remastered)", year = 2020)
        val second = WishListService.addAlbumWishForUser(userId, updated)
        assertEquals(first.id, second.id)
        assertEquals(1, WishListItem.findAll().size)
        assertEquals("Album (Remastered)", WishListItem.findById(first.id!!)!!.album_title)
        assertEquals(2020, WishListItem.findById(first.id!!)!!.album_year)
    }

    @Test
    fun `addAlbumWish resurrects a cancelled wish`() {
        val wish = WishListService.addAlbumWishForUser(userId, albumInput())
        assertTrue(WishListService.removeAlbumWishForUser(userId, "rg-1"))
        assertEquals(WishStatus.CANCELLED.name, WishListItem.findById(wish.id!!)!!.status)

        val resurrected = WishListService.addAlbumWishForUser(userId, albumInput())
        assertEquals(wish.id, resurrected.id)
        assertEquals(WishStatus.ACTIVE.name,
            WishListItem.findById(wish.id!!)!!.status)
        assertEquals(1, WishListItem.findAll().size)
    }

    @Test
    fun `removeAlbumWish returns false when nothing active matches`() {
        assertFalse(WishListService.removeAlbumWishForUser(userId, "missing-rg"))
    }

    @Test
    fun `getActiveAlbumWishes and activeAlbumWishReleaseGroupIdsForUser align`() {
        WishListService.addAlbumWishForUser(userId, albumInput("rg-A"))
        WishListService.addAlbumWishForUser(userId, albumInput("rg-B"))
        WishListService.addAlbumWishForUser(otherUserId, albumInput("rg-C"))
        WishListService.removeAlbumWishForUser(userId, "rg-B")

        val active = WishListService.getActiveAlbumWishesForUser(userId)
        assertEquals(1, active.size)
        assertEquals("rg-A", active.single().musicbrainz_release_group_id)

        val ids = WishListService.activeAlbumWishReleaseGroupIdsForUser(userId)
        assertEquals(setOf("rg-A"), ids)
    }

    @Test
    fun `fulfillAlbumWishes flips active rows across users to FULFILLED`() {
        val a = WishListService.addAlbumWishForUser(userId, albumInput("rg-X"))
        val b = WishListService.addAlbumWishForUser(otherUserId, albumInput("rg-X"))
        // Unrelated wish — must not change.
        val c = WishListService.addAlbumWishForUser(userId, albumInput("rg-Y"))

        WishListService.fulfillAlbumWishes("rg-X")

        assertEquals(WishStatus.FULFILLED.name, WishListItem.findById(a.id!!)!!.status)
        assertNotNull(WishListItem.findById(a.id!!)!!.fulfilled_at)
        assertEquals(WishStatus.FULFILLED.name, WishListItem.findById(b.id!!)!!.status)
        assertEquals(WishStatus.ACTIVE.name, WishListItem.findById(c.id!!)!!.status)
    }

    @Test
    fun `fulfillAlbumWishes is a no-op when no wishes match`() {
        WishListService.addAlbumWishForUser(userId, albumInput("rg-A"))
        WishListService.fulfillAlbumWishes("rg-NONE")
        assertEquals(WishStatus.ACTIVE.name,
            WishListItem.findAll().single().status)
    }

    // ---------------------- aggregations & lifecycle ----------------------

    @Test
    fun `getMediaWishVoteCounts groups by tmdb key and orders by votes desc`() {
        val tmdbA = TmdbId(100, MediaType.MOVIE)
        val tmdbB = TmdbId(200, MediaType.MOVIE)
        // Two voters on A, one on B — A must come first.
        WishListService.addMediaWishForUser(userId, tmdbA, "A", null, null, null)
        WishListService.addMediaWishForUser(otherUserId, tmdbA, "A", null, null, null)
        WishListService.addMediaWishForUser(userId, tmdbB, "B", null, null, null)

        val aggregates = WishListService.getMediaWishVoteCounts()
        assertEquals(2, aggregates.size)
        assertEquals(100, aggregates[0].tmdbId)
        assertEquals(2, aggregates[0].voteCount)
        assertEquals(setOf("User One", "User Two"), aggregates[0].voters.toSet())
        assertEquals(200, aggregates[1].tmdbId)
        assertEquals(1, aggregates[1].voteCount)
    }

    @Test
    fun `getAlbumWishVoteCounts marks owned releases READY_TO_WATCH`() {
        Title(name = "Existing Album", media_type = MediaType.ALBUM.name,
            musicbrainz_release_group_id = "rg-OWNED").apply { save() }
        WishListService.addAlbumWishForUser(userId, albumInput("rg-OWNED"))
        WishListService.addAlbumWishForUser(userId, albumInput("rg-MISSING"))

        val aggregates = WishListService.getAlbumWishVoteCounts()
            .associateBy { it.releaseGroupId }
        assertEquals(WishLifecycleStage.READY_TO_WATCH,
            aggregates["rg-OWNED"]!!.lifecycleStage)
        assertNotNull(aggregates["rg-OWNED"]!!.titleId)
        assertEquals(WishLifecycleStage.WISHED_FOR,
            aggregates["rg-MISSING"]!!.lifecycleStage)
        assertNull(aggregates["rg-MISSING"]!!.titleId)
    }

    @Test
    fun `setAcquisitionStatus creates Title and TitleSeason on first call`() {
        // No existing Title for this tmdb_id — setAcquisitionStatus should
        // backfill both rows.
        val agg = MediaWishAggregate(
            tmdbId = 9000,
            tmdbTitle = "New Show",
            tmdbMediaType = MediaType.TV.name,
            tmdbPosterPath = "/p.jpg",
            tmdbReleaseYear = 2024,
            tmdbPopularity = 50.0,
            seasonNumber = 3,
            voteCount = 1,
            voters = listOf("U")
        )
        WishListService.setAcquisitionStatus(agg, AcquisitionStatus.ORDERED)

        val title = Title.findAll().single { it.tmdb_id == 9000 }
        assertEquals("New Show", title.name)
        assertEquals(MediaType.TV.name, title.media_type)
        assertEquals(2024, title.release_year)
        // Season backfill — season 3 marked ORDERED.
        val season = TitleSeason.findAll().single { it.title_id == title.id && it.season_number == 3 }
        assertEquals(AcquisitionStatus.ORDERED.name, season.acquisition_status)
    }

    @Test
    fun `setAcquisitionStatus updates an existing TitleSeason in place`() {
        // Pre-existing title + season.
        val title = Title(name = "Show", media_type = MediaType.TV.name,
            tmdb_id = 7777).apply { save() }
        TitleSeason(title_id = title.id!!, season_number = 1,
            acquisition_status = AcquisitionStatus.UNKNOWN.name).apply { save() }

        val agg = MediaWishAggregate(
            tmdbId = 7777,
            tmdbTitle = "Show",
            tmdbMediaType = MediaType.TV.name,
            tmdbPosterPath = null, tmdbReleaseYear = null, tmdbPopularity = null,
            seasonNumber = 1, voteCount = 0, voters = emptyList()
        )
        WishListService.setAcquisitionStatus(agg, AcquisitionStatus.OWNED)

        val updated = TitleSeason.findAll().single { it.title_id == title.id && it.season_number == 1 }
        assertEquals(AcquisitionStatus.OWNED.name, updated.acquisition_status)
        // No second title row should have been created.
        assertEquals(1, Title.findAll().count { it.tmdb_id == 7777 })
    }

    @Test
    fun `setAcquisitionStatus uses season 0 for movies (no seasonNumber)`() {
        val agg = MediaWishAggregate(
            tmdbId = 4000,
            tmdbTitle = "A Movie",
            tmdbMediaType = MediaType.MOVIE.name,
            tmdbPosterPath = null, tmdbReleaseYear = null, tmdbPopularity = null,
            seasonNumber = null, voteCount = 0, voters = emptyList()
        )
        WishListService.setAcquisitionStatus(agg, AcquisitionStatus.ORDERED)
        val title = Title.findAll().single { it.tmdb_id == 4000 }
        val season = TitleSeason.findAll().single { it.title_id == title.id }
        assertEquals(0, season.season_number)
        assertEquals(AcquisitionStatus.ORDERED.name, season.acquisition_status)
    }

    @Test
    fun `syncPhysicalOwnership marks a movie as OWNED with season 0 join`() {
        // Movie title with one media_item_title link, no existing season.
        val mediaItemId = MediaItem().apply { save() }.id!!
        val mit = MediaItemTitle(media_item_id = mediaItemId, title_id = movieTitleId)
            .apply { save() }

        WishListService.syncPhysicalOwnership(movieTitleId)

        val season = TitleSeason.findAll().single { it.title_id == movieTitleId }
        assertEquals(0, season.season_number)
        assertEquals(AcquisitionStatus.OWNED.name, season.acquisition_status)
        // Join row created.
        val joins = MediaItemTitleSeason.findAll().filter {
            it.media_item_title_id == mit.id && it.title_season_id == season.id
        }
        assertEquals(1, joins.size)
    }

    @Test
    fun `syncPhysicalOwnership re-runs are idempotent for movies`() {
        val mediaItemId = MediaItem().apply { save() }.id!!
        MediaItemTitle(media_item_id = mediaItemId, title_id = movieTitleId)
            .apply { save() }

        WishListService.syncPhysicalOwnership(movieTitleId)
        WishListService.syncPhysicalOwnership(movieTitleId)

        assertEquals(1, TitleSeason.findAll().count { it.title_id == movieTitleId })
        assertEquals(1, MediaItemTitleSeason.findAll().size)
    }

    @Test
    fun `syncPhysicalOwnership is a no-op when title has no media_item_title links`() {
        WishListService.syncPhysicalOwnership(movieTitleId)
        assertEquals(0, TitleSeason.findAll().size)
    }

    @Test
    fun `syncPhysicalOwnership for TV honors per-disc seasons string`() {
        val tvTitleId = Title(name = "Show", media_type = MediaType.TV.name,
            tmdb_id = 8888).apply { save() }.id!!
        val mediaItemId = MediaItem().apply { save() }.id!!
        MediaItemTitle(media_item_id = mediaItemId, title_id = tvTitleId,
            seasons = "1, 2").apply { save() }

        WishListService.syncPhysicalOwnership(tvTitleId)

        val seasons = TitleSeason.findAll().filter { it.title_id == tvTitleId }
            .sortedBy { it.season_number }
        assertEquals(listOf(1, 2), seasons.map { it.season_number })
        assertTrue(seasons.all { it.acquisition_status == AcquisitionStatus.OWNED.name })
    }
}
