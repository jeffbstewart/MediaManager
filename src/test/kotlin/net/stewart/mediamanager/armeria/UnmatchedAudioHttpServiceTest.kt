package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import net.stewart.mediamanager.service.FakeMusicBrainzService
import net.stewart.mediamanager.service.MusicBrainzResult
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage for [UnmatchedAudioHttpService] — admin-only triage queue
 * for audio files the music scanner couldn't auto-link to a [Track].
 * Built against a [FakeMusicBrainzService] so every MB-touching path
 * is deterministic and offline.
 *
 * Heavy ingestion paths (linkAlbumToRelease / linkAlbumManual) are
 * exercised through their argument-validation gates; the deeper
 * MusicIngestionService machinery isn't in scope for this slice.
 */
internal class UnmatchedAudioHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("unmatchedaudio") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private lateinit var fakeMb: FakeMusicBrainzService
    private lateinit var service: UnmatchedAudioHttpService

    @Before
    fun reset() {
        UnmatchedAudio.deleteAll()
        TitleArtist.deleteAll()
        Track.deleteAll()
        Title.deleteAll()
        Artist.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()

        fakeMb = FakeMusicBrainzService()
        service = UnmatchedAudioHttpService(musicBrainz = fakeMb)
    }

    private fun seedRow(
        path: String,
        album: String? = null,
        albumArtist: String? = null,
        title: String? = null,
        disc: Int? = null,
        track: Int? = null,
        upc: String? = null,
        status: UnmatchedAudioStatus = UnmatchedAudioStatus.UNMATCHED,
    ): UnmatchedAudio = UnmatchedAudio(
        file_path = path,
        file_name = path.substringAfterLast('/'),
        media_format = MediaFormat.AUDIO_FLAC.name,
        match_status = status.name,
        parsed_album = album,
        parsed_album_artist = albumArtist,
        parsed_title = title,
        parsed_disc_number = disc,
        parsed_track_number = track,
        parsed_upc = upc,
        discovered_at = LocalDateTime.now(),
    ).apply { save() }

    // ---------------------- list ----------------------

    @Test
    fun `list returns 401 unauthenticated`() {
        val resp = service.list(ctxFor("/api/v2/admin/unmatched-audio", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `list returns 403 for non-admin viewers`() {
        val resp = service.list(ctxFor("/api/v2/admin/unmatched-audio",
            user = getOrCreateUser("viewer", level = 1)))
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `list returns only UNMATCHED rows, sorted by discovered_at desc`() {
        seedRow("/nas/x/a.flac", album = "A", title = "T1")
        seedRow("/nas/x/b.flac", album = "A", title = "T2",
            status = UnmatchedAudioStatus.LINKED)
        seedRow("/nas/x/c.flac", album = "A", title = "T3",
            status = UnmatchedAudioStatus.IGNORED)

        val resp = service.list(
            ctxFor("/api/v2/admin/unmatched-audio",
                user = getOrCreateUser("admin", level = 2))
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(1, body.get("total").asInt,
            "only the UNMATCHED row should appear")
        assertEquals("a.flac", body.getAsJsonArray("files")[0]
            .asJsonObject.get("file_name").asString)
    }

    // ---------------------- linkToTrack ----------------------

    @Test
    fun `linkToTrack returns 401 unauthenticated`() {
        val resp = service.linkToTrack(
            ctxFor("/api/v2/admin/unmatched-audio/1/link-track",
                method = HttpMethod.POST, user = null,
                jsonBody = """{"track_id": 1}"""),
            id = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `linkToTrack returns 403 for non-admin viewers`() {
        val resp = service.linkToTrack(
            ctxFor("/api/v2/admin/unmatched-audio/1/link-track",
                method = HttpMethod.POST,
                user = getOrCreateUser("viewer", level = 1),
                jsonBody = """{"track_id": 1}"""),
            id = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `linkToTrack returns 404 when the unmatched-audio row is gone`() {
        val resp = service.linkToTrack(
            ctxFor("/api/v2/admin/unmatched-audio/9999/link-track",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"track_id": 1}"""),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `linkToTrack returns 400 when track_id is missing from body`() {
        val row = seedRow("/nas/x/a.flac")
        val resp = service.linkToTrack(
            ctxFor("/api/v2/admin/unmatched-audio/${row.id}/link-track",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""),
            id = row.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `linkToTrack returns 404 when the target track is gone`() {
        val row = seedRow("/nas/x/a.flac")
        val resp = service.linkToTrack(
            ctxFor("/api/v2/admin/unmatched-audio/${row.id}/link-track",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"track_id": 9999}"""),
            id = row.id!!,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `linkToTrack returns 400 when target track is already linked to a different file`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = Title(name = "Album", media_type = MediaType.ALBUM.name,
            sort_name = "album").apply { save() }
        val track = Track(title_id = title.id!!, disc_number = 1, track_number = 1,
            name = "T", file_path = "/nas/different/file.flac").apply { save() }
        val row = seedRow("/nas/x/new.flac")

        val resp = service.linkToTrack(
            ctxFor("/api/v2/admin/unmatched-audio/${row.id}/link-track",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"track_id": ${track.id}}"""),
            id = row.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `linkToTrack happy path attaches file_path and flips status to LINKED`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = Title(name = "Album", media_type = MediaType.ALBUM.name,
            sort_name = "album").apply { save() }
        val track = Track(title_id = title.id!!, disc_number = 1, track_number = 1,
            name = "Track").apply { save() }
        val row = seedRow("/nas/x/match.flac")

        val resp = service.linkToTrack(
            ctxFor("/api/v2/admin/unmatched-audio/${row.id}/link-track",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"track_id": ${track.id}}"""),
            id = row.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val refreshedTrack = Track.findById(track.id!!)!!
        assertEquals("/nas/x/match.flac", refreshedTrack.file_path)
        val refreshedRow = UnmatchedAudio.findById(row.id!!)!!
        assertEquals(UnmatchedAudioStatus.LINKED.name, refreshedRow.match_status)
        assertEquals(track.id, refreshedRow.linked_track_id)
    }

    // ---------------------- searchTracks ----------------------

    @Test
    fun `searchTracks returns 403 for non-admin viewers`() {
        val resp = service.searchTracks(
            ctxFor("/api/v2/admin/unmatched-audio/search-tracks?q=hi",
                user = getOrCreateUser("viewer", level = 1)),
            query = "hi",
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `searchTracks returns empty list for queries shorter than 2 chars`() {
        val resp = service.searchTracks(
            ctxFor("/api/v2/admin/unmatched-audio/search-tracks?q=a",
                user = getOrCreateUser("admin", level = 2)),
            query = "a",
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(0, readJsonObject(resp).getAsJsonArray("tracks").size())
    }

    @Test
    fun `searchTracks finds tracks by name and includes album-only hits`() {
        val admin = getOrCreateUser("admin", level = 2)
        val matchingAlbum = Title(name = "The Matrix Soundtrack",
            media_type = MediaType.ALBUM.name,
            sort_name = "matrix soundtrack").apply { save() }
        val unrelatedAlbum = Title(name = "Other Album",
            media_type = MediaType.ALBUM.name,
            sort_name = "other album").apply { save() }
        // Track on the matching album — matches via album-name even
        // though its name doesn't contain "matrix".
        val byAlbum = Track(title_id = matchingAlbum.id!!,
            disc_number = 1, track_number = 1,
            name = "Wake Up").apply { save() }
        // Track on an unrelated album whose NAME contains the query.
        val byName = Track(title_id = unrelatedAlbum.id!!,
            disc_number = 1, track_number = 1,
            name = "Matrix Theme").apply { save() }

        val resp = service.searchTracks(
            ctxFor("/api/v2/admin/unmatched-audio/search-tracks?q=matrix",
                user = admin),
            query = "matrix",
        )
        val results = readJsonObject(resp).getAsJsonArray("tracks")
        // Both tracks should come back.
        val ids = results.map { it.asJsonObject.get("track_id").asLong }.toSet()
        assertTrue(byAlbum.id in ids)
        assertTrue(byName.id in ids)
    }

    // ---------------------- ignore ----------------------

    @Test
    fun `ignore returns 401 unauthenticated`() {
        val resp = service.ignore(
            ctxFor("/api/v2/admin/unmatched-audio/1/ignore",
                method = HttpMethod.POST, user = null),
            id = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `ignore returns 403 for non-admin viewers`() {
        val resp = service.ignore(
            ctxFor("/api/v2/admin/unmatched-audio/1/ignore",
                method = HttpMethod.POST, user = getOrCreateUser("viewer", level = 1)),
            id = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `ignore returns 404 when the row is gone`() {
        val resp = service.ignore(
            ctxFor("/api/v2/admin/unmatched-audio/9999/ignore",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `ignore flips the row status to IGNORED`() {
        val admin = getOrCreateUser("admin", level = 2)
        val row = seedRow("/nas/x/a.flac")
        val resp = service.ignore(
            ctxFor("/api/v2/admin/unmatched-audio/${row.id}/ignore",
                method = HttpMethod.POST, user = admin),
            id = row.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(UnmatchedAudioStatus.IGNORED.name,
            UnmatchedAudio.findById(row.id!!)!!.match_status)
    }

    // ---------------------- listGroups ----------------------

    @Test
    fun `listGroups returns 401 unauthenticated`() {
        val resp = service.listGroups(
            ctxFor("/api/v2/admin/unmatched-audio/groups", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `listGroups returns 403 for non-admin viewers`() {
        val resp = service.listGroups(
            ctxFor("/api/v2/admin/unmatched-audio/groups",
                user = getOrCreateUser("viewer", level = 1)))
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `listGroups groups files by (album_artist, album)`() {
        val admin = getOrCreateUser("admin", level = 2)
        // Two files in album "A" by "Artist X" — should collapse to 1 group.
        seedRow("/nas/x/A/1.flac", album = "A", albumArtist = "Artist X",
            title = "T1", disc = 1, track = 1)
        seedRow("/nas/x/A/2.flac", album = "A", albumArtist = "Artist X",
            title = "T2", disc = 1, track = 2)
        // Different album — separate group.
        seedRow("/nas/x/B/1.flac", album = "B", albumArtist = "Artist Y",
            title = "T", disc = 1, track = 1)

        val resp = service.listGroups(
            ctxFor("/api/v2/admin/unmatched-audio/groups", user = admin)
        )
        val body = readJsonObject(resp)
        assertEquals(2, body.get("total_groups").asInt)
        assertEquals(3, body.get("total_files").asInt)
    }

    // ---------------------- searchMusicBrainz ----------------------

    @Test
    fun `searchMusicBrainz returns 401 unauthenticated`() {
        val resp = service.searchMusicBrainz(
            ctxFor("/api/v2/admin/unmatched-audio/musicbrainz-search",
                method = HttpMethod.POST, user = null,
                jsonBody = """{}""")
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `searchMusicBrainz returns 403 for non-admin viewers`() {
        val resp = service.searchMusicBrainz(
            ctxFor("/api/v2/admin/unmatched-audio/musicbrainz-search",
                method = HttpMethod.POST,
                user = getOrCreateUser("viewer", level = 1),
                jsonBody = """{}""")
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `searchMusicBrainz returns 400 when unmatched_audio_ids is missing`() {
        val resp = service.searchMusicBrainz(
            ctxFor("/api/v2/admin/unmatched-audio/musicbrainz-search",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `searchMusicBrainz returns 400 when unmatched_audio_ids is empty`() {
        val resp = service.searchMusicBrainz(
            ctxFor("/api/v2/admin/unmatched-audio/musicbrainz-search",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"unmatched_audio_ids": []}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `searchMusicBrainz returns 400 when none of the ids exist`() {
        val resp = service.searchMusicBrainz(
            ctxFor("/api/v2/admin/unmatched-audio/musicbrainz-search",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"unmatched_audio_ids": [9999, 9998]}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `searchMusicBrainz with a direct MBID override skips tier search`() {
        val admin = getOrCreateUser("admin", level = 2)
        val row = seedRow("/nas/x/a.flac")
        val mbid = java.util.UUID.randomUUID().toString()
        // No releases configured in the fake → NotFound, but the
        // production code still routes to runDirectMbidLookup, which
        // returns a 200 with an empty candidates list.
        val resp = service.searchMusicBrainz(
            ctxFor("/api/v2/admin/unmatched-audio/musicbrainz-search",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"unmatched_audio_ids": [${row.id}],
                                "query_override": "$mbid"}""")
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(mbid, body.get("search_album").asString)
        assertEquals(0, body.getAsJsonArray("candidates").size())
    }

    // ---------------------- linkAlbumToRelease ----------------------

    @Test
    fun `linkAlbumToRelease returns 401 unauthenticated`() {
        val resp = service.linkAlbumToRelease(
            ctxFor("/api/v2/admin/unmatched-audio/link-album-to-release",
                method = HttpMethod.POST, user = null,
                jsonBody = """{}""")
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `linkAlbumToRelease returns 403 for viewers`() {
        val resp = service.linkAlbumToRelease(
            ctxFor("/api/v2/admin/unmatched-audio/link-album-to-release",
                method = HttpMethod.POST,
                user = getOrCreateUser("viewer", level = 1),
                jsonBody = """{}""")
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `linkAlbumToRelease returns 400 when unmatched_audio_ids is missing`() {
        val resp = service.linkAlbumToRelease(
            ctxFor("/api/v2/admin/unmatched-audio/link-album-to-release",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"release_mbid": "any"}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `linkAlbumToRelease returns 400 when release_mbid is missing`() {
        val row = seedRow("/nas/x/a.flac")
        val resp = service.linkAlbumToRelease(
            ctxFor("/api/v2/admin/unmatched-audio/link-album-to-release",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"unmatched_audio_ids": [${row.id}]}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `linkAlbumToRelease returns 400 when none of the ids exist`() {
        val resp = service.linkAlbumToRelease(
            ctxFor("/api/v2/admin/unmatched-audio/link-album-to-release",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"unmatched_audio_ids": [9999], "release_mbid": "abc"}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `linkAlbumToRelease returns 400 when MB lookup says NotFound`() {
        val admin = getOrCreateUser("admin", level = 2)
        val row = seedRow("/nas/x/a.flac")
        // No fake response configured → MB returns NotFound.
        val resp = service.linkAlbumToRelease(
            ctxFor("/api/v2/admin/unmatched-audio/link-album-to-release",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"unmatched_audio_ids": [${row.id}],
                                "release_mbid": "${java.util.UUID.randomUUID()}"}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `linkAlbumToRelease returns 400 when MB lookup errors`() {
        val admin = getOrCreateUser("admin", level = 2)
        val row = seedRow("/nas/x/a.flac")
        val mbid = java.util.UUID.randomUUID().toString()
        fakeMb.byReleaseMbid = mapOf(
            mbid to MusicBrainzResult.Error("network down", rateLimited = false)
        )
        val resp = service.linkAlbumToRelease(
            ctxFor("/api/v2/admin/unmatched-audio/link-album-to-release",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"unmatched_audio_ids": [${row.id}],
                                "release_mbid": "$mbid"}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    // ---------------------- linkAlbumManual ----------------------

    @Test
    fun `linkAlbumManual returns 403 for viewers`() {
        val resp = service.linkAlbumManual(
            ctxFor("/api/v2/admin/unmatched-audio/link-album-manual",
                method = HttpMethod.POST,
                user = getOrCreateUser("viewer", level = 1),
                jsonBody = """{"unmatched_audio_ids": [1]}""")
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `linkAlbumManual returns 400 when unmatched_audio_ids is missing or empty`() {
        val admin = getOrCreateUser("admin", level = 2)
        for (body in listOf("""{}""", """{"unmatched_audio_ids": []}""")) {
            val resp = service.linkAlbumManual(
                ctxFor("/api/v2/admin/unmatched-audio/link-album-manual",
                    method = HttpMethod.POST, user = admin, jsonBody = body)
            )
            assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp),
                "expected 400 for body=$body")
        }
    }

    @Test
    fun `linkAlbumManual returns 400 when none of the ids exist`() {
        val resp = service.linkAlbumManual(
            ctxFor("/api/v2/admin/unmatched-audio/link-album-manual",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"unmatched_audio_ids": [9999]}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }
}
