package net.stewart.mediamanager.armeria

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.Server
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.SessionToken
import net.stewart.mediamanager.grpc.ArmeriaServer
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PasswordService
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end check of the cookie CSRF gate through a real Armeria server
 * using production wiring. Covers the body-replay path (cookie DELETE with
 * no Content-Type) that a unit test with a synthetic context can't fully
 * prove.
 */
class CookieCsrfWiringTest {

    companion object {
        private lateinit var dataSource: HikariDataSource
        private lateinit var server: Server
        private var port: Int = 0
        private lateinit var cookie: String

        @BeforeClass @JvmStatic
        fun setupServer() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:csrfwiring;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
            AuthService.invalidateHasUsersCache()

            val now = LocalDateTime.now()
            val user = AppUser(
                username = "csrf-viewer", display_name = "csrf-viewer",
                password_hash = PasswordService.hash("Test1234!@#"),
                access_level = 1, created_at = now, updated_at = now,
            ).apply { save() }
            cookie = "${AuthService.COOKIE_NAME}=${AuthService.createSession(user, "test-agent")}"

            val sb = Server.builder().http(0)
            ArmeriaServer.registerGlobalDecorators(sb)
            ArmeriaServer.registerHttpServices(sb)
            server = sb.build()
            server.start().join()
            port = server.activeLocalPort()
        }

        @AfterClass @JvmStatic
        fun teardownServer() {
            server.stop().join()
            SessionToken.deleteAll()
            AppUser.deleteAll()
            AuthService.invalidateHasUsersCache()
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    private val client: WebClient get() = WebClient.of("http://127.0.0.1:$port")
    private val sameOrigin get() = "http://127.0.0.1:$port"

    private fun send(
        method: HttpMethod,
        path: String,
        origin: String? = null,
        contentType: MediaType? = null,
        body: String? = null,
    ): HttpStatus {
        val b = RequestHeaders.builder(method, path).add("cookie", cookie)
        if (origin != null) b.add("origin", origin)
        if (contentType != null) b.contentType(contentType)
        val req = if (body != null) HttpRequest.of(b.build(), com.linecorp.armeria.common.HttpData.ofUtf8(body))
            else HttpRequest.of(b.build())
        return client.execute(req).aggregate().join().status()
    }

    @Test
    fun `same-origin JSON POST is served`() {
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v2/playlists/track-completed",
            origin = sameOrigin, contentType = MediaType.JSON_UTF_8, body = """{"track_id": 1}"""))
    }

    @Test
    fun `cross-origin POST is refused`() {
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST, "/api/v2/playlists/track-completed",
            origin = "http://attacker.example.test", contentType = MediaType.JSON_UTF_8,
            body = """{"track_id": 1}"""))
    }

    @Test
    fun `text-plain POST is refused`() {
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, send(HttpMethod.POST,
            "/api/v2/playlists/track-completed", origin = sameOrigin,
            contentType = MediaType.PLAIN_TEXT_UTF_8, body = """{"track_id": 1}"""))
    }

    @Test
    fun `bodiless cookie DELETE still reaches the handler`() {
        assertEquals(HttpStatus.OK, send(HttpMethod.DELETE, "/api/v2/playlists/1/progress",
            origin = sameOrigin))
    }
}
