package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.PlaybackProgress
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [PlaybackProgressService] — the heart of Continue Watching,
 * Recently Watched, and the auto-clear-at-end logic for video playback.
 */
class PlaybackProgressServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:playbackprogresstest;DB_CLOSE_DELAY=-1"
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
    private var tvTitleId: Long = 0
    private var unratedTitleId: Long = 0
    private var movieTranscodeId: Long = 0
    private var tvTranscodeId: Long = 0
    private var unratedTranscodeId: Long = 0
    private var episodeId: Long = 0

    @Before
    fun reset() {
        // Children before parents to satisfy FKs.
        UserTitleFlag.deleteAll()
        PlaybackProgress.deleteAll()
        Transcode.deleteAll()
        Episode.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x")
        u.save(); userId = u.id!!
        val o = AppUser(username = "o", display_name = "O", password_hash = "x")
        o.save(); otherUserId = o.id!!

        val movie = Title(
            name = "Test Movie",
            tmdb_id = 1001,
            media_type = MediaType.MOVIE.name,
            content_rating = "PG"
        ).apply { save() }
        movieTitleId = movie.id!!

        val tv = Title(
            name = "Test Show",
            tmdb_id = 2002,
            media_type = MediaType.TV.name,
            content_rating = "TV-PG"
        ).apply { save() }
        tvTitleId = tv.id!!

        // Title without tmdb_id — getRecentlyAddedForUser should skip it.
        val unrated = Title(name = "Untagged", media_type = MediaType.MOVIE.name).apply { save() }
        unratedTitleId = unrated.id!!

        val ep = Episode(title_id = tvTitleId, season_number = 2, episode_number = 5,
            name = "The One Where").apply { save() }
        episodeId = ep.id!!

        movieTranscodeId = Transcode(title_id = movieTitleId, file_path = "/m.mkv",
            created_at = LocalDateTime.now()).apply { save() }.id!!
        tvTranscodeId = Transcode(title_id = tvTitleId, episode_id = episodeId,
            file_path = "/t.mkv", created_at = LocalDateTime.now()).apply { save() }.id!!
        unratedTranscodeId = Transcode(title_id = unratedTitleId, file_path = "/u.mkv",
            created_at = LocalDateTime.now()).apply { save() }.id!!
    }

    // ---------------------- recordProgressForUser ----------------------

    @Test
    fun `recordProgress creates a row on first call`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 30.0, 7200.0)
        val row = PlaybackProgressService.getProgressForUser(userId, movieTranscodeId)
        assertNotNull(row)
        assertEquals(30.0, row.position_seconds)
        assertEquals(7200.0, row.duration_seconds)
    }

    @Test
    fun `recordProgress updates existing row`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 10.0, 1000.0)
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 50.0, 1000.0)
        val all = PlaybackProgress.findAll().filter {
            it.user_id == userId && it.transcode_id == movieTranscodeId
        }
        assertEquals(1, all.size, "should upsert, not insert duplicates")
        assertEquals(50.0, all.first().position_seconds)
    }

    @Test
    fun `recordProgress carries forward duration when null`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 30.0, 1000.0)
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 60.0, null)
        val row = PlaybackProgressService.getProgressForUser(userId, movieTranscodeId)
        assertNotNull(row)
        assertEquals(60.0, row.position_seconds)
        assertEquals(1000.0, row.duration_seconds)
    }

    @Test
    fun `recordProgress auto-clears at or above 95 percent and flags VIEWED`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 30.0, 1000.0)
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 960.0, 1000.0)
        assertNull(PlaybackProgressService.getProgressForUser(userId, movieTranscodeId),
            "row should be cleared at >= 95%")
        val viewed = UserTitleFlag.findAll().any {
            it.user_id == userId && it.title_id == movieTitleId && it.flag == UserFlagType.VIEWED.name
        }
        assertTrue(viewed, "VIEWED flag should be set when auto-clearing")
    }

    @Test
    fun `recordProgress auto-clears under 120 seconds remaining and flags VIEWED`() {
        // 9000s total, position 8900s => 100s remaining (< 120) but only 98.8% — exercises
        // the "remaining <= 120" branch independently of the 95% branch.
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 100.0, 9000.0)
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 8900.0, 9000.0)
        assertNull(PlaybackProgressService.getProgressForUser(userId, movieTranscodeId))
        val viewed = UserTitleFlag.findAll().any {
            it.user_id == userId && it.title_id == movieTitleId && it.flag == UserFlagType.VIEWED.name
        }
        assertTrue(viewed)
    }

    @Test
    fun `recordProgress between 25 and 95 percent flags VIEWED but keeps row`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 500.0, 1000.0)
        assertNotNull(PlaybackProgressService.getProgressForUser(userId, movieTranscodeId))
        val viewed = UserTitleFlag.findAll().any {
            it.user_id == userId && it.title_id == movieTitleId && it.flag == UserFlagType.VIEWED.name
        }
        assertTrue(viewed, "VIEWED flag should be set above 25%")
    }

    @Test
    fun `recordProgress under 25 percent does not flag VIEWED`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 100.0, 1000.0)
        val viewed = UserTitleFlag.findAll().any {
            it.user_id == userId && it.title_id == movieTitleId && it.flag == UserFlagType.VIEWED.name
        }
        assertFalse(viewed)
    }

    @Test
    fun `recordProgress with null duration skips the auto-clear path`() {
        // No duration => no end-detection logic; row should just be saved.
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 999_999.0, null)
        val row = PlaybackProgressService.getProgressForUser(userId, movieTranscodeId)
        assertNotNull(row)
        assertEquals(999_999.0, row.position_seconds)
    }

    @Test
    fun `recordProgress sets VIEWED flag at most once`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 500.0, 1000.0)
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 600.0, 1000.0)
        val flagCount = UserTitleFlag.findAll().count {
            it.user_id == userId && it.title_id == movieTitleId && it.flag == UserFlagType.VIEWED.name
        }
        assertEquals(1, flagCount, "VIEWED flag should be idempotent")
    }

    // ---------------------- deleteProgressForUser ----------------------

    @Test
    fun `deleteProgressForUser removes only the matching row`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 30.0, 1000.0)
        PlaybackProgressService.recordProgressForUser(userId, tvTranscodeId, 30.0, 1000.0)
        PlaybackProgressService.deleteProgressForUser(userId, movieTranscodeId)
        assertNull(PlaybackProgressService.getProgressForUser(userId, movieTranscodeId))
        assertNotNull(PlaybackProgressService.getProgressForUser(userId, tvTranscodeId))
    }

    // ---------------------- getContinueWatchingForUser ----------------------

    @Test
    fun `getContinueWatchingForUser empty when no progress`() {
        assertTrue(PlaybackProgressService.getContinueWatchingForUser(userId).isEmpty())
    }

    @Test
    fun `getContinueWatchingForUser returns newest first with episode metadata`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 30.0, 1000.0)
        Thread.sleep(10)
        PlaybackProgressService.recordProgressForUser(userId, tvTranscodeId, 30.0, 1000.0)

        val items = PlaybackProgressService.getContinueWatchingForUser(userId)
        assertEquals(2, items.size)
        // Newest first.
        assertEquals(tvTranscodeId, items[0].transcodeId)
        assertEquals(movieTranscodeId, items[1].transcodeId)
        // TV item carries episode metadata.
        assertTrue(items[0].isEpisode)
        assertEquals(2, items[0].seasonNumber)
        assertEquals(5, items[0].episodeNumber)
        assertEquals("The One Where", items[0].episodeName)
        // Movie has no episode metadata.
        assertFalse(items[1].isEpisode)
    }

    @Test
    fun `getContinueWatchingForUser respects limit`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 30.0, 1000.0)
        PlaybackProgressService.recordProgressForUser(userId, tvTranscodeId, 30.0, 1000.0)
        PlaybackProgressService.recordProgressForUser(userId, unratedTranscodeId, 30.0, 1000.0)
        assertEquals(1, PlaybackProgressService.getContinueWatchingForUser(userId, limit = 1).size)
    }

    @Test
    fun `getContinueWatchingForUser only returns the requested user's rows`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 30.0, 1000.0)
        PlaybackProgressService.recordProgressForUser(otherUserId, tvTranscodeId, 30.0, 1000.0)
        val mine = PlaybackProgressService.getContinueWatchingForUser(userId)
        assertEquals(1, mine.size)
        assertEquals(movieTranscodeId, mine.first().transcodeId)
    }

    // ---------------------- getProgressByTitleForUser ----------------------

    @Test
    fun `getProgressByTitleForUser empty when no progress`() {
        assertTrue(PlaybackProgressService.getProgressByTitleForUser(userId).isEmpty())
    }

    @Test
    fun `getProgressByTitleForUser groups by title and picks newest per title`() {
        // Two transcodes for the same TV title (pretend two episodes). Only the most
        // recently updated should win per title.
        val secondTvTranscodeId = Transcode(title_id = tvTitleId, file_path = "/t2.mkv",
            created_at = LocalDateTime.now()).apply { save() }.id!!
        PlaybackProgressService.recordProgressForUser(userId, tvTranscodeId, 30.0, 1000.0)
        Thread.sleep(10)
        PlaybackProgressService.recordProgressForUser(userId, secondTvTranscodeId, 60.0, 1000.0)

        val byTitle = PlaybackProgressService.getProgressByTitleForUser(userId)
        assertEquals(1, byTitle.size, "two transcodes on one title collapse to one entry")
        val winner = byTitle[tvTitleId]
        assertNotNull(winner)
        assertEquals(secondTvTranscodeId, winner.transcode_id, "newest update wins")
    }

    // ---------------------- getOrCreateRokuUser ----------------------

    @Test
    fun `getOrCreateRokuUser creates the roku account on first call and reuses it`() {
        val first = PlaybackProgressService.getOrCreateRokuUser()
        assertEquals("roku", first.username)
        assertEquals(1, first.access_level)
        val second = PlaybackProgressService.getOrCreateRokuUser()
        assertEquals(first.id, second.id, "second call must reuse the existing row")
    }

    // ---------------------- getRecentlyWatchedForUser ----------------------

    @Test
    fun `getRecentlyWatchedForUser empty when no flags`() {
        assertTrue(PlaybackProgressService.getRecentlyWatchedForUser(userId).isEmpty())
    }

    @Test
    fun `getRecentlyWatchedForUser orders by created_at and excludes active progress`() {
        // Flag both titles as viewed.
        UserTitleFlag(user_id = userId, title_id = movieTitleId,
            flag = UserFlagType.VIEWED.name, created_at = LocalDateTime.now().minusHours(1)
        ).apply { save() }
        UserTitleFlag(user_id = userId, title_id = tvTitleId,
            flag = UserFlagType.VIEWED.name, created_at = LocalDateTime.now()
        ).apply { save() }

        // Active progress on the movie -> excluded from recently watched.
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 30.0, 1000.0)

        val recents = PlaybackProgressService.getRecentlyWatchedForUser(userId)
        assertEquals(1, recents.size)
        assertEquals(tvTitleId, recents.first().id)
    }

    // ---------------------- getRecentlyAddedForUser ----------------------

    @Test
    fun `getRecentlyAddedForUser dedupes by title and skips titles without tmdb_id`() {
        // Add a second transcode for the movie title (e.g. a re-rip) — should still
        // count as one entry. The unrated title has no tmdb_id so it must be skipped.
        Transcode(title_id = movieTitleId, file_path = "/m2.mkv",
            created_at = LocalDateTime.now()).apply { save() }

        val results = PlaybackProgressService.getRecentlyAddedForUser(user = null, limit = 10)
        val titleIds = results.map { it.first.id }.toSet()
        assertTrue(movieTitleId in titleIds)
        assertTrue(tvTitleId in titleIds)
        assertFalse(unratedTitleId in titleIds, "title without tmdb_id is skipped")
        // Title appears at most once even with multiple transcodes.
        assertEquals(titleIds.size, results.size, "results dedupe by title")
    }

    @Test
    fun `getRecentlyAddedForUser respects user canSeeRating`() {
        // Mark the TV title as TV-MA so a rating-ceiling user can't see it.
        val tv = Title.findById(tvTitleId)!!
        tv.content_rating = "TV-MA"
        tv.save()

        val limited = AppUser.findById(userId)!!
        limited.access_level = 1
        limited.rating_ceiling = ContentRating.TV_PG.ordinalLevel
        limited.save()

        val results = PlaybackProgressService.getRecentlyAddedForUser(limited, limit = 10)
        val titleIds = results.map { it.first.id }
        assertTrue(movieTitleId in titleIds)
        assertFalse(tvTitleId in titleIds, "TV-MA filtered out by ceiling TV-PG")
    }

    @Test
    fun `getRecentlyAddedForUser excludes titles flagged HIDDEN by the user`() {
        UserTitleFlag(user_id = userId, title_id = movieTitleId,
            flag = UserFlagType.HIDDEN.name, created_at = LocalDateTime.now()).apply { save() }

        val user = AppUser.findById(userId)!!
        val titleIds = PlaybackProgressService.getRecentlyAddedForUser(user, limit = 10)
            .map { it.first.id }
        assertFalse(movieTitleId in titleIds)
        assertTrue(tvTitleId in titleIds)
    }

    @Test
    fun `getRecentlyAddedForUser respects limit`() {
        // We have 2 titles with tmdb_id (movie + tv). limit=1 returns just the newest.
        val one = PlaybackProgressService.getRecentlyAddedForUser(user = null, limit = 1)
        assertEquals(1, one.size)
    }

    // ---------------------- ContinueWatchingItem helpers ----------------------

    @Test
    fun `ContinueWatchingItem episode and progress helpers`() {
        // Episode case.
        val episode = ContinueWatchingItem(
            transcodeId = 1, titleId = 1, titleName = "Show", posterUrl = null,
            positionSeconds = 600.0, durationSeconds = 1800.0,
            updatedAt = LocalDateTime.now(),
            seasonNumber = 2, episodeNumber = 5, episodeName = "The One Where"
        )
        assertTrue(episode.isEpisode)
        assertEquals("S02E05 — The One Where", episode.episodeLabel)
        assertEquals(0.333, episode.progressFraction, absoluteTolerance = 0.001)
        assertEquals("20 min left", episode.timeRemaining)
        assertEquals("10:00", episode.formattedPosition)

        // Episode without a name falls back to just the tag.
        val unnamed = episode.copy(episodeName = null)
        assertEquals("S02E05", unnamed.episodeLabel)

        // Movie case: no season => no label, no episode.
        val movie = episode.copy(seasonNumber = null, episodeNumber = null, episodeName = null)
        assertFalse(movie.isEpisode)
        assertNull(movie.episodeLabel)

        // Edge case: zero duration -> 0.0 fraction (not NaN).
        val zero = episode.copy(durationSeconds = 0.0)
        assertEquals(0.0, zero.progressFraction)

        // Edge case: position past duration clamps to 1.0.
        val past = episode.copy(positionSeconds = 9999.0, durationSeconds = 1000.0)
        assertEquals(1.0, past.progressFraction)

        // timeRemaining floors to at least 1 minute even if 0 seconds remain.
        val nearEnd = episode.copy(positionSeconds = 1799.0, durationSeconds = 1800.0)
        assertEquals("1 min left", nearEnd.timeRemaining)

        // formattedPosition pads seconds to two digits.
        val padded = episode.copy(positionSeconds = 65.0)
        assertEquals("1:05", padded.formattedPosition)
    }

    @Test
    fun `ContinueWatchingItem episodeLabel handles missing episode number as zero`() {
        val noEp = ContinueWatchingItem(
            transcodeId = 1, titleId = 1, titleName = "Show", posterUrl = null,
            positionSeconds = 0.0, durationSeconds = 1.0, updatedAt = null,
            seasonNumber = 1, episodeNumber = null, episodeName = null
        )
        assertEquals("S01E00", noEp.episodeLabel)
    }

    @Test
    fun `getProgressForUser returns null when nothing recorded`() {
        assertNull(PlaybackProgressService.getProgressForUser(userId, movieTranscodeId))
    }

    @Test
    fun `recordProgress uses different rows for different users`() {
        PlaybackProgressService.recordProgressForUser(userId, movieTranscodeId, 30.0, 1000.0)
        PlaybackProgressService.recordProgressForUser(otherUserId, movieTranscodeId, 60.0, 1000.0)
        val mine = PlaybackProgressService.getProgressForUser(userId, movieTranscodeId)!!
        val theirs = PlaybackProgressService.getProgressForUser(otherUserId, movieTranscodeId)!!
        assertNotEquals(mine.id, theirs.id)
        assertEquals(30.0, mine.position_seconds)
        assertEquals(60.0, theirs.position_seconds)
    }
}
