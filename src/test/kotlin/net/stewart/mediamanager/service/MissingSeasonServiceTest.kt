package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.DismissedNotification
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaItemTitleSeason
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.Transcode
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
 * Tests for [MissingSeasonService] — TMDB season storage, ownership
 * inference from transcoded episodes, and the missing-season feed
 * (with per-user dismissal).
 */
class MissingSeasonServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:missingseasontest;DB_CLOSE_DELAY=-1"
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
    private var showAlphaId: Long = 0
    private var showBetaId: Long = 0
    private var hiddenShowId: Long = 0

    @Before
    fun reset() {
        // Children before parents — FK chain is fairly deep here.
        DismissedNotification.deleteAll()
        MediaItemTitleSeason.deleteAll()
        MediaItemTitle.deleteAll()
        Transcode.deleteAll()
        Episode.deleteAll()
        TitleSeason.deleteAll()
        Title.deleteAll()
        MediaItem.deleteAll()
        AppUser.deleteAll()

        userId = AppUser(username = "u", display_name = "U", password_hash = "x")
            .apply { save() }.id!!

        showAlphaId = Title(name = "Alpha", media_type = MediaType.TV.name,
            tmdb_id = 100, popularity = 50.0, poster_path = "/a.jpg"
        ).apply { save() }.id!!

        showBetaId = Title(name = "Beta", media_type = MediaType.TV.name,
            tmdb_id = 101, popularity = 99.0, poster_path = "/b.jpg"
        ).apply { save() }.id!!

        hiddenShowId = Title(name = "Hidden", media_type = MediaType.TV.name,
            tmdb_id = 102, popularity = 100.0, hidden = true
        ).apply { save() }.id!!
    }

    // ---------------------- storeSeasons ----------------------

    @Test
    fun `storeSeasons inserts new rows on first call`() {
        MissingSeasonService.storeSeasons(showAlphaId, listOf(
            TmdbSeasonInfo(seasonNumber = 1, name = "S1", episodeCount = 10, airDate = "2020-01-01"),
            TmdbSeasonInfo(seasonNumber = 2, name = "S2", episodeCount = 12, airDate = "2021-01-01"),
        ))
        val rows = TitleSeason.findAll().filter { it.title_id == showAlphaId }
            .sortedBy { it.season_number }
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].season_number)
        assertEquals("S1", rows[0].name)
        assertEquals(10, rows[0].episode_count)
        assertEquals("2020-01-01", rows[0].air_date)
        assertEquals(AcquisitionStatus.UNKNOWN.name, rows[0].acquisition_status,
            "newly stored seasons default to UNKNOWN")
    }

    @Test
    fun `storeSeasons updates existing rows in place`() {
        // First pass — episode_count is unknown.
        MissingSeasonService.storeSeasons(showAlphaId, listOf(
            TmdbSeasonInfo(seasonNumber = 1, name = "Season 1", episodeCount = null, airDate = null),
        ))
        // Second pass — name changes, episode_count fills in. Should upsert, not duplicate.
        MissingSeasonService.storeSeasons(showAlphaId, listOf(
            TmdbSeasonInfo(seasonNumber = 1, name = "Season One", episodeCount = 8, airDate = "2020-03-15"),
        ))

        val rows = TitleSeason.findAll().filter { it.title_id == showAlphaId }
        assertEquals(1, rows.size)
        assertEquals("Season One", rows[0].name)
        assertEquals(8, rows[0].episode_count)
        assertEquals("2020-03-15", rows[0].air_date)
    }

    @Test
    fun `storeSeasons leaves seasons missing from the new list untouched`() {
        // We start with seasons 1 and 2 in the DB.
        MissingSeasonService.storeSeasons(showAlphaId, listOf(
            TmdbSeasonInfo(1, "S1", 10, null),
            TmdbSeasonInfo(2, "S2", 10, null),
        ))
        // Then TMDB only returns season 1 (e.g. transient incomplete response).
        // Season 2 must NOT be deleted — see comment on storeSeasons.
        MissingSeasonService.storeSeasons(showAlphaId, listOf(
            TmdbSeasonInfo(1, "S1 (renamed)", 10, null),
        ))
        val numbers = TitleSeason.findAll().filter { it.title_id == showAlphaId }
            .map { it.season_number }.toSet()
        assertEquals(setOf(1, 2), numbers)
    }

    // ---------------------- refreshOwnership ----------------------

    @Test
    fun `refreshOwnership promotes UNKNOWN seasons that have a transcoded episode`() {
        TitleSeason(title_id = showAlphaId, season_number = 1).apply { save() }
        TitleSeason(title_id = showAlphaId, season_number = 2).apply { save() }

        val ep = Episode(title_id = showAlphaId, season_number = 1, episode_number = 1)
            .apply { save() }
        Transcode(title_id = showAlphaId, episode_id = ep.id, file_path = "/foo.mkv")
            .apply { save() }

        MissingSeasonService.refreshOwnership()

        val s1 = TitleSeason.findAll().single { it.title_id == showAlphaId && it.season_number == 1 }
        val s2 = TitleSeason.findAll().single { it.title_id == showAlphaId && it.season_number == 2 }
        assertEquals(AcquisitionStatus.OWNED.name, s1.acquisition_status)
        assertEquals(AcquisitionStatus.UNKNOWN.name, s2.acquisition_status,
            "seasons without transcoded episodes stay UNKNOWN")
    }

    @Test
    fun `refreshOwnership does not demote ORDERED or other non-UNKNOWN states`() {
        TitleSeason(title_id = showAlphaId, season_number = 1,
            acquisition_status = AcquisitionStatus.ORDERED.name).apply { save() }
        // No transcode for this season — refreshOwnership must NOT touch ORDERED.
        MissingSeasonService.refreshOwnership()
        val s1 = TitleSeason.findAll().single { it.title_id == showAlphaId && it.season_number == 1 }
        assertEquals(AcquisitionStatus.ORDERED.name, s1.acquisition_status)
    }

    // ---------------------- getMissingSeasonsForUser ----------------------

    private fun seedAlphaWithMissingHigher() {
        // Alpha: own S1 + S2, S3 missing, S4 missing.
        TitleSeason(title_id = showAlphaId, season_number = 1,
            acquisition_status = AcquisitionStatus.OWNED.name).apply { save() }
        TitleSeason(title_id = showAlphaId, season_number = 2,
            acquisition_status = AcquisitionStatus.OWNED.name).apply { save() }
        TitleSeason(title_id = showAlphaId, season_number = 3).apply { save() }
        TitleSeason(title_id = showAlphaId, season_number = 4).apply { save() }
    }

    @Test
    fun `getMissingSeasonsForUser returns seasons above highest owned`() {
        seedAlphaWithMissingHigher()
        val results = MissingSeasonService.getMissingSeasonsForUser(userId)
        assertEquals(1, results.size)
        val summary = results.single()
        assertEquals(showAlphaId, summary.titleId)
        assertEquals("Alpha", summary.titleName)
        assertEquals("/a.jpg", summary.posterPath)
        assertEquals(100, summary.tmdbId)
        assertEquals(MediaType.TV.name, summary.tmdbMediaType)
        assertEquals(listOf(3, 4), summary.missingSeasons.map { it.season_number })
    }

    @Test
    fun `getMissingSeasonsForUser skips titles where we own no seasons`() {
        // Show with seasons declared but all UNKNOWN — we don't own any so don't notify.
        TitleSeason(title_id = showBetaId, season_number = 1).apply { save() }
        TitleSeason(title_id = showBetaId, season_number = 2).apply { save() }
        assertTrue(MissingSeasonService.getMissingSeasonsForUser(userId).isEmpty())
    }

    @Test
    fun `getMissingSeasonsForUser skips hidden titles`() {
        TitleSeason(title_id = hiddenShowId, season_number = 1,
            acquisition_status = AcquisitionStatus.OWNED.name).apply { save() }
        TitleSeason(title_id = hiddenShowId, season_number = 2).apply { save() }
        assertTrue(MissingSeasonService.getMissingSeasonsForUser(userId).isEmpty())
    }

    @Test
    fun `getMissingSeasonsForUser orders results by popularity desc`() {
        // Both shows have a missing higher season. Beta (popularity 99) should
        // outrank Alpha (popularity 50).
        seedAlphaWithMissingHigher()
        TitleSeason(title_id = showBetaId, season_number = 1,
            acquisition_status = AcquisitionStatus.OWNED.name).apply { save() }
        TitleSeason(title_id = showBetaId, season_number = 2).apply { save() }

        val results = MissingSeasonService.getMissingSeasonsForUser(userId)
        assertEquals(listOf(showBetaId, showAlphaId), results.map { it.titleId })
    }

    @Test
    fun `getMissingSeasonsForUser excludes dismissed seasons`() {
        seedAlphaWithMissingHigher()
        // User dismisses S3 only — S4 should still surface.
        MissingSeasonService.dismiss(userId, showAlphaId, 3)

        val results = MissingSeasonService.getMissingSeasonsForUser(userId)
        assertEquals(1, results.size)
        assertEquals(listOf(4), results.single().missingSeasons.map { it.season_number })
    }

    @Test
    fun `getMissingSeasonsForUser drops a title once all missing seasons are dismissed`() {
        seedAlphaWithMissingHigher()
        MissingSeasonService.dismiss(userId, showAlphaId, 3)
        MissingSeasonService.dismiss(userId, showAlphaId, 4)
        assertTrue(MissingSeasonService.getMissingSeasonsForUser(userId).isEmpty())
    }

    @Test
    fun `getMissingSeasonsForUser respects limit`() {
        seedAlphaWithMissingHigher()
        TitleSeason(title_id = showBetaId, season_number = 1,
            acquisition_status = AcquisitionStatus.OWNED.name).apply { save() }
        TitleSeason(title_id = showBetaId, season_number = 2).apply { save() }

        val results = MissingSeasonService.getMissingSeasonsForUser(userId, limit = 1)
        assertEquals(1, results.size)
    }

    // ---------------------- syncStructuredSeasons ----------------------

    @Test
    fun `syncStructuredSeasons creates title_season rows and join links`() {
        val mediaItemId = MediaItem().apply { save() }.id!!
        val mit = MediaItemTitle(media_item_id = mediaItemId, title_id = showAlphaId)
            .apply { save() }

        MissingSeasonService.syncStructuredSeasons(mit.id!!, showAlphaId, "1, 2")

        val tsRows = TitleSeason.findAll().filter { it.title_id == showAlphaId }
            .sortedBy { it.season_number }
        assertEquals(listOf(1, 2), tsRows.map { it.season_number })
        // Both must be marked OWNED — the user has the disc.
        assertTrue(tsRows.all { it.acquisition_status == AcquisitionStatus.OWNED.name })

        val joins = MediaItemTitleSeason.findAll().filter { it.media_item_title_id == mit.id }
        assertEquals(2, joins.size)
    }

    @Test
    fun `syncStructuredSeasons promotes UNKNOWN to OWNED on existing seasons`() {
        val mediaItemId = MediaItem().apply { save() }.id!!
        val mit = MediaItemTitle(media_item_id = mediaItemId, title_id = showAlphaId)
            .apply { save() }
        // Pre-existing UNKNOWN season — sync should promote to OWNED.
        TitleSeason(title_id = showAlphaId, season_number = 1).apply { save() }

        MissingSeasonService.syncStructuredSeasons(mit.id!!, showAlphaId, "1")

        val ts = TitleSeason.findAll().single { it.title_id == showAlphaId && it.season_number == 1 }
        assertEquals(AcquisitionStatus.OWNED.name, ts.acquisition_status)
    }

    @Test
    fun `syncStructuredSeasons leaves ORDERED seasons untouched`() {
        val mediaItemId = MediaItem().apply { save() }.id!!
        val mit = MediaItemTitle(media_item_id = mediaItemId, title_id = showAlphaId)
            .apply { save() }
        TitleSeason(title_id = showAlphaId, season_number = 1,
            acquisition_status = AcquisitionStatus.ORDERED.name).apply { save() }

        MissingSeasonService.syncStructuredSeasons(mit.id!!, showAlphaId, "1")

        val ts = TitleSeason.findAll().single { it.title_id == showAlphaId && it.season_number == 1 }
        assertEquals(AcquisitionStatus.ORDERED.name, ts.acquisition_status,
            "syncStructuredSeasons only promotes UNKNOWN, never reverses other states")
    }

    @Test
    fun `syncStructuredSeasons replaces previous joins on re-sync`() {
        val mediaItemId = MediaItem().apply { save() }.id!!
        val mit = MediaItemTitle(media_item_id = mediaItemId, title_id = showAlphaId)
            .apply { save() }

        MissingSeasonService.syncStructuredSeasons(mit.id!!, showAlphaId, "1, 2")
        assertEquals(2, MediaItemTitleSeason.findAll().count { it.media_item_title_id == mit.id })

        MissingSeasonService.syncStructuredSeasons(mit.id!!, showAlphaId, "3")
        val joins = MediaItemTitleSeason.findAll().filter { it.media_item_title_id == mit.id }
        assertEquals(1, joins.size, "old joins for seasons 1+2 should be removed")
        // The new join points at season 3.
        val s3 = TitleSeason.findAll().single { it.title_id == showAlphaId && it.season_number == 3 }
        assertEquals(s3.id, joins.single().title_season_id)
    }

    @Test
    fun `syncStructuredSeasons clears joins when freetext is null or blank`() {
        val mediaItemId = MediaItem().apply { save() }.id!!
        val mit = MediaItemTitle(media_item_id = mediaItemId, title_id = showAlphaId)
            .apply { save() }
        MissingSeasonService.syncStructuredSeasons(mit.id!!, showAlphaId, "1")
        assertEquals(1, MediaItemTitleSeason.findAll().count { it.media_item_title_id == mit.id })

        MissingSeasonService.syncStructuredSeasons(mit.id!!, showAlphaId, null)
        assertEquals(0, MediaItemTitleSeason.findAll().count { it.media_item_title_id == mit.id })

        MissingSeasonService.syncStructuredSeasons(mit.id!!, showAlphaId, "  ")
        assertEquals(0, MediaItemTitleSeason.findAll().count { it.media_item_title_id == mit.id })
    }

    @Test
    fun `syncStructuredSeasons no-ops on unparseable input`() {
        val mediaItemId = MediaItem().apply { save() }.id!!
        val mit = MediaItemTitle(media_item_id = mediaItemId, title_id = showAlphaId)
            .apply { save() }
        MissingSeasonService.syncStructuredSeasons(mit.id!!, showAlphaId, "garbage tokens")
        // Old joins are removed but no new ones are inserted.
        assertEquals(0, MediaItemTitleSeason.findAll().count { it.media_item_title_id == mit.id })
        assertEquals(0, TitleSeason.findAll().count { it.title_id == showAlphaId })
    }

    // ---------------------- parseSeasonText ----------------------

    @Test
    fun `parseSeasonText accepts a single integer`() {
        assertEquals(listOf(2), MissingSeasonService.parseSeasonText("2"))
        assertEquals(listOf(2), MissingSeasonService.parseSeasonText("  2  "))
    }

    @Test
    fun `parseSeasonText accepts ranges`() {
        assertEquals(listOf(1, 2, 3), MissingSeasonService.parseSeasonText("1-3"))
        assertEquals(listOf(1, 2, 3), MissingSeasonService.parseSeasonText("1 - 3"))
    }

    @Test
    fun `parseSeasonText rejects reversed ranges and oversized ranges`() {
        assertNull(MissingSeasonService.parseSeasonText("5-2"), "reversed range")
        assertNull(MissingSeasonService.parseSeasonText("1-100"), "range larger than 50")
    }

    @Test
    fun `parseSeasonText accepts comma lists with optional S prefix`() {
        assertEquals(listOf(1, 3), MissingSeasonService.parseSeasonText("1,3"))
        assertEquals(listOf(1, 2), MissingSeasonService.parseSeasonText("S1, S2"))
        assertEquals(listOf(1, 2), MissingSeasonService.parseSeasonText("s1, s2"))
    }

    @Test
    fun `parseSeasonText returns null on garbage`() {
        assertNull(MissingSeasonService.parseSeasonText("hello"))
        assertNull(MissingSeasonService.parseSeasonText("1, foo"))
    }

    // ---------------------- dismiss & dismissAllForTitle ----------------------

    @Test
    fun `dismiss is idempotent and uses the canonical key format`() {
        MissingSeasonService.dismiss(userId, showAlphaId, 3)
        MissingSeasonService.dismiss(userId, showAlphaId, 3)
        val rows = DismissedNotification.findAll().filter { it.user_id == userId }
        assertEquals(1, rows.size, "second dismiss should not insert a duplicate")
        assertEquals("missing_season:${showAlphaId}:3", rows.single().notification_key)
        assertNotNull(rows.single().dismissed_at)
    }

    @Test
    fun `dismissAllForTitle dismisses every non-OWNED season`() {
        TitleSeason(title_id = showAlphaId, season_number = 1,
            acquisition_status = AcquisitionStatus.OWNED.name).apply { save() }
        TitleSeason(title_id = showAlphaId, season_number = 2).apply { save() }
        TitleSeason(title_id = showAlphaId, season_number = 3,
            acquisition_status = AcquisitionStatus.ORDERED.name).apply { save() }

        MissingSeasonService.dismissAllForTitle(userId, showAlphaId)

        val keys = DismissedNotification.findAll().filter { it.user_id == userId }
            .map { it.notification_key }.toSet()
        assertEquals(setOf(
            "missing_season:${showAlphaId}:2",
            "missing_season:${showAlphaId}:3",
        ), keys, "OWNED is excluded; UNKNOWN+ORDERED both dismissed")
        assertFalse("missing_season:${showAlphaId}:1" in keys)
    }
}
