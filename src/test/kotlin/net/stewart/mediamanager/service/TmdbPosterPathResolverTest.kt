package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollectionPart
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [TmdbPosterPathResolver]. Focus is the cross-media-type
 * collision: TMDB id namespaces are separate for MOVIE and TV (movie
 * 253 ≠ TV 253), so every (tmdb_id, media_type) lookup must filter
 * by both columns. The pre-fix resolver matched WishListItem by
 * tmdb_id alone, returning whichever wish was first in row-order —
 * frequently the wrong-type poster, or none at all.
 */
class TmdbPosterPathResolverTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:tmdbresolvertest;DB_CLOSE_DELAY=-1"
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
        TmdbCollectionPart.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    private fun makeUser(): Long {
        val now = LocalDateTime.now()
        val u = AppUser(
            username = "u${System.nanoTime()}",
            display_name = "Test",
            password_hash = PasswordService.hash("Sufficient1!"),
            access_level = 1,
            created_at = now, updated_at = now
        )
        u.save()
        return u.id!!
    }

    private fun seedWish(userId: Long, tmdbId: Int, mediaType: MediaType, posterPath: String?) {
        WishListItem(
            user_id = userId,
            wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = tmdbId,
            tmdb_media_type = mediaType.name,
            tmdb_title = "wish-$tmdbId-$mediaType",
            tmdb_poster_path = posterPath,
            created_at = LocalDateTime.now(),
        ).save()
    }

    private fun seedTitle(tmdbId: Int, mediaType: MediaType, posterPath: String?) {
        val now = LocalDateTime.now()
        Title(
            name = "title-$tmdbId-$mediaType",
            media_type = mediaType.name,
            tmdb_id = tmdbId,
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            poster_path = posterPath,
            created_at = now, updated_at = now,
        ).save()
    }

    @Test
    fun `returns null for invalid tmdb id`() {
        assertNull(TmdbPosterPathResolver.find(0, "MOVIE"))
        assertNull(TmdbPosterPathResolver.find(-5, "TV"))
    }

    @Test
    fun `returns null when no row carries a poster for that key`() {
        assertNull(TmdbPosterPathResolver.find(999, "MOVIE"))
    }

    @Test
    fun `title with matching tmdb id and media type wins`() {
        seedTitle(11, MediaType.MOVIE, "/movie11.jpg")
        assertEquals("/movie11.jpg", TmdbPosterPathResolver.find(11, "MOVIE"))
    }

    @Test
    fun `wish provides poster when no owned title exists`() {
        val u = makeUser()
        seedWish(u, 60059, MediaType.TV, "/saul.jpg")
        assertEquals("/saul.jpg", TmdbPosterPathResolver.find(60059, "TV"))
    }

    @Test
    fun `wish lookup must filter by media type — cross-type collision regression`() {
        // Why: TMDB ID namespaces are separate. A wish for the TV show
        // with tmdb_id=11 must not satisfy a /tmdb-poster/MOVIE/11/...
        // request. The bug was that resolver.find(11, "MOVIE") matched
        // the TV wish (it was first by row-order) and either returned
        // a wrong-type poster or null when the TV wish lacked one.
        val u = makeUser()
        seedWish(u, 11, MediaType.TV, "/wrong-tv-11.jpg")
        seedWish(u, 11, MediaType.MOVIE, "/right-movie-11.jpg")

        assertEquals("/right-movie-11.jpg", TmdbPosterPathResolver.find(11, "MOVIE"))
        assertEquals("/wrong-tv-11.jpg",    TmdbPosterPathResolver.find(11, "TV"))
    }

    @Test
    fun `wish without a poster falls through to null`() {
        val u = makeUser()
        seedWish(u, 11, MediaType.MOVIE, null)
        assertNull(TmdbPosterPathResolver.find(11, "MOVIE"))
    }

    @Test
    fun `stale poster-less wish must not shadow a later wish with a poster`() {
        // Why: seed-wishes cancels-and-re-adds fixture rows when their
        // tmdb_poster_path is null. Cancel sets status=CANCELLED but
        // doesn't delete the row, so the table has BOTH the old null-
        // poster wish AND the new ACTIVE poster-bearing wish for the
        // same (tmdb_id, media_type). Resolver must return the poster,
        // not get short-circuited by the older null row.
        val u = makeUser()
        seedWish(u, 11, MediaType.MOVIE, null)            // older, cancelled (null poster)
        seedWish(u, 11, MediaType.MOVIE, "/star-wars.jpg") // newer, active
        assertEquals("/star-wars.jpg", TmdbPosterPathResolver.find(11, "MOVIE"))
    }
}
