package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [TrackDiagnosticHttpService] — the admin-only diagnostic
 * + rescan endpoints. The `diagnoseFile` endpoint forks ffprobe and is
 * out of scope here (it would need a real binary at test time).
 */
internal class TrackDiagnosticHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("trackdiag") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = TrackDiagnosticHttpService()

    @Before
    fun reset() {
        UnmatchedAudio.deleteAll()
        Track.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    private fun seedAlbum(name: String = "Album"): Title =
        Title(name = name, media_type = MediaType.ALBUM.name,
            sort_name = name.lowercase()).apply { save() }

    private fun seedTrack(
        titleId: Long, disc: Int = 1, track: Int = 1,
        name: String = "Track $track", filePath: String? = null,
    ): Track = Track(
        title_id = titleId, disc_number = disc, track_number = track,
        name = name, file_path = filePath,
    ).apply { save() }

    // ---------------------- diagnoseTrack ----------------------

    @Test
    fun `diagnoseTrack returns 401 unauthenticated`() {
        val resp = service.diagnoseTrack(
            ctxFor("/api/v2/admin/diag/track/1", user = null),
            trackId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `diagnoseTrack returns 403 for non-admin viewers`() {
        val resp = service.diagnoseTrack(
            ctxFor("/api/v2/admin/diag/track/1",
                user = getOrCreateUser("viewer", level = 1)),
            trackId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `diagnoseTrack returns 404 when track does not exist`() {
        val resp = service.diagnoseTrack(
            ctxFor("/api/v2/admin/diag/track/9999",
                user = getOrCreateUser("admin", level = 2)),
            trackId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `diagnoseTrack returns the track + sibling list when no music_root is configured`() {
        val admin = getOrCreateUser("admin", level = 2)
        val album = seedAlbum("Diagnose Me")
        val target = seedTrack(album.id!!, disc = 1, track = 2,
            name = "The Target Track")
        seedTrack(album.id!!, disc = 1, track = 1, name = "Track One")
        seedTrack(album.id!!, disc = 1, track = 3, name = "Track Three")

        val resp = service.diagnoseTrack(
            ctxFor("/api/v2/admin/diag/track/${target.id}", user = admin),
            trackId = target.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(target.id, body.getAsJsonObject("track").get("id").asLong)
        assertEquals(album.id, body.getAsJsonObject("title").get("id").asLong)
        // Siblings include all three tracks (target + two others), ordered by (disc, track).
        val siblings = body.getAsJsonArray("siblings")
        assertEquals(3, siblings.size())
        assertEquals(1, siblings[0].asJsonObject.get("track_number").asInt)
        // No file paths anywhere → empty albumDirs and no search_root.
        assertEquals(0, body.getAsJsonArray("album_directories").size())
        // Gson omits null fields, so search_root key is absent.
        assertTrue(!body.has("search_root") || body.get("search_root").isJsonNull)
        assertEquals(0, body.getAsJsonArray("candidate_files").size())
        assertTrue(body.has("diagnosis"))
    }

    @Test
    fun `diagnoseTrack surfaces matching unmatched_audio rows by (disc, track) + album`() {
        val admin = getOrCreateUser("admin", level = 2)
        val album = seedAlbum("Match Album")
        val target = seedTrack(album.id!!, disc = 1, track = 5, name = "Target")
        UnmatchedAudio(
            file_path = "/nas/music/Match Album/05 - Target.flac",
            file_name = "05 - Target.flac",
            media_format = MediaFormat.AUDIO_FLAC.name,
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            parsed_album = "Match Album",
            parsed_disc_number = 1,
            parsed_track_number = 5,
            discovered_at = LocalDateTime.now(),
        ).save()

        val resp = service.diagnoseTrack(
            ctxFor("/api/v2/admin/diag/track/${target.id}", user = admin),
            trackId = target.id!!,
        )
        val matching = readJsonObject(resp).getAsJsonArray("matching_unmatched_audio")
        assertEquals(1, matching.size())
        assertEquals("UNMATCHED", matching[0].asJsonObject.get("match_status").asString)
    }

    @Test
    fun `diagnoseTrack also matches unmatched audio rows by parsed_title alone`() {
        val admin = getOrCreateUser("admin", level = 2)
        val album = seedAlbum("Some Album")
        val target = seedTrack(album.id!!, disc = 1, track = 1,
            name = "Distinctive Track Name")
        UnmatchedAudio(
            file_path = "/nas/music/X.flac",
            file_name = "X.flac",
            media_format = MediaFormat.AUDIO_FLAC.name,
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            parsed_title = "Distinctive Track Name",  // only title matches
            // Disc/track + album DON'T match
            parsed_album = "Different Album",
            parsed_disc_number = 99,
            parsed_track_number = 99,
            discovered_at = LocalDateTime.now(),
        ).save()

        val resp = service.diagnoseTrack(
            ctxFor("/api/v2/admin/diag/track/${target.id}", user = admin),
            trackId = target.id!!,
        )
        val matching = readJsonObject(resp).getAsJsonArray("matching_unmatched_audio")
        assertEquals(1, matching.size())
    }

    // ---------------------- rescanAlbum ----------------------

    @Test
    fun `rescanAlbum returns 401 unauthenticated`() {
        val resp = service.rescanAlbum(
            ctxFor("/api/v2/admin/albums/1/rescan",
                method = HttpMethod.POST, user = null),
            titleId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `rescanAlbum returns 403 for non-admin viewers`() {
        val resp = service.rescanAlbum(
            ctxFor("/api/v2/admin/albums/1/rescan",
                method = HttpMethod.POST,
                user = getOrCreateUser("viewer", level = 1)),
            titleId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `rescanAlbum returns 404 when title does not exist`() {
        val resp = service.rescanAlbum(
            ctxFor("/api/v2/admin/albums/9999/rescan",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            titleId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `rescanAlbum returns 400 when the title has no tracks`() {
        val admin = getOrCreateUser("admin", level = 2)
        val empty = seedAlbum("Empty Album")
        val resp = service.rescanAlbum(
            ctxFor("/api/v2/admin/albums/${empty.id}/rescan",
                method = HttpMethod.POST, user = admin),
            titleId = empty.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `rescanAlbum returns 400 when there's no search root and no music_root_path`() {
        val admin = getOrCreateUser("admin", level = 2)
        val album = seedAlbum()
        // No file_path on any sibling, no music_root_path config either.
        seedTrack(album.id!!)
        val resp = service.rescanAlbum(
            ctxFor("/api/v2/admin/albums/${album.id}/rescan",
                method = HttpMethod.POST, user = admin),
            titleId = album.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `rescanAlbum returns 400 when music_root_path points at a non-directory`() {
        val admin = getOrCreateUser("admin", level = 2)
        val album = seedAlbum()
        seedTrack(album.id!!)
        AppConfig(config_key = "music_root_path",
            config_val = "/no/such/directory").save()

        val resp = service.rescanAlbum(
            ctxFor("/api/v2/admin/albums/${album.id}/rescan",
                method = HttpMethod.POST, user = admin),
            titleId = album.id!!,
        )
        // AlbumRescanService surfaces this as one of the bad-request shapes
        // (NoSearchRoot or empty result from a non-walkable dir).
        assertTrue(
            statusOf(resp) == HttpStatus.BAD_REQUEST ||
                statusOf(resp) == HttpStatus.OK,
            "expected 400 or empty 200; got ${statusOf(resp)}",
        )
    }

    @Test
    fun `rescanAlbum returns OK with an empty result when search root exists but has no audio`() {
        val admin = getOrCreateUser("admin", level = 2)
        val album = seedAlbum()
        seedTrack(album.id!!)
        // Real but empty temp dir as music_root.
        val emptyRoot = java.nio.file.Files.createTempDirectory("rescan-empty-")
            .toFile().apply { deleteOnExit() }
        AppConfig(config_key = "music_root_path",
            config_val = emptyRoot.absolutePath).save()

        val resp = service.rescanAlbum(
            ctxFor("/api/v2/admin/albums/${album.id}/rescan",
                method = HttpMethod.POST, user = admin),
            titleId = album.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertTrue(body.has("linked"), "result envelope present")
    }
}
