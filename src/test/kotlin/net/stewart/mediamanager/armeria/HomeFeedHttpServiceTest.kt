package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.DiscoveredFileStatus
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.PlaybackProgress
import net.stewart.mediamanager.entity.ProblemReport
import net.stewart.mediamanager.entity.ReportStatus
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Integration coverage of [HomeFeedHttpService] — drives each `@Get` /
 * `@Post` / `@Delete` method through the full DB-backed code path with
 * a real H2 schema. The harness ([ArmeriaTestBase]) skips the Armeria
 * server and feeds a builder-produced [com.linecorp.armeria.server.ServiceRequestContext]
 * pre-stamped with the authenticated user, since that's the only piece
 * of HTTP context the production code reads.
 */
internal class HomeFeedHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("homefeed") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = HomeFeedHttpService()

    @Before
    fun reset() {
        // FK-safe cleanup. PlaybackProgress refs Transcode + AppUser,
        // Transcode refs Title, etc.
        PlaybackProgress.deleteAll()
        Transcode.deleteAll()
        UnmatchedAudio.deleteAll()
        ProblemReport.deleteAll()
        DiscoveredFile.deleteAll()
        Camera.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    // ---------------------- /api/v2/catalog/home ----------------------

    @Test
    fun `homeFeed returns 401 for an unauthenticated request`() {
        val resp = service.homeFeed(ctxFor("/api/v2/catalog/home", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `homeFeed returns the full carousel payload for an admin on an empty catalog`() {
        val resp = service.homeFeed(adminCtx())
        assertEquals(HttpStatus.OK, statusOf(resp))

        val body = readJsonObject(resp)
        // Every documented carousel key is present, even on an empty
        // catalog — the Angular shell expects them as empty arrays.
        for (key in listOf(
            "continue_watching", "recently_added", "recently_added_books",
            "recently_added_albums", "resume_listening", "resume_reading",
            "recently_watched", "missing_seasons", "features",
        )) {
            assertTrue(body.has(key), "missing key '$key' in homeFeed response")
        }

        val features = body.getAsJsonObject("features")
        assertEquals(true, features.get("is_admin").asBoolean)
        // Counts default to 0 on the empty catalog.
        assertEquals(0, features.get("unmatched_count").asInt)
        assertEquals(0, features.get("wish_ready_count").asInt)
    }

    @Test
    fun `homeFeed surfaces the unmatched discovered_file count for admins`() {
        val title = Title(name = "Movie", media_type = MediaType.MOVIE.name,
            sort_name = "movie").apply { save() }
        // Two unmatched discovered files + one matched.
        DiscoveredFile(file_path = "/nas/u1.mkv", file_name = "u1.mkv", directory = "Movies",
            media_format = MediaFormat.UNKNOWN.name, media_type = MediaType.MOVIE.name,
            match_status = DiscoveredFileStatus.UNMATCHED.name).save()
        DiscoveredFile(file_path = "/nas/u2.mkv", file_name = "u2.mkv", directory = "Movies",
            media_format = MediaFormat.UNKNOWN.name, media_type = MediaType.MOVIE.name,
            match_status = DiscoveredFileStatus.UNMATCHED.name).save()
        DiscoveredFile(file_path = "/nas/m.mkv", file_name = "m.mkv", directory = "Movies",
            media_format = MediaFormat.UNKNOWN.name, media_type = MediaType.MOVIE.name,
            matched_title_id = title.id,
            match_status = DiscoveredFileStatus.MATCHED.name).save()

        val resp = service.homeFeed(adminCtx())
        val features = readJsonObject(resp).getAsJsonObject("features")
        assertEquals(2, features.get("unmatched_count").asInt)
    }

    @Test
    fun `homeFeed zeroes admin-only counts for a non-admin viewer`() {
        // An unmatched file exists, but the viewer should not see it
        // reflected in the badge count.
        DiscoveredFile(file_path = "/nas/u.mkv", file_name = "u.mkv", directory = "Movies",
            media_format = MediaFormat.UNKNOWN.name, media_type = MediaType.MOVIE.name,
            match_status = DiscoveredFileStatus.UNMATCHED.name).save()

        val resp = service.homeFeed(viewerCtx())
        val features = readJsonObject(resp).getAsJsonObject("features")
        assertEquals(0, features.get("unmatched_count").asInt)
        assertEquals(0, features.get("data_quality_count").asInt)
        assertEquals(0, features.get("open_reports_count").asInt)
        assertEquals(false, features.get("is_admin").asBoolean)
    }

    @Test
    fun `homeFeed flags has_books, has_music, has_personal_videos based on Title media_type`() {
        Title(name = "Movie", media_type = MediaType.MOVIE.name, sort_name = "movie").save()
        Title(name = "Book", media_type = MediaType.BOOK.name, sort_name = "book",
            enrichment_status = EnrichmentStatus.ENRICHED.name).save()
        Title(name = "Album", media_type = MediaType.ALBUM.name, sort_name = "album",
            enrichment_status = EnrichmentStatus.ENRICHED.name).save()
        Title(name = "Vacation", media_type = MediaType.PERSONAL.name,
            sort_name = "vacation").save()

        val resp = service.homeFeed(adminCtx())
        val features = readJsonObject(resp).getAsJsonObject("features")
        assertTrue(features.get("has_books").asBoolean)
        assertTrue(features.get("has_music").asBoolean)
        assertTrue(features.get("has_personal_videos").asBoolean)
    }

    @Test
    fun `homeFeed counts open ProblemReports for admins`() {
        val admin = getOrCreateUser("admin", level = 2)
        ProblemReport(
            user_id = admin.id!!, status = ReportStatus.OPEN.name,
            description = "bug 1", created_at = LocalDateTime.now(),
        ).save()
        ProblemReport(
            user_id = admin.id!!, status = ReportStatus.RESOLVED.name,
            description = "fixed", created_at = LocalDateTime.now(),
        ).save()

        val resp = service.homeFeed(adminCtx())
        val features = readJsonObject(resp).getAsJsonObject("features")
        assertEquals(1, features.get("open_reports_count").asInt,
            "only OPEN reports counted")
    }

    // ---------------------- /api/v2/catalog/features ----------------------

    @Test
    fun `features returns 401 unauthenticated`() {
        val resp = service.features(ctxFor("/api/v2/catalog/features", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `features returns the same shape as homeFeed but without carousels`() {
        val resp = service.features(adminCtx())
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        // No carousel keys — features endpoint is just the FeatureService nav
        // gates.
        assertFalse(body.has("continue_watching"))
        assertFalse(body.has("missing_seasons"))
        // But the feature flags are all there.
        for (key in listOf(
            "has_books", "has_music", "has_personal_videos", "is_admin",
            "unmatched_count", "data_quality_count",
        )) {
            assertTrue(body.has(key), "missing flag '$key'")
        }
    }

    @Test
    fun `features surfaces enabled cameras and live-tv tuners`() {
        Camera(go2rtc_name = "front", enabled = true).save()
        Camera(go2rtc_name = "old", enabled = false).save()
        // Note: LiveTvTuner intentionally not seeded — has_live_tv stays false.

        val resp = service.features(adminCtx())
        val body = readJsonObject(resp)
        assertTrue(body.get("has_cameras").asBoolean)
        assertFalse(body.get("has_live_tv").asBoolean)
    }

    @Test
    fun `features unmatched_audio_count surfaces UNMATCHED audio rows for admins`() {
        UnmatchedAudio(file_path = "/nas/audio/o1.flac", file_name = "o1.flac",
            media_format = MediaFormat.AUDIO_FLAC.name,
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).save()
        UnmatchedAudio(file_path = "/nas/audio/done.flac", file_name = "done.flac",
            media_format = MediaFormat.AUDIO_FLAC.name,
            match_status = UnmatchedAudioStatus.LINKED.name,
            discovered_at = LocalDateTime.now()).save()

        val resp = service.features(adminCtx())
        val body = readJsonObject(resp)
        assertEquals(1, body.get("unmatched_audio_count").asInt)
    }

    // ---------------------- /api/v2/playback-progress/{id} ----------------------

    @Test
    fun `clearProgress returns 401 for unauthenticated requests`() {
        val resp = service.clearProgress(
            ctxFor("/api/v2/playback-progress/1", method = HttpMethod.DELETE, user = null),
            transcodeId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `clearProgress deletes only the calling user's row for the target transcode`() {
        val admin = getOrCreateUser("admin", level = 2)
        val other = AppUser(username = "other", display_name = "other",
            password_hash = "x", access_level = 1,
            created_at = LocalDateTime.now(), updated_at = LocalDateTime.now()).apply { save() }
        val title = Title(name = "Movie", media_type = MediaType.MOVIE.name,
            sort_name = "movie").apply { save() }
        val tc = Transcode(title_id = title.id!!,
            file_path = "/nas/m.mkv").apply { save() }

        // Two PlaybackProgress rows: one for the calling admin, one for someone else.
        PlaybackProgress(user_id = admin.id!!, transcode_id = tc.id!!,
            position_seconds = 100.0, duration_seconds = 1000.0,
            updated_at = LocalDateTime.now()).save()
        PlaybackProgress(user_id = other.id!!, transcode_id = tc.id!!,
            position_seconds = 200.0, duration_seconds = 1000.0,
            updated_at = LocalDateTime.now()).save()

        val resp = service.clearProgress(adminCtx(), transcodeId = tc.id!!)
        assertEquals(HttpStatus.OK, statusOf(resp))

        val remaining = PlaybackProgress.findAll()
        assertEquals(1, remaining.size, "the other user's progress untouched")
        assertEquals(other.id, remaining.single().user_id)
    }

    // ---------------------- /api/v2/catalog/dismiss-missing-seasons/{id} ----------------------

    @Test
    fun `dismissMissingSeasons returns 401 unauthenticated`() {
        val resp = service.dismissMissingSeasons(
            ctxFor("/api/v2/catalog/dismiss-missing-seasons/1",
                method = HttpMethod.POST, user = null),
            titleId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `dismissMissingSeasons returns OK with ok=true on the empty path`() {
        // No seasons or dismissed-notification rows exist; the dismiss
        // call is a no-op but the endpoint should still 200.
        val title = Title(name = "Show", media_type = MediaType.TV.name,
            sort_name = "show").apply { save() }
        val resp = service.dismissMissingSeasons(adminCtx(), titleId = title.id!!)
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(true, readJsonObject(resp).get("ok").asBoolean)
    }

    // ---------------------- /api/v2/pair/info ----------------------

    @Test
    fun `pairInfo returns 401 unauthenticated`() {
        val resp = service.pairInfo(
            ctxFor("/api/v2/pair/info", user = null), code = "ABCD")
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `pairInfo with a blank code returns 400 with an error payload`() {
        // Default annotation gives an empty string when the param is
        // missing entirely; production validates and rejects blanks.
        val resp = service.pairInfo(adminCtx(), code = "")
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
        assertEquals("code required", readJsonObject(resp).get("error").asString)
    }

    @Test
    fun `pairInfo with an unknown code returns OK with status='expired'`() {
        // PairingService.checkStatus returns null when the code is
        // not in the in-memory pairing table; the endpoint surfaces
        // that as `{"status": "expired"}` to the device-side caller.
        val resp = service.pairInfo(adminCtx(), code = "NOPE00")
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals("expired", readJsonObject(resp).get("status").asString)
    }
}
