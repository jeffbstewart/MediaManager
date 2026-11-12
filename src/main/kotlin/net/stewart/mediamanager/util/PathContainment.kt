package net.stewart.mediamanager.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Directory-containment check for path-traversal guards.
 *
 * A plain string prefix test (`child.canonicalPath.startsWith(root.canonicalPath)`)
 * has no separator boundary: with root `/nas/media`, the sibling
 * `/nas/media-private/x` passes. Comparing canonical [Path]s with
 * [Path.startsWith] matches whole name elements only.
 *
 * Beyond the boundary fix, two classes of path are refused outright rather
 * than resolved and re-checked:
 *  - any `..` element below the root (a request that *needs* resolving is
 *    not one we serve, even when it would resolve back inside);
 *  - any symbolic link below the root in the path as requested (the link's
 *    target may be inside today and repointed tomorrow; the NAS root itself
 *    may be a symlink or mount, so only elements beneath it are inspected).
 */
object PathContainment {

    /**
     * True when [child] is [root] itself or lies beneath it, and the path
     * as requested contains neither a `..` element nor a symbolic link
     * below [root]. Nonexistent trailing elements are allowed (callers
     * check existence separately), since they cannot be links.
     */
    fun isWithin(child: File, root: File): Boolean {
        val rootAbs = root.absoluteFile.toPath()
        val childAbs = child.absoluteFile.toPath()
        if (elementsBelow(childAbs, rootAbs).any { it.toString() == ".." }) return false

        // Resolved location must be the root or beneath it (whole-element match).
        val rootCanon = root.canonicalFile.toPath()
        val childCanon = child.canonicalFile.toPath()
        if (!childCanon.startsWith(rootCanon)) return false

        // No symlink in the requested path below the root. The child may name
        // the root by either spelling (as configured, or canonical); any other
        // route into the root goes through a link outside it and is refused.
        val childNorm = childAbs.normalize()
        val base = listOf(rootAbs.normalize(), rootCanon).firstOrNull { childNorm.startsWith(it) }
            ?: return false
        var cur = base
        for (elem in elementsBelow(childNorm, base)) {
            cur = cur.resolve(elem)
            if (Files.isSymbolicLink(cur)) return false
        }
        return true
    }

    /** Name elements of [path] after the [prefix] it starts with; all of [path] otherwise. */
    private fun elementsBelow(path: Path, prefix: Path): List<Path> {
        if (!path.startsWith(prefix)) return path.toList()
        if (path.nameCount == prefix.nameCount) return emptyList()
        return path.subpath(prefix.nameCount, path.nameCount).toList()
    }
}
