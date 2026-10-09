package net.stewart.mediamanager.util

import java.io.File

/**
 * Directory-containment check for path-traversal guards.
 *
 * A plain string prefix test (`child.canonicalPath.startsWith(root.canonicalPath)`)
 * has no separator boundary: with root `/nas/media`, the sibling
 * `/nas/media-private/x` passes. Comparing canonical [java.nio.file.Path]s
 * with [java.nio.file.Path.startsWith] matches whole name elements only.
 */
object PathContainment {

    /**
     * True when [child], after canonicalization (resolving `..` and
     * symlinks), is [root] itself or lies beneath it.
     */
    fun isWithin(child: File, root: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        return childPath.startsWith(rootPath)
    }
}
