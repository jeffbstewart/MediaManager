package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.Transcode
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class VideoStreamHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource
        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("vidstream") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = VideoStreamHttpService()

    @Before
    fun reset() {
        Transcode.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `subResource returns 404 when transcode is missing (no auth required for files)`() {
        // VideoStream's sub-resources don't gate on the bearer user the
        // same way the rest of the API does — the cookie/key path runs
        // earlier in the request chain. Missing transcode → 404.
        val resp = service.subResource(
            ctxFor("/stream/9999/thumbs.vtt",
                user = getOrCreateUser("admin", level = 2)),
            transcodeId = 9999L, subPath = "thumbs.vtt",
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class CameraStreamHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource
        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("camstream") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = CameraStreamHttpService()

    @Before
    fun reset() {
        Camera.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    @Test
    fun `startRelay returns 404 for unknown camera`() {
        val resp = service.startRelay(
            ctxFor("/cam/9999/start",
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `masterPlaylist returns 404 for unknown camera`() {
        val resp = service.masterPlaylist(
            ctxFor("/cam/9999/stream.m3u8",
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `variantPlaylist returns 404 for unknown camera`() {
        val resp = service.variantPlaylist(
            ctxFor("/cam/9999/hls/live.m3u8",
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `snapshot returns 404 for unknown camera`() {
        val resp = service.snapshot(
            ctxFor("/cam/9999/snapshot.jpg",
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class AudioStreamHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource
        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("audstream") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = AudioStreamHttpService()

    @Before
    fun reset() {
        Track.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `stream returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.stream(
            ctxFor("/audio/1", user = null), trackId = 1L)))
    }

    @Test
    fun `stream returns 404 when track is missing`() {
        val resp = service.stream(
            ctxFor("/audio/9999",
                user = getOrCreateUser("admin", level = 2)),
            trackId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `stream returns 404 when track has no file_path`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = Title(name = "A", media_type = MediaType.ALBUM.name,
            sort_name = "a").apply { save() }
        val track = Track(title_id = title.id!!, disc_number = 1,
            track_number = 1, name = "T",
            file_path = null).apply { save() }
        val resp = service.stream(
            ctxFor("/audio/${track.id}", user = admin),
            trackId = track.id!!,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `reportProgress returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.reportProgress(
            ctxFor("/api/v2/audio/progress", method = HttpMethod.POST,
                user = null, jsonBody = """{}"""))))
    }

    @Test
    fun `reportProgress returns 400 when track_id is missing`() {
        val resp = service.reportProgress(
            ctxFor("/api/v2/audio/progress", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"position_seconds": 30}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `reportProgress returns 400 when position_seconds is missing`() {
        val resp = service.reportProgress(
            ctxFor("/api/v2/audio/progress", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"track_id": 1}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `reportProgress accepts a valid request and returns ok`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = Title(name = "Album", media_type = MediaType.ALBUM.name,
            sort_name = "album").apply { save() }
        val track = Track(title_id = title.id!!, disc_number = 1,
            track_number = 1, name = "T").apply { save() }
        val resp = service.reportProgress(
            ctxFor("/api/v2/audio/progress", method = HttpMethod.POST, user = admin,
                jsonBody = """{"track_id": ${track.id},
                                "position_seconds": 30,
                                "duration_seconds": 180}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class EbookHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource
        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("ebook") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = EbookHttpService()

    @Before
    fun reset() {
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `get returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.get(
            ctxFor("/ebook/1", user = null), mediaItemId = 1L)))
    }

    @Test
    fun `get returns 404 when media item is missing`() {
        val resp = service.get(
            ctxFor("/ebook/9999",
                user = getOrCreateUser("admin", level = 2)),
            mediaItemId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `get returns 404 when media item has no file_path`() {
        val admin = getOrCreateUser("admin", level = 2)
        val item = MediaItem(media_format = MediaFormat.EBOOK_EPUB.name,
            file_path = null).apply { save() }
        val resp = service.get(
            ctxFor("/ebook/${item.id}", user = admin),
            mediaItemId = item.id!!,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `parseRange returns null for unparseable values`() {
        // The companion-object range parser is internal; exercising it
        // here gets the small leftover lines.
        assertEquals(null, service.parseRange("garbage", 1000L))
        assertEquals(null, service.parseRange("bytes=", 1000L))
        // Real range gets parsed.
        val parsed = service.parseRange("bytes=0-99", 1000L)
        assertEquals(0L to 99L, parsed)
    }
}

internal class LiveTvStreamHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource
        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("livetvstr") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = LiveTvStreamHttpService()

    @Before
    fun reset() {
        LiveTvChannel.deleteAll()
        LiveTvTuner.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    @Test
    fun `masterPlaylist returns 404 for unknown channel`() {
        val resp = service.masterPlaylist(
            ctxFor("/live-tv-stream/9999/stream.m3u8",
                user = getOrCreateUser("admin", level = 2)),
            channelId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `playlist returns 404 for unknown channel`() {
        val resp = service.playlist(
            ctxFor("/live-tv-stream/9999/hls/live.m3u8",
                user = getOrCreateUser("admin", level = 2)),
            channelId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `segment returns 400 when segmentName fails the validator regex`() {
        // LiveTvStreamManager.isValidSegmentName accepts only well-formed
        // HLS chunk names (segment*.ts shape). A name that doesn't match
        // returns 400 before the channel lookup runs.
        val resp = service.segment(
            ctxFor("/live-tv-stream/1/segment/bogus.txt",
                user = getOrCreateUser("admin", level = 2)),
            channelId = 1L, segmentName = "bogus.txt",
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }
}

internal class RokuFeedHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource
        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("rokufeed") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = RokuFeedHttpService()

    @Before
    fun reset() {
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    // Roku endpoints use a separate ?key=... API key auth (not the
    // bearer/cookie user the rest of armeria reads). With no key
    // configured the endpoints all return 401, which is what these
    // tests exercise.

    @Test
    fun `feed returns 401 without api key`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.feed(
            ctxFor("/roku/feed.json", user = null))))
    }

    @Test
    fun `home returns 401 without api key`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.home(
            ctxFor("/roku/home.json", user = null))))
    }

    @Test
    fun `search returns 401 without api key`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.search(
            ctxFor("/roku/search.json", user = null), q = "")))
    }
}

internal class ImageProxyHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource
        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("imgproxy") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = ImageProxyHttpService()

    @Before fun reset() { AppUser.deleteAll() }

    // Image proxies are unauthenticated by design — they front a public
    // image CDN with input validation + SSRF screening. Tests cover the
    // pure-function input gates.

    @Test
    fun `tmdb returns 400 for an invalid size`() {
        assertEquals(HttpStatus.BAD_REQUEST,
            statusOf(service.tmdb(size = "junk", file = "abc.jpg")))
    }

    @Test
    fun `tmdb returns 400 for a filename with no extension`() {
        // The regex requires \.<ext> at the end — bare names get refused.
        assertEquals(HttpStatus.BAD_REQUEST,
            statusOf(service.tmdb(size = "w185", file = "noextension")))
    }

    @Test
    fun `openLibrary returns 400 for an unknown kind`() {
        assertEquals(HttpStatus.BAD_REQUEST,
            statusOf(service.openLibrary(kind = "podcast", key = "x", size = "M")))
    }

    @Test
    fun `openLibrary returns 400 for an invalid size`() {
        assertEquals(HttpStatus.BAD_REQUEST,
            statusOf(service.openLibrary(kind = "isbn", key = "9780000000000",
                size = "ZZ")))
    }

    @Test
    fun `coverArtArchive returns 400 for malformed MBID`() {
        assertEquals(HttpStatus.BAD_REQUEST,
            statusOf(service.coverArtArchive(mbid = "not-a-uuid", size = "front-500")))
    }

    @Test
    fun `coverArtArchiveReleaseGroup returns 400 for malformed MBID`() {
        assertEquals(HttpStatus.BAD_REQUEST,
            statusOf(service.coverArtArchiveReleaseGroup(rgid = "garbage",
                size = "front-500")))
    }
}

internal class ActorHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource
        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("actor") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = ActorHttpService()

    @Before fun reset() { AppUser.deleteAll() }

    @Test
    fun `actorDetail returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.actorDetail(
            ctxFor("/api/v2/catalog/actor/1", user = null), personId = 1)))
    }
}
