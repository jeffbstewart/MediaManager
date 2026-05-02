package net.stewart.mediamanager.armeria

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.PasswordService
import org.flywaydb.core.Flyway
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared scaffolding for Armeria HTTP-service tests.
 *
 * Each subclass owns its own H2 database (named with the test-class
 * counter so parallel runs don't collide) and seeds it with Flyway
 * migrations in a `@BeforeClass`.
 *
 * Tests construct a [ServiceRequestContext] via [ctxFor] / [adminCtx] /
 * [viewerCtx] and call the service method directly — there's no real
 * Armeria server in the loop. The annotated services all read the
 * authenticated user via [ArmeriaAuthDecorator.getUser], which is just
 * `ctx.attr(USER_KEY)`; setting that attribute on a builder-produced
 * context is enough to drive the auth-gate branches without standing
 * up a full Armeria pipeline.
 *
 * Helpers [readJson] / [readJsonObject] / [readJsonArrayOf] decode the
 * `HttpResponse` body the production code returns; the response is
 * collected synchronously via `aggregate().join()`.
 */
abstract class ArmeriaTestBase {

    companion object {
        private val DB_COUNTER = AtomicInteger(0)
        protected val gson = Gson()

        /** Distinct in-memory H2 schema per JVM-class so suites can interleave. */
        @JvmStatic
        protected fun setupSchema(prefix: String): HikariDataSource {
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:${prefix}-${DB_COUNTER.incrementAndGet()};DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(ds)
            Flyway.configure().dataSource(ds).load().migrate()
            return ds
        }

        @JvmStatic
        protected fun teardownSchema(ds: HikariDataSource) {
            JdbiOrm.destroy()
            ds.close()
        }
    }

    // ---------------------- request-context construction ----------------------

    /**
     * Build a [ServiceRequestContext] for [path] (and optional query
     * string) authenticated as [user]. Pass `null` for [user] to model
     * the unauthenticated case — production code returns 401 for those.
     */
    protected fun ctxFor(
        path: String,
        method: HttpMethod = HttpMethod.GET,
        user: AppUser? = null,
        jsonBody: String? = null,
        cookieHeader: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ServiceRequestContext {
        val headersBuilder = RequestHeaders.builder(method, path)
        if (jsonBody != null) {
            headersBuilder.contentType(MediaType.JSON_UTF_8)
        }
        if (cookieHeader != null) {
            headersBuilder.add("cookie", cookieHeader)
        }
        for ((k, v) in extraHeaders) headersBuilder.add(k, v)
        val headers = headersBuilder.build()
        val req = if (jsonBody != null) {
            HttpRequest.of(headers, HttpData.ofUtf8(jsonBody))
        } else {
            HttpRequest.of(headers)
        }
        val ctx = ServiceRequestContext.builder(req).build()
        if (user != null) ctx.setAttr(ArmeriaAuthDecorator.USER_KEY, user)
        return ctx
    }

    /** Convenience: a context attached to an admin user (access level 2). */
    protected fun adminCtx(path: String = "/test",
                           method: HttpMethod = HttpMethod.GET): ServiceRequestContext =
        ctxFor(path, method, getOrCreateUser("admin", level = 2))

    /** Convenience: a context attached to a viewer (access level 1). */
    protected fun viewerCtx(path: String = "/test",
                            method: HttpMethod = HttpMethod.GET): ServiceRequestContext =
        ctxFor(path, method, getOrCreateUser("viewer", level = 1))

    /** Find-or-create idempotent user helper. */
    protected fun getOrCreateUser(username: String, level: Int = 1): AppUser {
        val existing = AppUser.findAll().firstOrNull { it.username == username }
        if (existing != null) return existing
        val now = LocalDateTime.now()
        return AppUser(
            username = username, display_name = username,
            password_hash = PasswordService.hash("Test1234!@#"),
            access_level = level,
            created_at = now, updated_at = now,
        ).apply { save() }
    }

    // ---------------------- response inspection ----------------------

    /** Collect [response] body as a UTF-8 string. */
    protected fun readBody(response: HttpResponse): String =
        response.aggregate().get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .contentUtf8()

    /** Collect the response's status line. */
    protected fun statusOf(response: HttpResponse): HttpStatus =
        response.aggregate().get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .status()

    /** Decode the body as a JSON object. */
    protected fun readJsonObject(response: HttpResponse): JsonObject =
        JsonParser.parseString(readBody(response)).asJsonObject

    /** Decode the body as a JSON array of homogenous objects (e.g. lists). */
    protected fun readJsonArrayOf(response: HttpResponse, key: String): List<JsonObject> {
        val obj = readJsonObject(response)
        val arr = obj.getAsJsonArray(key) ?: return emptyList()
        return arr.map { it.asJsonObject }
    }
}
