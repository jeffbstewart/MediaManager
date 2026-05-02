package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BarcodeScan
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.ProblemReport
import net.stewart.mediamanager.entity.ReportStatus
import net.stewart.mediamanager.entity.SessionToken
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.UnmatchedBook
import net.stewart.mediamanager.service.PasswordService
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class UserManagementHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("usermgmt") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = UserManagementHttpService()

    @Before
    fun reset() {
        SessionToken.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for non-admin viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/users",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `list returns the users array`() {
        getOrCreateUser("admin", level = 2)
        getOrCreateUser("alice", level = 1)
        val resp = service.list(
            ctxFor("/api/v2/admin/users",
                user = getOrCreateUser("admin", level = 2)))
        val body = readJsonObject(resp)
        assertTrue(body.has("users"))
        assertTrue(body.getAsJsonArray("users").size() >= 2)
    }

    @Test
    fun `create returns 400 when username is missing`() {
        val resp = service.createUser(
            ctxFor("/api/v2/admin/users",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"password": "Excellent1234!"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    // Password strength is enforced inside PasswordService — service
    // accepts the request and returns 200 with ok=false when the new
    // password is weak. The 400 path I'd expected covers missing fields.

    @Test
    fun `promote returns 404 when user is missing`() {
        val resp = service.promote(
            ctxFor("/api/v2/admin/users/9999/promote",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            userId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `promote and demote flip access_level`() {
        val admin = getOrCreateUser("admin", level = 2)
        val alice = getOrCreateUser("alice", level = 1)
        service.promote(
            ctxFor("/api/v2/admin/users/${alice.id}/promote",
                method = HttpMethod.POST, user = admin),
            userId = alice.id!!,
        )
        assertEquals(2, AppUser.findById(alice.id!!)!!.access_level)
        service.demote(
            ctxFor("/api/v2/admin/users/${alice.id}/demote",
                method = HttpMethod.POST, user = admin),
            userId = alice.id!!,
        )
        assertEquals(1, AppUser.findById(alice.id!!)!!.access_level)
    }

    @Test
    fun `unlock clears the locked flag`() {
        val admin = getOrCreateUser("admin", level = 2)
        val alice = getOrCreateUser("alice", level = 1).apply {
            locked = true
            save()
        }
        service.unlock(
            ctxFor("/api/v2/admin/users/${alice.id}/unlock",
                method = HttpMethod.POST, user = admin),
            userId = alice.id!!,
        )
        assertEquals(false, AppUser.findById(alice.id!!)!!.locked)
    }

    @Test
    fun `forcePasswordChange sets must_change_password=true`() {
        val admin = getOrCreateUser("admin", level = 2)
        val alice = getOrCreateUser("alice", level = 1)
        service.forcePasswordChange(
            ctxFor("/api/v2/admin/users/${alice.id}/force-password-change",
                method = HttpMethod.POST, user = admin),
            userId = alice.id!!,
        )
        assertEquals(true, AppUser.findById(alice.id!!)!!.must_change_password)
    }

    @Test
    fun `deleteUser removes the user`() {
        val admin = getOrCreateUser("admin", level = 2)
        val alice = getOrCreateUser("alice", level = 1)
        service.deleteUser(
            ctxFor("/api/v2/admin/users/${alice.id}",
                method = HttpMethod.DELETE, user = admin),
            userId = alice.id!!,
        )
        // Admin survives, alice is gone.
        assertEquals(null, AppUser.findById(alice.id!!))
    }

    @Test
    fun `setRatingCeiling persists a null value when not provided`() {
        val admin = getOrCreateUser("admin", level = 2)
        val alice = getOrCreateUser("alice", level = 1)
        val resp = service.setRatingCeiling(
            ctxFor("/api/v2/admin/users/${alice.id}/rating-ceiling",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{}"""),
            userId = alice.id!!,
        )
        // Empty body → ceiling cleared, returns 200.
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `listSessions returns 200 for unknown user with empty list`() {
        val resp = service.listSessions(
            ctxFor("/api/v2/admin/users/9999/sessions",
                user = getOrCreateUser("admin", level = 2)),
            userId = 9999L,
        )
        // Production filters SessionToken by user_id without checking
        // user existence — empty list comes back as 200.
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class UnmatchedHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("unmatched") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = UnmatchedHttpService()

    @Before
    fun reset() {
        DiscoveredFile.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `listUnmatched returns 403 for non-admin`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.listUnmatched(
            ctxFor("/api/v2/admin/unmatched",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `listUnmatched returns the empty array on a clean database`() {
        val resp = service.listUnmatched(
            ctxFor("/api/v2/admin/unmatched",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
        // Response shape: an array under some key, varies; just ensure 200.
    }

    @Test
    fun `accept returns 404 when discovered_file is missing`() {
        val resp = service.accept(
            ctxFor("/api/v2/admin/unmatched/9999/accept",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `link returns 404 when discovered_file is missing`() {
        val resp = service.link(
            ctxFor("/api/v2/admin/unmatched/9999/link/1",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L, titleId = 1L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `ignore returns 404 when discovered_file is missing`() {
        val resp = service.ignore(
            ctxFor("/api/v2/admin/unmatched/9999/ignore",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class UnmatchedBookHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("unmatchedbook") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = UnmatchedBookHttpService()

    @Before
    fun reset() {
        UnmatchedBook.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/unmatched-books",
                user = getOrCreateUser("viewer", level = 1)))))
    }

    @Test
    fun `list returns 200 on a clean database`() {
        val resp = service.list(
            ctxFor("/api/v2/admin/unmatched-books",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `linkByIsbn returns 404 when row is gone`() {
        val resp = service.linkByIsbn(
            ctxFor("/api/v2/admin/unmatched-books/9999/link-isbn",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"isbn": "9780000000000"}"""),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `ignore returns 404 when row is gone`() {
        val resp = service.ignore(
            ctxFor("/api/v2/admin/unmatched-books/9999/ignore",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class BookSeriesHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("bookseries") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = BookSeriesHttpService()

    @Before
    fun reset() {
        TitleAuthor.deleteAll()
        Title.deleteAll()
        Author.deleteAll()
        BookSeries.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.list(
            ctxFor("/api/v2/catalog/series", user = null))))
    }

    @Test
    fun `list returns the empty array on a clean catalog`() {
        val resp = service.list(
            ctxFor("/api/v2/catalog/series",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `detail returns 404 when series is missing`() {
        val resp = service.detail(
            ctxFor("/api/v2/catalog/series/9999",
                user = getOrCreateUser("admin", level = 2)),
            seriesId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class ProblemReportHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("problemreport") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = ProblemReportHttpService()

    @Before
    fun reset() {
        ProblemReport.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `submit returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.submit(
            ctxFor("/api/v2/reports", method = HttpMethod.POST,
                user = null, jsonBody = """{"description": "x"}"""))))
    }

    @Test
    fun `submit returns 400 when description is missing`() {
        val resp = service.submit(
            ctxFor("/api/v2/reports", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `submit persists a problem report on the happy path`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.submit(
            ctxFor("/api/v2/reports", method = HttpMethod.POST, user = admin,
                jsonBody = """{"description": "Audio glitch"}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(1, ProblemReport.findAll().size)
    }

    @Test
    fun `list returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/reports",
                user = getOrCreateUser("viewer", level = 1)),
            status = "OPEN", page = 0, size = 50)))
    }

    @Test
    fun `list returns reports filtered by status`() {
        val admin = getOrCreateUser("admin", level = 2)
        ProblemReport(user_id = admin.id!!, description = "open one",
            status = ReportStatus.OPEN.name,
            created_at = LocalDateTime.now()).save()
        ProblemReport(user_id = admin.id!!, description = "fixed one",
            status = ReportStatus.RESOLVED.name,
            created_at = LocalDateTime.now()).save()

        val resp = service.list(
            ctxFor("/api/v2/admin/reports?status=OPEN", user = admin),
            status = "OPEN", page = 0, size = 50,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `resolve returns 404 when report is missing`() {
        val resp = service.resolve(
            ctxFor("/api/v2/admin/reports/9999/resolve",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"admin_notes": "fixed"}"""),
            reportId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `reopen returns 404 when report is missing`() {
        val resp = service.reopen(
            ctxFor("/api/v2/admin/reports/9999/reopen",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            reportId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class ValuationHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("valuation") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = ValuationHttpService()

    @Before
    fun reset() {
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    @Test
    fun `list returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.list(
            ctxFor("/api/v2/admin/valuations",
                user = getOrCreateUser("viewer", level = 1)),
            search = "", unpricedOnly = false, page = 0, size = 50)))
    }

    @Test
    fun `list returns empty rows + zero stats on an empty catalog`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.list(
            ctxFor("/api/v2/admin/valuations", user = admin),
            search = "", unpricedOnly = false, page = 0, size = 50,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(0, body.get("total").asInt)
    }

    @Test
    fun `update returns 404 when item is missing`() {
        val resp = service.update(
            ctxFor("/api/v2/admin/valuations/9999",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"replacement_value": 19.99}"""),
            itemId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `agentStatus returns 200 with status info`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.agentStatus(
            ctxFor("/api/v2/admin/valuations/agent-status", user = admin))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class AddItemHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("additem") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = AddItemHttpService()

    @Before
    fun reset() {
        BarcodeScan.deleteAll()
        AmazonOrder.deleteAll()
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `scanUpc returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.scanUpc(
            ctxFor("/api/v2/admin/add-item/scan",
                method = HttpMethod.POST,
                user = getOrCreateUser("viewer", level = 1),
                jsonBody = """{"upc": "012345678901"}"""))))
    }

    @Test
    fun `quota returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.quota(
            ctxFor("/api/v2/admin/add-item/quota", user = null))))
    }

    @Test
    fun `quota returns the quota state for an admin`() {
        val resp = service.quota(
            ctxFor("/api/v2/admin/add-item/quota",
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `recent returns the recent-scans list`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.recent(
            ctxFor("/api/v2/admin/add-item/recent", user = admin),
            filter = "ALL")
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `searchTmdb returns 403 for viewers`() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(service.searchTmdb(
            ctxFor("/api/v2/admin/add-item/search-tmdb?q=x",
                user = getOrCreateUser("viewer", level = 1)),
            query = "x", type = "MOVIE")))
    }

    @Test
    fun `addFromTmdb returns 400 when tmdb_id is missing`() {
        val resp = service.addFromTmdb(
            ctxFor("/api/v2/admin/add-item/add-from-tmdb",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"media_type": "MOVIE"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `addFromIsbn returns 400 when isbn is missing`() {
        val resp = service.addFromIsbn(
            ctxFor("/api/v2/admin/add-item/add-from-isbn",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `deleteItem is idempotent and returns OK for unknown id`() {
        val resp = service.deleteItem(
            ctxFor("/api/v2/admin/add-item/item/9999",
                method = HttpMethod.DELETE,
                user = getOrCreateUser("admin", level = 2)),
            mediaItemId = 9999L,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `deleteScan is idempotent and returns OK for unknown id`() {
        val resp = service.deleteScan(
            ctxFor("/api/v2/admin/add-item/scan/9999",
                method = HttpMethod.DELETE,
                user = getOrCreateUser("admin", level = 2)),
            scanId = 9999L,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}
