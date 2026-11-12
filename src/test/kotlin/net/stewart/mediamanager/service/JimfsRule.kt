package net.stewart.mediamanager.service

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import org.junit.rules.ExternalResource
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * JUnit 4 `@Rule` that swaps an in-memory Jimfs into [Filesystems.current]
 * for the duration of a test, then restores the JVM default and closes
 * the in-memory filesystem in teardown.
 *
 * Tests that need to seed paths before the production code runs use
 * [seed] to create a parent directory tree and write file content in
 * one call.
 *
 * ```
 * @get:Rule val fsRule = JimfsRule()
 *
 * @Test fun `something`() {
 *     fsRule.seed("/nas/movies/Foundation.mp4", bytes = byteArrayOf())
 *     // Production code calling File(...).exists() through Filesystems.current
 *     // sees the seeded file.
 * }
 * ```
 *
 * Always defaults to a Unix Jimfs configuration regardless of host OS.
 * The disk-touching production code routes its path *arithmetic* (not
 * just existence checks) through [Filesystems.current], so a Unix-style
 * path string parses and resolves consistently on every host. Jimfs's
 * Windows config rejects Unix-style absolute paths outright, so we
 * don't try to match the host — the seam is the active filesystem,
 * not the host's separator.
 *
 * Tests pass `/`-rooted absolute paths; the rule resolves them against
 * Jimfs's `/` root.
 */
class JimfsRule(
    private val windowsLike: Boolean = false,
) : ExternalResource() {

    lateinit var fs: FileSystem
        private set

    override fun before() {
        val config = if (windowsLike) Configuration.windows() else Configuration.unix()
        fs = Jimfs.newFileSystem(config)
        Filesystems.current = fs
    }

    override fun after() {
        Filesystems.current = FileSystems.getDefault()
        fs.close()
    }

    /** Resolve a string path against the active Jimfs. */
    fun path(p: String): Path = pathify(p)

    /**
     * Create the parent directories for [absolutePath] (idempotent) and
     * write [bytes] to it. Returns the resolved [Path]. The path string
     * is portable across hosts — pass it with `/` separators and a
     * leading `/`; the rule resolves it against the active filesystem's
     * first root (`/` on Unix-Jimfs, the working drive on Windows-Jimfs)
     * so it lines up with what production code (which uses `File` for
     * path arithmetic and emits host-native separators) eventually hands
     * back to [Filesystems.current].
     */
    fun seed(absolutePath: String, bytes: ByteArray = ByteArray(0)): Path {
        val target = pathify(absolutePath)
        target.parent?.let { Files.createDirectories(it) }
        Files.write(target, bytes)
        return target
    }

    /** Create [absolutePath] as a directory. Parents are created too. */
    fun seedDir(absolutePath: String): Path {
        val target = pathify(absolutePath)
        Files.createDirectories(target)
        return target
    }

    /**
     * Split on either separator and resolve part-by-part against the
     * filesystem's first root directory. Lets the underlying Jimfs apply
     * its own separator convention regardless of how the test wrote the
     * path string.
     */
    private fun pathify(p: String): Path {
        val parts = p.trim().split('/', '\\').filter { it.isNotEmpty() }
        return parts.fold(fs.rootDirectories.first()) { acc, part ->
            acc.resolve(part)
        }
    }
}
