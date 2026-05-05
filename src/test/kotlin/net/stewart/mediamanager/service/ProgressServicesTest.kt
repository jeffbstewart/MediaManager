package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.ListeningProgress
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.ReadingProgress
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ReadingProgressService] and [ListeningProgressService] —
 * the parallel upsert+recent helpers behind the Resume Reading and
 * Continue Listening carousels.
 */
class ProgressServicesTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:progresstest;DB_CLOSE_DELAY=-1"
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

    private var userOneId: Long = 0
    private var userTwoId: Long = 0
    private var bookItemA: Long = 0
    private var bookItemB: Long = 0
    private var bookItemC: Long = 0
    private val trackIds = mutableListOf<Long>()

    @Before
    fun reset() {
        ReadingProgress.deleteAll()
        ListeningProgress.deleteAll()
        Track.deleteAll()
        Title.deleteAll()
        MediaItem.deleteAll()
        AppUser.deleteAll()
        trackIds.clear()

        // FK chain: reading_progress.media_item_id → media_item.id;
        //           listening_progress.track_id → track.id → title.id.
        val u1 = AppUser(username = "u1", display_name = "U1", password_hash = "x")
        u1.save(); userOneId = u1.id!!
        val u2 = AppUser(username = "u2", display_name = "U2", password_hash = "x")
        u2.save(); userTwoId = u2.id!!

        val itemA = MediaItem().apply { save() }
        bookItemA = itemA.id!!
        val itemB = MediaItem().apply { save() }
        bookItemB = itemB.id!!
        val itemC = MediaItem().apply { save() }
        bookItemC = itemC.id!!

        // Seed an album + 20 tracks so the listening test can pick
        // any track_id without hitting the FK.
        val album = Title(name = "Album").apply { save() }
        for (i in 1..20) {
            val t = Track(
                title_id = album.id!!,
                name = "Track $i",
                track_number = i,
                disc_number = 1,
            ).apply { save() }
            trackIds += t.id!!
        }
    }

    // ---------------------- ReadingProgressService ----------------------

    @Test
    fun `reading save creates a row on first call`() {
        val saved = ReadingProgressService.save(userOneId, bookItemA, "epubcfi(/6/4!/4/1:0)", 0.25)
        assertEquals(userOneId, saved.user_id)
        assertEquals(bookItemA, saved.media_item_id)
        assertEquals("epubcfi(/6/4!/4/1:0)", saved.cfi)
        assertEquals(0.25, saved.percent)
        assertNotNull(saved.updated_at)

        val fetched = ReadingProgressService.get(userOneId, bookItemA)
        assertEquals(saved.id, fetched?.id)
    }

    @Test
    fun `reading save updates the existing row`() {
        ReadingProgressService.save(userOneId, bookItemA, "cfi-a", 0.10)
        ReadingProgressService.save(userOneId, bookItemA, "cfi-b", 0.50)
        val all = ReadingProgress.findAll().filter {
            it.user_id == userOneId && it.media_item_id == bookItemA
        }
        assertEquals(1, all.size, "save should upsert, not insert duplicates")
        assertEquals("cfi-b", all.first().cfi)
        assertEquals(0.50, all.first().percent)
    }

    @Test
    fun `reading save coerces percent into 0 to 1`() {
        val low = ReadingProgressService.save(userOneId, bookItemB, "cfi", -0.5)
        assertEquals(0.0, low.percent)
        val high = ReadingProgressService.save(userOneId, bookItemC, "cfi", 1.7)
        assertEquals(1.0, high.percent)
    }

    @Test
    fun `reading delete removes the row`() {
        ReadingProgressService.save(userOneId, bookItemA, "cfi", 0.5)
        ReadingProgressService.delete(userOneId, bookItemA)
        assertNull(ReadingProgressService.get(userOneId, bookItemA))
    }

    @Test
    fun `reading delete on missing row is a no-op`() {
        // Non-existent media_item id is fine because delete only issues a
        // DELETE WHERE — no insert means no FK check.
        ReadingProgressService.delete(userOneId, 999_999)
    }

    @Test
    fun `reading recentForUser returns most-recently-updated first`() {
        val a = ReadingProgressService.save(userOneId, bookItemA, "cfi", 0.1)
        Thread.sleep(10)
        val b = ReadingProgressService.save(userOneId, bookItemB, "cfi", 0.1)
        val recent = ReadingProgressService.recentForUser(userOneId)
        assertEquals(2, recent.size)
        assertEquals(b.id, recent[0].id, "newest first")
        assertEquals(a.id, recent[1].id)
    }

    @Test
    fun `reading recentForUser respects limit and user`() {
        ReadingProgressService.save(userOneId, bookItemA, "cfi", 0.1)
        ReadingProgressService.save(userTwoId, bookItemB, "cfi", 0.1)
        val ofUserOne = ReadingProgressService.recentForUser(userOneId)
        assertEquals(1, ofUserOne.size)
        assertEquals(userOneId, ofUserOne.first().user_id)
    }

    // ---------------------- Most-recent-wins on client_recorded_at ----------------------

    @Test
    fun `reading save accepts a write with no client timestamp`() {
        // Pre-rollout clients (and any future caller that just doesn't
        // bother with the timestamp) should still get their writes
        // accepted — this is the back-compat path.
        val saved = ReadingProgressService.save(userOneId, bookItemA, "cfi-old", 0.1)
        assertEquals("cfi-old", saved.cfi)
        assertNull(saved.client_recorded_at, "no client timestamp passed → row stores null")
    }

    @Test
    fun `reading save records client_recorded_at when supplied`() {
        val ts = LocalDateTime.of(2026, 5, 1, 12, 0)
        val saved = ReadingProgressService.save(
            userOneId, bookItemA, "cfi", 0.1, clientRecordedAt = ts)
        assertEquals(ts, saved.client_recorded_at)
    }

    @Test
    fun `reading save drops a strictly older client write`() {
        val newer = LocalDateTime.of(2026, 5, 1, 12, 0)
        val older = LocalDateTime.of(2026, 5, 1, 11, 0)
        ReadingProgressService.save(userOneId, bookItemA, "newer-cfi", 0.5, clientRecordedAt = newer)
        ReadingProgressService.save(userOneId, bookItemA, "older-cfi", 0.2, clientRecordedAt = older)
        val row = ReadingProgressService.get(userOneId, bookItemA)
        assertNotNull(row)
        assertEquals("newer-cfi", row.cfi, "older client write should be dropped")
        assertEquals(0.5, row.percent)
        assertEquals(newer, row.client_recorded_at)
    }

    @Test
    fun `reading save accepts a strictly newer client write`() {
        val older = LocalDateTime.of(2026, 5, 1, 11, 0)
        val newer = LocalDateTime.of(2026, 5, 1, 12, 0)
        ReadingProgressService.save(userOneId, bookItemA, "old-cfi", 0.1, clientRecordedAt = older)
        ReadingProgressService.save(userOneId, bookItemA, "new-cfi", 0.6, clientRecordedAt = newer)
        val row = ReadingProgressService.get(userOneId, bookItemA)
        assertNotNull(row)
        assertEquals("new-cfi", row.cfi)
        assertEquals(0.6, row.percent)
        assertEquals(newer, row.client_recorded_at)
    }

    @Test
    fun `reading save drops a duplicate client timestamp`() {
        val ts = LocalDateTime.of(2026, 5, 1, 12, 0)
        ReadingProgressService.save(userOneId, bookItemA, "first", 0.3, clientRecordedAt = ts)
        ReadingProgressService.save(userOneId, bookItemA, "duplicate", 0.4, clientRecordedAt = ts)
        val row = ReadingProgressService.get(userOneId, bookItemA)
        assertNotNull(row)
        assertEquals("first", row.cfi, "equal-timestamp write should be treated as duplicate and dropped")
    }

    @Test
    fun `reading save accepts client write when existing has no timestamp`() {
        // Old row from a pre-rollout client (no client_recorded_at).
        // First write with a real timestamp upgrades the row.
        ReadingProgressService.save(userOneId, bookItemA, "old", 0.1)
        val ts = LocalDateTime.of(2026, 5, 1, 12, 0)
        ReadingProgressService.save(userOneId, bookItemA, "upgraded", 0.4, clientRecordedAt = ts)
        val row = ReadingProgressService.get(userOneId, bookItemA)
        assertNotNull(row)
        assertEquals("upgraded", row.cfi)
        assertEquals(ts, row.client_recorded_at)
    }

    @Test
    fun `reading save accepts no-timestamp write even when existing has one`() {
        // Mixed-fleet case: a new client wrote with a timestamp, then
        // an old client (no timestamp) writes. The old client doesn't
        // know enough to compare, so we accept its write — matches
        // pre-rollout semantics. The row's timestamp goes back to null.
        val ts = LocalDateTime.of(2026, 5, 1, 12, 0)
        ReadingProgressService.save(userOneId, bookItemA, "new-client", 0.5, clientRecordedAt = ts)
        ReadingProgressService.save(userOneId, bookItemA, "old-client", 0.6)
        val row = ReadingProgressService.get(userOneId, bookItemA)
        assertNotNull(row)
        assertEquals("old-client", row.cfi)
        assertNull(row.client_recorded_at, "no-timestamp write clears the timestamp column")
    }

    @Test
    fun `reading save clamps far-future client timestamps to now`() {
        // Client clock 10 years in the future would otherwise lock out
        // any sane future write. The save should clamp it to ~now.
        val farFuture = LocalDateTime.now().plusYears(10)
        val saved = ReadingProgressService.save(
            userOneId, bookItemA, "cfi", 0.5, clientRecordedAt = farFuture)
        assertNotEquals(farFuture, saved.client_recorded_at)
        // Clamped value should be within a few seconds of "now"; well
        // inside the 5-minute tolerance window the service uses.
        val recordedAt = saved.client_recorded_at
        assertNotNull(recordedAt)
        val diffSeconds = Duration.between(recordedAt, LocalDateTime.now()).abs().seconds
        assertTrue(diffSeconds < 60, "clamped to ~now (within 60s); got diff=${diffSeconds}s")
    }

    @Test
    fun `reading save accepts client timestamps slightly in the future`() {
        // A small future skew (up to 5 minutes per the service's
        // tolerance) should be accepted as-is — clients aren't
        // perfectly synced and we don't want to penalise rounding.
        val nearFuture = LocalDateTime.now().plusMinutes(2)
        val saved = ReadingProgressService.save(
            userOneId, bookItemA, "cfi", 0.5, clientRecordedAt = nearFuture)
        assertEquals(nearFuture, saved.client_recorded_at,
            "near-future timestamp within tolerance should land unchanged")
    }

    // ---------------------- ListeningProgressService ----------------------

    @Test
    fun `listening save creates and updates with duration carry-forward`() {
        val track = trackIds[0]
        ListeningProgressService.save(userOneId, track, positionSeconds = 30, durationSeconds = 200)
        ListeningProgressService.save(userOneId, track, positionSeconds = 60, durationSeconds = null)
        val row = ListeningProgressService.get(userOneId, track)
        assertNotNull(row)
        assertEquals(60, row.position_seconds)
        assertEquals(200, row.duration_seconds, "duration should carry forward when null")
    }

    @Test
    fun `listening save coerces negative position to zero`() {
        val row = ListeningProgressService.save(userOneId, trackIds[0],
            positionSeconds = -10, durationSeconds = 100)
        assertEquals(0, row.position_seconds)
    }

    @Test
    fun `listening delete removes the row`() {
        val track = trackIds[0]
        ListeningProgressService.save(userOneId, track, positionSeconds = 30, durationSeconds = 100)
        ListeningProgressService.delete(userOneId, track)
        assertNull(ListeningProgressService.get(userOneId, track))
    }

    @Test
    fun `listening recentForUser returns most-recently-updated first`() {
        val a = ListeningProgressService.save(userOneId, trackIds[0],
            positionSeconds = 30, durationSeconds = 100)
        Thread.sleep(10)
        val b = ListeningProgressService.save(userOneId, trackIds[1],
            positionSeconds = 30, durationSeconds = 100)
        val recent = ListeningProgressService.recentForUser(userOneId)
        assertEquals(2, recent.size)
        assertEquals(b.id, recent[0].id)
        assertEquals(a.id, recent[1].id)
    }

    @Test
    fun `listening recentForUser limit caps results`() {
        for (track in trackIds.take(15)) {
            ListeningProgressService.save(userOneId, track,
                positionSeconds = 30, durationSeconds = 100)
        }
        assertTrue(ListeningProgressService.recentForUser(userOneId, limit = 5).size == 5)
    }
}
