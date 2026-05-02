package net.stewart.mediamanager.armeria

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.Server
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.grpc.ArmeriaServer
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration check that the three production HTTP decorators —
 * [CspDecorator], [ArmeriaAuthDecorator], and [AccessLogDecorator] —
 * are wired into the live server. The unit tests in [DecoratorTest]
 * already prove each decorator's behaviour in isolation; this test
 * exists so a future refactor that drops a `.decorator(...)` call from
 * [ArmeriaServer] gets caught at test time, not at deploy time.
 *
 * Stands up a real Armeria server via the same `registerGlobalDecorators`
 * + `registerHttpServices` calls production uses, then sends real HTTP
 * requests through the running pipeline.
 */
class DecoratorWiringTest {

    companion object {
        private lateinit var dataSource: HikariDataSource
        private lateinit var server: Server
        private var port: Int = 0

        @BeforeClass @JvmStatic
        fun setupServer() {
            // ArmeriaAuthDecorator calls AuthService.hasUsers() → DB.
            // The annotated services likewise need migrations applied.
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:decwiring;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()

            val sb = Server.builder().http(0)
            // Same wiring production uses — if a decorator gets removed
            // from one of these methods, this test fails.
            ArmeriaServer.registerGlobalDecorators(sb)
            ArmeriaServer.registerHttpServices(sb)
            server = sb.build()
            server.start().join()
            port = server.activeLocalPort()
        }

        @AfterClass @JvmStatic
        fun teardownServer() {
            server.stop().join()
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    private fun client(): WebClient = WebClient.of("http://127.0.0.1:$port")

    // ---------------------- CspDecorator ----------------------

    @Test
    fun `CspDecorator is wired - response carries Content-Security-Policy`() {
        // Hit any endpoint registered by registerHttpServices. /api/v2/auth/discover
        // is unauthenticated by design, so we don't have to scaffold credentials.
        val resp = client().get("/api/v2/auth/discover").aggregate().join()
        assertEquals(HttpStatus.OK, resp.status())
        assertNotNull(resp.headers().get("content-security-policy"),
            "CspDecorator must add the CSP header — production wiring is broken")
        // Companion security header — same decorator emits both, so its
        // presence pins down "the wiring fired."
        assertNotNull(resp.headers().get("strict-transport-security"))
        // Server header replaced with the generic "MediaManager" banner.
        assertEquals("MediaManager", resp.headers().get("server"))
    }

    // ---------------------- ArmeriaAuthDecorator ----------------------

    @Test
    fun `ArmeriaAuthDecorator is wired - authenticated endpoint returns 403 pre-setup`() {
        // No users exist in the freshly-migrated schema, so AuthDecorator's
        // first check (`AuthService.hasUsers()`) returns false and the
        // decorator returns 403. The fact that we get 403 instead of the
        // service's own response shape proves AuthDecorator is on the chain.
        val resp = client().get("/api/v2/profile").aggregate().join()
        assertEquals(HttpStatus.FORBIDDEN, resp.status(),
            "ArmeriaAuthDecorator must short-circuit pre-setup with 403")
    }

    // ---------------------- AccessLogDecorator ----------------------

    @Test
    fun `AccessLogDecorator is wired - request emits a log line to the http access logger`() {
        // The project's BufferingLogger (logging-common) writes every
        // SLF4J record to stderr. We capture stderr around a single
        // request and assert the log line contains the path the
        // decorator's lambda is supposed to format.
        val original = System.err
        val capture = ByteArrayOutputStream()
        System.setErr(PrintStream(capture, true, Charsets.UTF_8))
        try {
            // Any non-skipped path. /api/v2/auth/discover is unauthenticated
            // so it returns 200; AccessLogDecorator's skip-list excludes
            // /health and /metrics on 200 GET only — /auth/discover passes.
            val resp = client().get("/api/v2/auth/discover").aggregate().join()
            assertEquals(HttpStatus.OK, resp.status())

            // The log fires on whenComplete(), which only resolves once
            // the response is fully written + cleaned up. Drain briefly so
            // the callback runs on Armeria's executor before we assert.
            // 200ms is generous; the callback is synchronous post-write.
            Thread.sleep(200)
        } finally {
            System.setErr(original)
        }

        val captured = capture.toString(Charsets.UTF_8)
        // The decorator formats lines like:
        //   `GET /api/v2/auth/discover 200 5ms 123B 127.0.0.1 user=-`
        // We don't pin the elapsed/size values (volatile); just confirm
        // the path + status appeared, which only happens if the
        // whenComplete lambda inside AccessLogDecorator ran.
        assertTrue("/api/v2/auth/discover" in captured,
            "expected access-log line for /api/v2/auth/discover; got stderr:\n$captured")
        assertTrue("200" in captured,
            "access-log line should carry response status 200; got:\n$captured")
    }
}
