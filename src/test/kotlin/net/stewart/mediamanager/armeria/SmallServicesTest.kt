package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.PlaybackProgress
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleFamilyMember
import net.stewart.mediamanager.entity.Transcode
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bundle of lean tests for several small armeria HTTP services. Each
 * inner class has its own DB schema; the shared [ArmeriaTestBase]
 * harness handles the rest.
 */
internal class HealthHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("health") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    @Test
    fun `health returns 200 when DB is reachable`() {
        val resp = HealthHttpService().health()
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertTrue(readBody(resp).contains("UP"))
    }
}

internal class CameraListHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("cameralist") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = CameraListHttpService()

    @Before
    fun reset() {
        Camera.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `listCameras returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            statusOf(service.listCameras(ctxFor("/api/v2/catalog/cameras", user = null))))
    }

    @Test
    fun `listCameras returns enabled cameras only, sorted by display_order`() {
        val admin = getOrCreateUser("admin", level = 2)
        Camera(go2rtc_name = "front", name = "Front Door",
            enabled = true, display_order = 2).save()
        Camera(go2rtc_name = "back", name = "Back Yard",
            enabled = true, display_order = 1).save()
        Camera(go2rtc_name = "old", name = "Disabled",
            enabled = false, display_order = 0).save()

        val resp = service.listCameras(ctxFor("/api/v2/catalog/cameras", user = admin))
        val body = readJsonObject(resp)
        assertEquals(2, body.get("total").asInt)
        assertEquals("Back Yard",
            body.getAsJsonArray("cameras")[0].asJsonObject.get("name").asString)
    }
}

internal class FamilyMemberHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("familymember") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = FamilyMemberHttpService()

    @Before
    fun reset() {
        TitleFamilyMember.deleteAll()
        FamilyMember.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for non-admin viewers`() {
        val resp = service.list(
            ctxFor("/api/v2/admin/family-members",
                user = getOrCreateUser("viewer", level = 1)))
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `list returns the members with video_count`() {
        val admin = getOrCreateUser("admin", level = 2)
        FamilyMember(name = "Alice").save()
        val resp = service.list(
            ctxFor("/api/v2/admin/family-members", user = admin))
        val body = readJsonObject(resp)
        assertEquals(1, body.getAsJsonArray("members").size())
    }

    @Test
    fun `create returns 400 when name is blank`() {
        val resp = service.create(
            ctxFor("/api/v2/admin/family-members",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"name": "  "}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `create persists a new family member on the happy path`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.create(
            ctxFor("/api/v2/admin/family-members",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"name": "Alice", "notes": "Daughter"}""")
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val saved = FamilyMember.findAll().single()
        assertEquals("Alice", saved.name)
        assertEquals("Daughter", saved.notes)
    }

    @Test
    fun `create returns 400 on duplicate name`() {
        val admin = getOrCreateUser("admin", level = 2)
        FamilyMember(name = "Alice").save()
        val resp = service.create(
            ctxFor("/api/v2/admin/family-members",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"name": "Alice"}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `update returns 404 when id is missing`() {
        val resp = service.update(
            ctxFor("/api/v2/admin/family-members/9999",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"name": "X"}"""),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `update persists the new name`() {
        val admin = getOrCreateUser("admin", level = 2)
        val member = FamilyMember(name = "Alice").apply { save() }
        service.update(
            ctxFor("/api/v2/admin/family-members/${member.id}",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"name": "Alicia"}"""),
            id = member.id!!,
        )
        assertEquals("Alicia", FamilyMember.findById(member.id!!)!!.name)
    }

    @Test
    fun `delete removes the family member`() {
        val admin = getOrCreateUser("admin", level = 2)
        val member = FamilyMember(name = "Alice").apply { save() }
        service.delete(
            ctxFor("/api/v2/admin/family-members/${member.id}",
                method = HttpMethod.DELETE, user = admin),
            id = member.id!!,
        )
        assertEquals(0, FamilyMember.findAll().size)
    }
}

internal class PlaybackProgressHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("playback") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = PlaybackProgressHttpService()

    @Before
    fun reset() {
        PlaybackProgress.deleteAll()
        Transcode.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `record returns 401 unauthenticated`() {
        val resp = service.record(
            ctxFor("/playback-progress/1", method = HttpMethod.POST,
                user = null, jsonBody = """{"position": 10}"""),
            transcodeId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `record returns 400 when position is missing`() {
        val resp = service.record(
            ctxFor("/playback-progress/1", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""),
            transcodeId = 1L,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `record persists progress on the happy path`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = Title(name = "M", media_type = MediaType.MOVIE.name,
            sort_name = "m").apply { save() }
        val tc = Transcode(title_id = title.id!!,
            file_path = "/nas/m.mp4").apply { save() }
        val resp = service.record(
            ctxFor("/playback-progress/${tc.id}",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"position": 120, "duration": 5400}"""),
            transcodeId = tc.id!!,
        )
        assertEquals(HttpStatus.NO_CONTENT, statusOf(resp))
        val progress = PlaybackProgress.findAll().single()
        assertEquals(120.0, progress.position_seconds)
        assertEquals(5400.0, progress.duration_seconds)
    }

    @Test
    fun `get returns 404 when no progress exists`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.get(
            ctxFor("/playback-progress/9999", user = admin),
            transcodeId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `delete is idempotent and returns 204 even when no row exists`() {
        val resp = service.delete(
            ctxFor("/playback-progress/9999",
                method = HttpMethod.DELETE,
                user = getOrCreateUser("admin", level = 2)),
            transcodeId = 9999L,
        )
        assertEquals(HttpStatus.NO_CONTENT, statusOf(resp))
    }
}

internal class ReadingProgressHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("reading") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = ReadingProgressHttpService()

    @Before
    fun reset() {
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `get returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            statusOf(service.get(ctxFor("/api/v2/reading-progress/1", user = null),
                mediaItemId = 1L)))
    }

    @Test
    fun `get returns 403 when media item is missing`() {
        // canSee returns false when MediaItem.findById is null.
        val resp = service.get(
            ctxFor("/api/v2/reading-progress/9999",
                user = getOrCreateUser("admin", level = 2)),
            mediaItemId = 9999L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `get returns the empty-shape when item exists but no progress saved`() {
        val admin = getOrCreateUser("admin", level = 2)
        val item = MediaItem(media_format = MediaFormat.EBOOK_EPUB.name).apply { save() }
        val resp = service.get(
            ctxFor("/api/v2/reading-progress/${item.id}", user = admin),
            mediaItemId = item.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(0.0, body.get("percent").asDouble)
    }

    @Test
    fun `save returns 400 when cfi is missing`() {
        val admin = getOrCreateUser("admin", level = 2)
        val item = MediaItem(media_format = MediaFormat.EBOOK_EPUB.name).apply { save() }
        val resp = service.save(
            ctxFor("/api/v2/reading-progress/${item.id}",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"percent": 0.5}"""),
            mediaItemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `save persists progress and returns ok`() {
        val admin = getOrCreateUser("admin", level = 2)
        val item = MediaItem(media_format = MediaFormat.EBOOK_EPUB.name).apply { save() }
        val resp = service.save(
            ctxFor("/api/v2/reading-progress/${item.id}",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"cfi": "epubcfi(/6/4!/4/2/2)", "percent": 0.42}"""),
            mediaItemId = item.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(true, readJsonObject(resp).get("ok").asBoolean)
    }

    @Test
    fun `delete returns 200 even when no progress exists`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.delete(
            ctxFor("/api/v2/reading-progress/1",
                method = HttpMethod.DELETE, user = admin),
            mediaItemId = 1L,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class AdvancedSearchHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("advsearch") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = AdvancedSearchHttpService()

    @Before
    fun reset() {
        AppUser.deleteAll()
    }

    @Test
    fun `listPresets returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            statusOf(service.listPresets(ctxFor("/api/v2/search/presets", user = null))))
    }

    @Test
    fun `listPresets returns the preset list`() {
        val resp = service.listPresets(
            ctxFor("/api/v2/search/presets",
                user = getOrCreateUser("admin", level = 2)))
        val body = readJsonObject(resp)
        assertTrue(body.has("presets"))
        // AdvancedSearchPresets.ALL is non-empty per docs/MUSIC.md.
        assertTrue(body.getAsJsonArray("presets").size() > 0)
    }

    @Test
    fun `searchTracks returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            statusOf(service.searchTracks(
                ctxFor("/api/v2/search/tracks", user = null),
                q = "", bpmMin = 0, bpmMax = 0,
                timeSignature = "", limit = 200)))
    }

    @Test
    fun `searchTracks returns empty tracks when no filters and no data`() {
        val resp = service.searchTracks(
            ctxFor("/api/v2/search/tracks",
                user = getOrCreateUser("admin", level = 2)),
            q = "", bpmMin = 0, bpmMax = 0,
            timeSignature = "", limit = 200,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(0, readJsonObject(resp).getAsJsonArray("tracks").size())
    }
}

internal class BacklogHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("backlog") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = BacklogHttpService()

    @Before
    fun reset() {
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.list(
            ctxFor("/api/v2/admin/transcode-backlog", user = null),
            search = "", page = 0, size = 50)))
    }

    @Test
    fun `list returns 403 for non-admin viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/transcode-backlog",
                user = getOrCreateUser("viewer", level = 1)),
            search = "", page = 0, size = 50)))
    }

    @Test
    fun `list returns empty rows for an empty catalog`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.list(
            ctxFor("/api/v2/admin/transcode-backlog", user = admin),
            search = "", page = 0, size = 50,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(0, body.get("total").asInt)
    }
}
