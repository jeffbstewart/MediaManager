package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [MusicScannerAgent]. Uses real on-host temp directories
 * for the music root (the agent's NIO walk is fast enough that per-test
 * temp dirs work fine), the existing [FakeMusicBrainzService] for the
 * MB lookups, and an inline tag-reader closure so each file's tags can
 * be scripted directly without any subprocess.
 */
internal class MusicScannerAgentTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:musicscannertest;DB_CLOSE_DELAY=-1"
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
    private lateinit var fakeMb: FakeMusicBrainzService
    /** Keyed by absolute path string so host-separator quirks don't bite. */
    private val tagsByPath = mutableMapOf<String, AudioTagReader.AudioTags>()
    private lateinit var agent: MusicScannerAgent
    private lateinit var musicRoot: File

    @Before
    fun reset() {
        UnmatchedAudio.deleteAll()
        Track.deleteAll()
        Title.deleteAll()
        AppConfig.deleteAll()
        tagsByPath.clear()

        clock = TestClock()
        fakeMb = FakeMusicBrainzService()
        agent = MusicScannerAgent(
            clock = clock,
            musicBrainz = fakeMb,
            tagReader = { file -> tagsByPath[file.absolutePath] ?: AudioTagReader.AudioTags.EMPTY },
        )
        // scanOnce's per-dir loop guards on running.get() so stop() can
        // break early; tests don't call start() so the flag would
        // otherwise be false and every dir would be skipped.
        agent.running.set(true)
        musicRoot = Files.createTempDirectory("music-root-").toFile().apply {
            deleteOnExit()
        }
    }

    private fun seedAudioFile(relPath: String): File {
        val target = File(musicRoot, relPath)
        target.parentFile.mkdirs()
        target.writeBytes(ByteArray(0))
        return target
    }

    private fun configureMusicRoot() {
        AppConfig(config_key = MusicScannerAgent.CONFIG_KEY_MUSIC_ROOT,
            config_val = musicRoot.absolutePath).save()
    }

    private fun tagsFor(
        title: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        trackArtist: String? = null,
        trackNumber: Int? = null,
        discNumber: Int? = 1,
        durationSeconds: Int? = null,
        mbReleaseId: String? = null,
        mbRecordingId: String? = null,
        upc: String? = null,
    ) = AudioTagReader.AudioTags(
        title = title,
        album = album,
        albumArtist = albumArtist,
        trackArtist = trackArtist,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = null,
        durationSeconds = durationSeconds,
        musicBrainzReleaseId = mbReleaseId,
        musicBrainzReleaseGroupId = null,
        musicBrainzRecordingId = mbRecordingId,
        musicBrainzArtistId = null,
        upc = upc,
        isrc = null,
        catalogNumber = null,
        label = null,
        genres = emptyList(),
        styles = emptyList(),
        bpm = null,
        timeSignature = null,
    )

    // ---------------------- early-return guards ----------------------

    @Test
    fun `scanOnce noops when music_root_path is not configured`() {
        seedAudioFile("artist/album/01.flac")
        // No AppConfig entry.
        agent.scanOnce()
        assertEquals(0, Track.findAll().size)
        assertEquals(0, UnmatchedAudio.findAll().size)
    }

    @Test
    fun `scanOnce noops when music_root_path points at a non-directory`() {
        AppConfig(config_key = MusicScannerAgent.CONFIG_KEY_MUSIC_ROOT,
            config_val = "/no/such/dir/at/all").save()
        agent.scanOnce()
        assertEquals(0, Track.findAll().size)
        assertEquals(0, UnmatchedAudio.findAll().size)
    }

    @Test
    fun `scanOnce returns without staging when no audio files are found`() {
        configureMusicRoot()
        // Non-audio file — agent skips it.
        File(musicRoot, "readme.txt").writeText("nothing to see")
        agent.scanOnce()
        assertEquals(0, UnmatchedAudio.findAll().size)
    }

    // ---------------------- happy path: link to existing tracks ----------------------

    @Test
    fun `scanOnce links audio files to an existing Title's tracks via MBID tier`() {
        configureMusicRoot()
        val releaseMbid = java.util.UUID.randomUUID().toString()
        val rec1Mbid = java.util.UUID.randomUUID().toString()
        val rec2Mbid = java.util.UUID.randomUUID().toString()
        // Pre-existing album with two empty (un-linked) tracks.
        val album = Title(name = "Animals",
            media_type = MediaType.ALBUM.name,
            sort_name = "animals",
            musicbrainz_release_id = releaseMbid).apply { save() }
        val track1 = Track(title_id = album.id!!, track_number = 1, disc_number = 1,
            name = "Pigs on the Wing",
            musicbrainz_recording_id = rec1Mbid).apply { save() }
        val track2 = Track(title_id = album.id!!, track_number = 2, disc_number = 1,
            name = "Dogs",
            musicbrainz_recording_id = rec2Mbid).apply { save() }

        val file1 = seedAudioFile("Pink Floyd/Animals/01-Pigs.flac")
        val file2 = seedAudioFile("Pink Floyd/Animals/02-Dogs.flac")
        tagsByPath[file1.absolutePath] = tagsFor(title = "Pigs on the Wing", album = "Animals",
            trackNumber = 1, mbReleaseId = releaseMbid, mbRecordingId = rec1Mbid)
        tagsByPath[file2.absolutePath] = tagsFor(title = "Dogs", album = "Animals",
            trackNumber = 2, mbReleaseId = releaseMbid, mbRecordingId = rec2Mbid)
        // The agent calls musicBrainz.lookupByReleaseMbid to validate the
        // candidate against the files' (disc, track) positions.
        fakeMb.byReleaseMbid = mapOf(
            releaseMbid to MusicBrainzResult.Success(
                MusicBrainzReleaseLookup(
                    musicBrainzReleaseId = releaseMbid,
                    musicBrainzReleaseGroupId = java.util.UUID.randomUUID().toString(),
                    title = "Animals",
                    albumArtistCredits = listOf(MusicBrainzArtistCredit(
                        musicBrainzArtistId = java.util.UUID.randomUUID().toString(),
                        name = "Pink Floyd",
                        type = "Group",
                        sortName = "Pink Floyd",
                    )),
                    releaseYear = 1977,
                    label = "Harvest",
                    barcode = null,
                    tracks = listOf(
                        MusicBrainzTrack(rec1Mbid, 1, 1, "Pigs on the Wing", 200, emptyList()),
                        MusicBrainzTrack(rec2Mbid, 2, 1, "Dogs", 1700, emptyList()),
                    ),
                    totalDurationSeconds = 1900,
                    rawJson = "{}",
                )
            )
        )

        agent.scanOnce()

        val refreshed1 = Track.findById(track1.id!!)!!
        val refreshed2 = Track.findById(track2.id!!)!!
        assertEquals(file1.absolutePath, refreshed1.file_path)
        assertEquals(file2.absolutePath, refreshed2.file_path)
        assertEquals(0, UnmatchedAudio.findAll().size,
            "all files linked → nothing parked for triage")
    }

    // ---------------------- staging path: tags with no resolvable identity ----------------------

    @Test
    fun `scanOnce parks a tag-less file in UnmatchedAudio for admin triage`() {
        configureMusicRoot()
        val orphan = seedAudioFile("orphan.flac")
        // Empty tags — no MBID, no UPC, no catno, no ISRC, no album+artist.
        tagsByPath[orphan.absolutePath] = AudioTagReader.AudioTags.EMPTY

        agent.scanOnce()

        val staged = UnmatchedAudio.findAll().single()
        assertEquals(orphan.absolutePath, staged.file_path)
        assertEquals(UnmatchedAudioStatus.UNMATCHED.name, staged.match_status)
        assertEquals(0, Track.findAll().size, "no track linking attempted")
    }

    @Test
    fun `scanOnce skips a file whose path already matches a linked Track`() {
        configureMusicRoot()
        val file = seedAudioFile("known/01.flac")
        // Pre-existing track already linked to this file.
        val album = Title(name = "Album", media_type = MediaType.ALBUM.name,
            sort_name = "album").apply { save() }
        Track(title_id = album.id!!, track_number = 1, disc_number = 1,
            name = "Track", file_path = file.absolutePath).save()

        agent.scanOnce()

        // Already-linked file is filtered out before tagging — no UnmatchedAudio
        // row created.
        assertEquals(0, UnmatchedAudio.findAll().size)
    }

    @Test
    fun `scanOnce skips a file whose path already sits in UnmatchedAudio`() {
        configureMusicRoot()
        val file = seedAudioFile("staged/01.flac")
        UnmatchedAudio(file_path = file.absolutePath, file_name = "01.flac",
            media_format = net.stewart.mediamanager.entity.MediaFormat.AUDIO_FLAC.name,
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = java.time.LocalDateTime.now()).save()
        // Even with empty tags, the previously-staged row should not be
        // re-created on this pass.
        tagsByPath[file.absolutePath] = AudioTagReader.AudioTags.EMPTY

        agent.scanOnce()
        assertEquals(1, UnmatchedAudio.findAll().size,
            "agent skipped the file because it's already in UnmatchedAudio")
    }

    // ---------------------- start / stop lifecycle ----------------------

    @Test
    fun `start is idempotent and stop flips the running flag back`() {
        // The shared @Before sets running = true so scanOnce can complete
        // its per-dir loop in other tests; reset that here so we can
        // observe the start() lifecycle from a clean baseline.
        agent.running.set(false)
        try {
            agent.start()
            assertTrue(agent.running.get())
            agent.start()  // second call must no-op
            assertTrue(agent.running.get())
        } finally {
            agent.stop()
            assertFalse(agent.running.get())
        }
    }
}
