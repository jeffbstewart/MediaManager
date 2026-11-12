package net.stewart.mediamanager.util

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathContainmentTest {

    private lateinit var base: File
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("pathcontain").toFile()
        root = File(base, "media").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    /**
     * Symlink creation needs a privilege on Windows (Developer Mode or
     * SeCreateSymbolicLinkPrivilege). Returns false, and the caller returns
     * early, when the platform refuses; CI runs on Linux where it works.
     */
    private fun trySymlink(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (e: Exception) {
        println("skipping symlink assertions: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    @Test
    fun `file directly and deeply under root is within`() {
        assertTrue(PathContainment.isWithin(File(root, "movie.mp4"), root))
        assertTrue(PathContainment.isWithin(File(root, "ForBrowser/Movies/a.mp4"), root))
    }

    @Test
    fun `nonexistent file under root is within`() {
        assertTrue(PathContainment.isWithin(File(root, "ForBrowser/not/yet/transcoded.mp4"), root))
    }

    @Test
    fun `root itself is within`() {
        assertTrue(PathContainment.isWithin(root, root))
    }

    @Test
    fun `sibling directory sharing the root name as a prefix is NOT within`() {
        // The old canonicalPath string-prefix check accepted this.
        val sibling = File(base, "media-private").apply { mkdirs() }
        val secret = File(sibling, "secret.mp4")
        assertTrue(secret.canonicalPath.startsWith(root.canonicalPath), "precondition: string prefix matches")
        assertFalse(PathContainment.isWithin(secret, root))
        assertFalse(PathContainment.isWithin(File(base, "media2.mp4"), root))
    }

    @Test
    fun `dot-dot below root is refused even when it would resolve back inside`() {
        assertFalse(PathContainment.isWithin(File(root, "../outside.mp4"), root))
        assertFalse(PathContainment.isWithin(File(root, "sub/../../media-private/x"), root))
        assertFalse(PathContainment.isWithin(File(root, "sub/../inside.mp4"), root))
        assertFalse(PathContainment.isWithin(File(root, ".."), root))
    }

    @Test
    fun `dot-dot inside the configured root spelling is tolerated`() {
        // A root configured as /x/other/../media is the operator's business;
        // only elements below the root are policed.
        val oddRoot = File(base, "other/../media")
        assertTrue(PathContainment.isWithin(File(oddRoot, "movie.mp4"), oddRoot))
        assertFalse(PathContainment.isWithin(File(oddRoot, "../media-private/x"), oddRoot))
    }

    @Test
    fun `parent of root is not within`() {
        assertFalse(PathContainment.isWithin(base, root))
    }

    @Test
    fun `symlink below root pointing outside is refused`() {
        val outside = File(base, "elsewhere").apply { mkdirs() }
        File(outside, "secret.mp4").writeText("x")
        val link = File(root, "escape")
        if (!trySymlink(link, outside)) return
        assertFalse(PathContainment.isWithin(File(link, "secret.mp4"), root))
        assertFalse(PathContainment.isWithin(link, root))
    }

    @Test
    fun `symlink below root pointing inside is refused too`() {
        val real = File(root, "Movies").apply { mkdirs() }
        File(real, "a.mp4").writeText("x")
        val link = File(root, "Films")
        if (!trySymlink(link, real)) return
        assertFalse(PathContainment.isWithin(File(link, "a.mp4"), root), "directory link")
        val fileLink = File(root, "a-link.mp4")
        if (!trySymlink(fileLink, File(real, "a.mp4"))) return
        assertFalse(PathContainment.isWithin(fileLink, root), "file link")
        assertTrue(PathContainment.isWithin(File(real, "a.mp4"), root), "the real file still passes")
    }

    @Test
    fun `root reached through a symlink is fine, links beneath it are not`() {
        // Mount-style setups: the configured NAS root is itself a link.
        val real = File(base, "volume1/media").apply { mkdirs() }
        File(real, "a.mp4").writeText("x")
        val rootLink = File(base, "nas")
        if (!trySymlink(rootLink, real)) return
        assertTrue(PathContainment.isWithin(File(rootLink, "a.mp4"), rootLink), "requested via the link root")
        assertTrue(PathContainment.isWithin(File(real, "a.mp4"), rootLink), "requested via the canonical root")
        assertTrue(PathContainment.isWithin(rootLink, rootLink), "the link root itself")
        val inner = File(rootLink, "inner")
        if (!trySymlink(inner, File(real, "sub").apply { mkdirs() })) return
        assertFalse(PathContainment.isWithin(File(inner, "b.mp4"), rootLink))
    }

    @Test
    fun `path entering the root through a foreign symlink is refused`() {
        File(root, "a.mp4").writeText("x")
        val alias = File(base, "alias")
        if (!trySymlink(alias, root)) return
        // Canonically inside the root, but the request walks a link we do not own.
        assertFalse(PathContainment.isWithin(File(alias, "a.mp4"), root))
    }
}
