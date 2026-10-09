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

    @Test
    fun `file directly and deeply under root is within`() {
        assertTrue(PathContainment.isWithin(File(root, "movie.mp4"), root))
        assertTrue(PathContainment.isWithin(File(root, "ForBrowser/Movies/a.mp4"), root))
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
    fun `dot-dot escapes are resolved and rejected`() {
        assertFalse(PathContainment.isWithin(File(root, "../outside.mp4"), root))
        assertFalse(PathContainment.isWithin(File(root, "sub/../../media-private/x"), root))
        assertTrue(PathContainment.isWithin(File(root, "sub/../inside.mp4"), root))
    }

    @Test
    fun `parent of root is not within`() {
        assertFalse(PathContainment.isWithin(base, root))
    }
}
