package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SPA mount serves real files, falls back to index.html for client
 * routes, and answers a missing asset with 404 rather than index.html.
 * The last case is what a browser hits after a redeploy: a tab on the
 * previous build asks for a lazy chunk by its old hashed name, and an
 * index.html answer makes the module import fail with a MIME error
 * that the client cannot tell from a broken build.
 */
class SpaServiceTest : ArmeriaTestBase() {

    private lateinit var dir: Path
    private lateinit var service: SpaService

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("spa-test")
        dir.resolve("index.html").writeText("<!doctype html><title>INDEX</title>")
        dir.resolve("main-ABC123.js").writeText("console.log('main')")
        Files.createDirectories(dir.resolve("media"))
        dir.resolve("media/inter.woff2").writeText("font bytes")
        service = SpaService(dir)
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun get(path: String) = ctxFor(path).let { service.serve(it, it.request()) }

    @Test
    fun `serves a file that exists`() {
        assertEquals(HttpStatus.OK, statusOf(get("/main-ABC123.js")))
        assertTrue(readBody(get("/main-ABC123.js")).contains("console.log"))
    }

    @Test
    fun `serves a nested file that exists`() {
        assertEquals("font bytes", readBody(get("/media/inter.woff2")))
    }

    @Test
    fun `serves index for the root and for client routes`() {
        for (path in listOf("/", "/title/127", "/search", "/content/movies", "/admin/settings")) {
            assertEquals(HttpStatus.OK, statusOf(get(path)), path)
            assertTrue(readBody(get(path)).contains("INDEX"), path)
        }
    }

    @Test
    fun `answers 404 for a missing asset instead of index`() {
        for (path in listOf("/chunk-OLDHASH.js", "/main-OLDHASH.js", "/styles-OLDHASH.css",
                            "/media/missing.woff2", "/favicon-missing.ico", "/build-info.json")) {
            assertEquals(HttpStatus.NOT_FOUND, statusOf(get(path)), path)
        }
    }

    @Test
    fun `a client route segment with a dot still gets index`() {
        // Only known asset extensions mean "file"; a route like /tag/v1.2 is a page.
        assertTrue(readBody(get("/tag/v1.2")).contains("INDEX"))
    }
}
