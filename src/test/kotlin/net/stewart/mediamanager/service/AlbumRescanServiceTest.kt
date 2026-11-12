package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [AlbumRescanService] — focused on the early-return /
 * no-work paths. The full file-walking + tag-matching loop is
 * exercised end-to-end by an integration test elsewhere; here we
 * pin the four [AlbumRescanService.Outcome] values that the gRPC
 * and REST handlers translate to status codes.
 */
class AlbumRescanServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:rescantest;DB_CLOSE_DELAY=-1"
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

    @get:Rule
    val tempDir = TemporaryFolder()

    @Before
    fun reset() {
        Track.deleteAll()
        Title.deleteAll()
        AppConfig.deleteAll()
    }

    @Test
    fun `rescan returns TitleNotFound for missing id`() {
        val outcome = AlbumRescanService.rescan(99999)
        assertEquals(AlbumRescanService.Outcome.TitleNotFound, outcome)
    }

    @Test
    fun `rescan returns NoTracks when album has no rows`() {
        val title = Title(name = "Empty", media_type = MediaType.ALBUM.name)
        title.save()
        val outcome = AlbumRescanService.rescan(title.id!!)
        assertEquals(AlbumRescanService.Outcome.NoTracks, outcome)
    }

    @Test
    fun `rescan returns NoSearchRoot when no siblings linked and music_root unset`() {
        // Title with two unlinked tracks and no music_root_path config:
        // the walker has nothing to traverse, so we surface that
        // explicitly instead of attempting a doomed scan.
        val title = Title(name = "Album", media_type = MediaType.ALBUM.name)
        title.save()
        Track(title_id = title.id!!, name = "Track 1", track_number = 1, disc_number = 1).save()
        Track(title_id = title.id!!, name = "Track 2", track_number = 2, disc_number = 1).save()
        val outcome = AlbumRescanService.rescan(title.id!!)
        assertEquals(AlbumRescanService.Outcome.NoSearchRoot, outcome)
    }

    @Test
    fun `rescan returns Success with all-already-linked summary`() {
        // Both tracks linked and the linked dir exists — the walker
        // discovers no unlinked tracks and short-circuits with the
        // "already linked, nothing to do" message.
        val albumDir = tempDir.newFolder("album")
        val title = Title(name = "Linked", media_type = MediaType.ALBUM.name)
        title.save()
        Track(
            title_id = title.id!!, name = "T1", track_number = 1, disc_number = 1,
            file_path = albumDir.resolve("01.flac").absolutePath,
        ).save()
        Track(
            title_id = title.id!!, name = "T2", track_number = 2, disc_number = 1,
            file_path = albumDir.resolve("02.flac").absolutePath,
        ).save()

        val outcome = AlbumRescanService.rescan(title.id!!)
        assertTrue(outcome is AlbumRescanService.Outcome.Success)
        val r = outcome.result
        assertEquals(0, r.linked)
        assertEquals(2, r.skippedAlreadyLinked)
        assertEquals(0, r.candidatesConsidered)
        assertNotNull(r.message)
        assertTrue(r.message.contains("already linked"))
    }

    @Test
    fun `rescan with music_root only finds no candidates`() {
        // No siblings linked, but music_root is configured to an
        // empty directory — the walker traverses but finds nothing.
        // This drives the primaryRoot=musicRoot + path-prefilter
        // branch and the empty-walk Success outcome.
        val musicRoot = tempDir.newFolder("music")
        AppConfig(config_key = "music_root_path", config_val = musicRoot.absolutePath).save()

        val title = Title(name = "Lonely Album", media_type = MediaType.ALBUM.name)
        title.save()
        Track(title_id = title.id!!, name = "Solo", track_number = 1, disc_number = 1).save()

        val outcome = AlbumRescanService.rescan(title.id!!)
        assertTrue(outcome is AlbumRescanService.Outcome.Success)
        val r = outcome.result
        assertEquals(0, r.linked)
        assertEquals(0, r.candidatesConsidered)
        // The walker still records the music_root path it traversed.
        assertEquals(musicRoot.absolutePath, r.musicRootConfigured)
        assertTrue(r.rootsWalked.contains(musicRoot.absolutePath))
    }
}
