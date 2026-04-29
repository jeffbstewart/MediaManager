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
import kotlin.test.Test
import kotlin.test.assertEquals
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
