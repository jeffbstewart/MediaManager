package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.RecommendedArtist
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.LegalRequirements
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class RecommendationHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("recommend") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = RecommendationHttpService()

    @Before
    fun reset() {
        RecommendedArtist.deleteAll()
        Artist.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.list(
            ctxFor("/api/v2/recommendations/artists", user = null),
            limit = 30)))
    }

    @Test
    fun `list returns empty for a user with no recommendations`() {
        val resp = service.list(
            ctxFor("/api/v2/recommendations/artists",
                user = getOrCreateUser("admin", level = 2)),
            limit = 30,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(0, readJsonObject(resp).getAsJsonArray("artists").size())
    }

    @Test
    fun `dismiss returns 400 when suggested_artist_mbid is missing`() {
        val resp = service.dismiss(
            ctxFor("/api/v2/recommendations/dismiss",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `dismiss returns 404 when no matching recommendation exists`() {
        val resp = service.dismiss(
            ctxFor("/api/v2/recommendations/dismiss",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"suggested_artist_mbid": "${java.util.UUID.randomUUID()}"}"""))
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `refresh queues a background job and returns ok`() {
        val resp = service.refresh(
            ctxFor("/api/v2/recommendations/refresh",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(true, readJsonObject(resp).get("ok").asBoolean)
    }
}

internal class RadioHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("radio") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = RadioHttpService()

    @Before fun reset() { AppUser.deleteAll() }

    @Test
    fun `start returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(service.start(
            ctxFor("/api/v2/radio/start", method = HttpMethod.POST,
                user = null, jsonBody = """{}"""))))
    }

    @Test
    fun `start returns 400 when seed_type is missing`() {
        val resp = service.start(
            ctxFor("/api/v2/radio/start", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"seed_id": 1}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `start returns 400 when seed_id is missing`() {
        val resp = service.start(
            ctxFor("/api/v2/radio/start", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"seed_type": "track"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `start returns 400 for unknown seed_type`() {
        val resp = service.start(
            ctxFor("/api/v2/radio/start", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"seed_type": "podcast", "seed_id": 1}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `next returns 400 when radio_seed_id is missing`() {
        val resp = service.next(
            ctxFor("/api/v2/radio/next", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `next returns 404 when radio_seed_id is unknown`() {
        val resp = service.next(
            ctxFor("/api/v2/radio/next", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"radio_seed_id": "nonexistent-${java.util.UUID.randomUUID()}"}"""))
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }
}

internal class LiveTvListHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("livetvlist") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = LiveTvListHttpService()

    @Before
    fun reset() {
        LiveTvChannel.deleteAll()
        LiveTvTuner.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    @Test
    fun `listChannels returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            statusOf(service.listChannels(ctxFor("/api/v2/catalog/live-tv/channels",
                user = null))))
    }

    @Test
    fun `listChannels returns enabled channels with reception_quality at or above ceiling`() {
        val admin = getOrCreateUser("admin", level = 2).apply {
            live_tv_min_quality = 3
            save()
        }
        val tuner = LiveTvTuner(name = "T1",
            ip_address = "192.0.2.${(System.nanoTime() % 200 + 1).toInt()}",
            enabled = true).apply { save() }
        LiveTvChannel(tuner_id = tuner.id!!,
            guide_number = "5.1", guide_name = "Good",
            reception_quality = 4, enabled = true,
            display_order = 0).save()
        LiveTvChannel(tuner_id = tuner.id!!,
            guide_number = "9.1", guide_name = "Bad",
            reception_quality = 1, enabled = true,
            display_order = 1).save()

        val resp = service.listChannels(
            ctxFor("/api/v2/catalog/live-tv/channels", user = admin))
        // Only the high-reception channel survives the per-user min_quality.
        val channels = readJsonObject(resp).getAsJsonArray("channels")
        assertEquals(1, channels.size())
        assertEquals("Good",
            channels[0].asJsonObject.get("guide_name").asString)
    }
}

internal class PairingHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("pairing") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = PairingHttpService()

    @Test
    fun `start returns 200 with a fresh pairing code (unauthenticated)`() {
        val resp = service.start(ctxFor("/api/pair/start",
            method = HttpMethod.POST, user = null,
            jsonBody = """{"device_name": "Roku Living Room"}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertTrue(body.has("code"))
    }

    @Test
    fun `status returns 400 for an empty code`() {
        val resp = service.status(ctxFor("/api/pair/status?code=", user = null),
            code = "")
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `status returns 404 for an unknown code`() {
        val resp = service.status(ctxFor("/api/pair/status?code=NOPE99", user = null),
            code = "NOPE99")
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `qr returns the QR code bytes for any code`() {
        // QR endpoint generates a code regardless of validity — the bytes
        // come back as image/png and aren't worth decoding in the test.
        val resp = service.qr(ctxFor("/api/pair/qr?code=ABCDEF", user = null),
            code = "ABCDEF")
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}

internal class AmazonImportHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("amazon") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = AmazonImportHttpService()

    @Before
    fun reset() {
        AmazonOrder.deleteAll()
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    @Test
    fun `list returns 403 for non-admin viewers`() {
        val resp = service.list(
            ctxFor("/api/v2/admin/amazon-orders",
                user = getOrCreateUser("viewer", level = 1)),
            search = "", mediaOnly = false, unlinkedOnly = false,
            hideCancelled = true, page = 0, size = 50,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `list returns empty rows + zero stats on an empty catalog`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.list(
            ctxFor("/api/v2/admin/amazon-orders", user = admin),
            search = "", mediaOnly = false, unlinkedOnly = false,
            hideCancelled = true, page = 0, size = 50,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(0, body.get("total").asInt)
    }

    @Test
    fun `searchItems returns 403 for viewers`() {
        val resp = service.searchItems(
            ctxFor("/api/v2/admin/amazon-orders/search-items?q=x",
                user = getOrCreateUser("viewer", level = 1)),
            query = "x",
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `link returns 403 for viewers`() {
        val resp = service.link(
            ctxFor("/api/v2/admin/amazon-orders/1/link/1",
                method = HttpMethod.POST,
                user = getOrCreateUser("viewer", level = 1)),
            orderId = 1L, itemId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `unlink returns 403 for viewers`() {
        val resp = service.unlink(
            ctxFor("/api/v2/admin/amazon-orders/1/unlink",
                method = HttpMethod.POST,
                user = getOrCreateUser("viewer", level = 1)),
            orderId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }
}

internal class CspReportHttpServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("csp") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = CspReportHttpService()

    @Test
    fun `report accepts a legacy csp-report payload`() {
        val resp = service.report(
            ctxFor("/csp-report", method = HttpMethod.POST,
                jsonBody = """{"csp-report": {
                    "document-uri": "https://example.com/x",
                    "violated-directive": "img-src",
                    "blocked-uri": "https://evil.example/img.png"
                }}""")
        )
        assertEquals(HttpStatus.NO_CONTENT, statusOf(resp))
    }

    @Test
    fun `report accepts a modern reports+json array`() {
        val resp = service.report(
            ctxFor("/csp-report", method = HttpMethod.POST,
                jsonBody = """[{
                    "type": "csp-violation",
                    "body": {
                        "documentURL": "https://example.com/x",
                        "effectiveDirective": "script-src",
                        "blockedURL": "inline"
                    }
                }]""",
                extraHeaders = mapOf("content-type" to "application/reports+json"))
        )
        assertEquals(HttpStatus.NO_CONTENT, statusOf(resp))
    }

    @Test
    fun `report tolerates malformed JSON without throwing`() {
        val resp = service.report(
            ctxFor("/csp-report", method = HttpMethod.POST,
                jsonBody = "garbage not json")
        )
        // Parse fails internally → still 204 (no leak of parse state).
        assertEquals(HttpStatus.NO_CONTENT, statusOf(resp))
    }

    @Test
    fun `report returns 400 when body exceeds the size cap`() {
        val tooBig = "x".repeat(10_000)
        val resp = service.report(
            ctxFor("/csp-report", method = HttpMethod.POST, jsonBody = tooBig)
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }
}

internal class LegalRestServiceTest : ArmeriaTestBase() {
    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("legal") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = LegalRestService()

    @Before
    fun reset() {
        AppUser.deleteAll()
        AppConfig.deleteAll()
        LegalRequirements.refresh()
    }

    @Test
    fun `status returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            statusOf(service.status(ctxFor("/api/v2/legal/status", user = null))))
    }

    @Test
    fun `status returns the user's compliance state`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.status(
            ctxFor("/api/v2/legal/status", user = admin))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertTrue(body.has("compliant"))
        assertTrue(body.has("required_terms_of_use_version"))
    }

    @Test
    fun `agree returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            statusOf(service.agree(ctxFor("/api/v2/legal/agree",
                method = HttpMethod.POST, user = null,
                jsonBody = """{}"""))))
    }

    @Test
    fun `agree returns 200 ok=true on the empty-required-versions path`() {
        // No legal config seeded → required versions are 0 → no version
        // mismatch possible. Endpoint just records the agreement.
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.agree(
            ctxFor("/api/v2/legal/agree", method = HttpMethod.POST, user = admin,
                jsonBody = """{}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(true, readJsonObject(resp).get("ok").asBoolean)
    }
}
