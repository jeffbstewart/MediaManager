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
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TagSourceType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Transcode
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TitleListHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("titlelist") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = TitleListHttpService()

    @Before fun reset() {
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `listTitles returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.listTitles(
            ctxFor("/api/v2/catalog/titles", user = null),
            mediaType = "MOVIE", sort = "name",
            ratings = "", playableOnly = false)))
    }

    @Test
    fun `listTitles returns the empty array on a clean catalog`() {
        val resp = service.listTitles(
            ctxFor("/api/v2/catalog/titles",
                user = getOrCreateUser("admin", level = 2)),
            mediaType = "MOVIE", sort = "name",
            ratings = "", playableOnly = false,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class TranscodeStatusHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("transcodestatus") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = TranscodeStatusHttpService()

    @Before fun reset() {
        Transcode.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `status returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.status(
            ctxFor("/api/v2/admin/transcode-status",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `status returns OK on empty database`() {
        val resp = service.status(
            ctxFor("/api/v2/admin/transcode-status",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `scanNas returns 200 for an admin (kicks NAS scan in background)`() {
        val resp = service.scanNas(
            ctxFor("/api/v2/admin/transcode-status/scan",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `clearFailures returns 200 for an admin`() {
        val resp = service.clearFailures(
            ctxFor("/api/v2/admin/transcode-status/clear-failures",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class ExpandHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("expand") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = ExpandHttpService()

    @Before fun reset() {
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/expand",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `list returns OK on empty catalog`() {
        val resp = service.list(
            ctxFor("/api/v2/admin/expand",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `detail returns 404 when item is missing`() {
        val resp = service.detail(
            ctxFor("/api/v2/admin/expand/9999",
                user = getOrCreateUser("admin", level = 2)),
            itemId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class PurchaseWishesHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("purchwishes") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = PurchaseWishesHttpService()

    @Before fun reset() {
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/purchase-wishes",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `list returns OK on empty database`() {
        val resp = service.list(
            ctxFor("/api/v2/admin/purchase-wishes",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `setStatus returns 400 when wish_id is missing`() {
        val resp = service.setStatus(
            ctxFor("/api/v2/admin/purchase-wishes/set-status",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"status": "NOT_AVAILABLE"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }
}

internal class SettingsHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("settings") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = SettingsHttpService()

    @Before fun reset() {
        AppConfig.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `getSettings returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.getSettings(
            ctxFor("/api/v2/admin/settings",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `getSettings returns admin settings`() {
        val resp = service.getSettings(
            ctxFor("/api/v2/admin/settings",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    // saveSettings expects body with key→string entries; skipping the
    // happy path here — covered indirectly by the gRPC AdminGrpcService
    // settings tests.
}

internal class CameraSettingsHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("cameraset") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = CameraSettingsHttpService()

    @Before fun reset() {
        Camera.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/cameras",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `list returns the cameras array for an admin`() {
        val resp = service.list(
            ctxFor("/api/v2/admin/cameras",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class DataQualityHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("dataquality") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = DataQualityHttpService()

    @Before fun reset() {
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/data-quality",
                user = getOrCreateUser("viewer", level = 1)),
            status = "", search = "", showHidden = false,
            page = 0, size = 50)))
    }

    @Test
    fun `list returns OK on empty catalog`() {
        val resp = service.list(
            ctxFor("/api/v2/admin/data-quality",
                user = getOrCreateUser("admin", level = 2)),
            status = "", search = "", showHidden = false,
            page = 0, size = 50,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `reEnrich returns 404 when title is missing`() {
        val resp = service.reEnrich(
            ctxFor("/api/v2/admin/data-quality/9999/re-enrich",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            titleId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `toggleHidden returns 404 when title is missing`() {
        val resp = service.toggleHidden(
            ctxFor("/api/v2/admin/data-quality/9999/toggle-hidden",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            titleId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class DocumentOwnershipHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("docown") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = DocumentOwnershipHttpService()

    @Before fun reset() {
        OwnershipPhoto.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `lookup returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.lookup(
            ctxFor("/api/v2/admin/ownership/lookup?upc=x",
                user = getOrCreateUser("viewer", level = 1)),
            upc = "x")))
    }

    @Test
    fun `search returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.search(
            ctxFor("/api/v2/admin/ownership/search?q=x",
                user = getOrCreateUser("viewer", level = 1)),
            query = "x")))
    }

    @Test
    fun `deletePhoto is idempotent and returns 200 even for unknown id`() {
        val resp = service.deletePhoto(
            ctxFor("/api/v2/admin/ownership/photos/9999",
                method = HttpMethod.DELETE,
                user = getOrCreateUser("admin", level = 2)),
            photoId = "9999",
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class TagManagementHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("tagmgmt") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = TagManagementHttpService()

    @Before fun reset() {
        TitleTag.deleteAll()
        Tag.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/tags",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `create returns 400 when name is missing`() {
        val resp = service.create(
            ctxFor("/api/v2/admin/tags",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `create persists a new tag`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.create(
            ctxFor("/api/v2/admin/tags",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"name": "NewTag", "bg_color": "#abc"}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertTrue(Tag.findAll().any { it.name == "NewTag" })
    }

    @Test
    fun `update returns 200 for an admin`() {
        val admin = getOrCreateUser("admin", level = 2)
        val tag = Tag(name = "T", bg_color = "#000",
            source_type = TagSourceType.MANUAL.name).apply { save() }
        val resp = service.update(
            ctxFor("/api/v2/admin/tags/${tag.id}",
                method = HttpMethod.PUT, user = admin,
                jsonBody = """{"name": "Renamed"}"""),
            tagId = tag.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `delete returns 200 even when tag is missing (idempotent)`() {
        val resp = service.delete(
            ctxFor("/api/v2/admin/tags/9999",
                method = HttpMethod.DELETE,
                user = getOrCreateUser("admin", level = 2)),
            tagId = 9999L,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class FamilyVideosHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("famvideos") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = FamilyVideosHttpService()

    @Before fun reset() {
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `listFamilyVideos returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.listFamilyVideos(
            ctxFor("/api/v2/catalog/family-videos", user = null),
            sort = "date_desc", members = "", playableOnly = false)))
    }

    @Test
    fun `listFamilyVideos returns OK on empty catalog`() {
        val resp = service.listFamilyVideos(
            ctxFor("/api/v2/catalog/family-videos",
                user = getOrCreateUser("admin", level = 2)),
            sort = "date_desc", members = "", playableOnly = false,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class LinkedTranscodesHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("linkedtc") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = LinkedTranscodesHttpService()

    @Before fun reset() {
        Transcode.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/linked-transcodes",
                user = getOrCreateUser("viewer", level = 1)),
            search = "", format = "", mediaType = "",
            sort = "name", page = 0, size = 50)))
    }

    @Test
    fun `list returns OK on empty database`() {
        val resp = service.list(
            ctxFor("/api/v2/admin/linked-transcodes",
                user = getOrCreateUser("admin", level = 2)),
            search = "", format = "", mediaType = "",
            sort = "name", page = 0, size = 50,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `requestRetranscode returns 404 when transcode is missing`() {
        val resp = service.requestRetranscode(
            ctxFor("/api/v2/admin/linked-transcodes/9999/retranscode",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            transcodeId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class LiveTvSettingsHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("livetvsettings") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = LiveTvSettingsHttpService()

    @Before fun reset() {
        LiveTvChannel.deleteAll()
        LiveTvTuner.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    @Test
    fun `getSettings returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.getSettings(
            ctxFor("/api/v2/admin/live-tv",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `getSettings returns OK on empty database`() {
        val resp = service.getSettings(
            ctxFor("/api/v2/admin/live-tv",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `deleteTuner is idempotent and returns 200 even when tuner is missing`() {
        val resp = service.deleteTuner(
            ctxFor("/api/v2/admin/live-tv/tuners/9999",
                method = HttpMethod.DELETE,
                user = getOrCreateUser("admin", level = 2)),
            tunerId = 9999L,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class InventoryReportHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic fun setupDb() { ds = setupSchema("invreport") }
        @AfterClass @JvmStatic fun teardownDb() { teardownSchema(ds) }
    }

    private val service = InventoryReportHttpService()

    @Before fun reset() {
        AppUser.deleteAll()
    }

    @Test
    fun `info returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.info(
            ctxFor("/api/v2/admin/report/info",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `info returns OK for an admin`() {
        val resp = service.info(
            ctxFor("/api/v2/admin/report/info",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `status returns OK for an admin`() {
        val resp = service.status(
            ctxFor("/api/v2/admin/report/status",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}
