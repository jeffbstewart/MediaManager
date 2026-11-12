package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Detects and renames files and directories on the NAS whose names contain
 * characters that are disallowed on Windows (: ? * " < > |). These are
 * created by MakeMKV on Linux and can't be accessed from Windows clients.
 *
 * Runs as a fixup phase at the start of each NAS scan, before file discovery.
 */
object FilenameSanitizer {

    private val log = LoggerFactory.getLogger(FilenameSanitizer::class.java)

    // Characters forbidden in Windows filenames (excluding \ and / which are path separators)
    private val WINDOWS_DISALLOWED = Regex("[:\\?\\*\"<>|]")

    /**
     * Returns true if the filename contains Windows-disallowed characters.
     */
    fun needsSanitization(fileName: String): Boolean = WINDOWS_DISALLOWED.containsMatchIn(fileName)

    /**
     * Replaces Windows-disallowed characters with safe alternatives.
     * Colons become dashes, question marks are removed, others become underscores.
     * Collapses multiple consecutive replacement characters.
     */
    fun sanitize(fileName: String): String {
        val result = StringBuilder(fileName.length)
        for (ch in fileName) {
            when (ch) {
                ':' -> result.append(" -")
                '?' -> {} // drop question marks
                '*', '"', '<', '>', '|' -> result.append('_')
                else -> result.append(ch)
            }
        }
        return result.toString()
            .replace(Regex("\\s+"), " ")  // collapse whitespace runs
            .trim()
    }

    /**
     * Scans the NAS directories for files and directories with disallowed characters
     * and renames them. Also renames any corresponding ForBrowser files.
     *
     * @return list of renames performed (old relative path → new relative path)
     */
    fun fixupNasFiles(nasRoot: String): List<RenameResult> {
        val rootPath = Path.of(nasRoot)
        val results = mutableListOf<RenameResult>()
        var scannedFiles = 0
        var scannedDirs = 0

        log.info("Filename sanitization starting, NAS root: {}", nasRoot)

        // Scan flat movie directories (files only, no subdirs expected)
        for (dirName in listOf("BLURAY", "DVD", "UHD")) {
            val dir = rootPath.resolve(dirName)
            if (Files.isDirectory(dir)) {
                scannedFiles += fixupFilesFlat(dir, rootPath, results)
            }
        }

        // Scan TV directory — directories first (bottom-up), then files
        val tvDir = rootPath.resolve("TV Series From Media")
        if (Files.isDirectory(tvDir)) {
            scannedDirs += fixupDirectoriesRecursive(tvDir, rootPath, results)
            scannedFiles += fixupFilesRecursive(tvDir, rootPath, results)
        }

        log.info("Filename sanitization complete: scanned {} files and {} directories, renamed {}",
            scannedFiles, scannedDirs, results.size)

        return results
    }

    /**
     * Renames files with bad characters in a flat directory.
     * @return number of files scanned
     */
    private fun fixupFilesFlat(dir: Path, nasRoot: Path, results: MutableList<RenameResult>): Int {
        var count = 0
        try {
            Files.list(dir).use { stream ->
                stream.forEach { path ->
                    if (Files.isRegularFile(path)) {
                        count++
                        tryRenameFile(path, nasRoot, results)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Error scanning {} for bad filenames: {}", dir, e.message)
        }
        return count
    }

    /**
     * Renames files with bad characters recursively.
     * @return number of files scanned
     */
    private fun fixupFilesRecursive(dir: Path, nasRoot: Path, results: MutableList<RenameResult>): Int {
        var count = 0
        try {
            Files.walk(dir).use { stream ->
                val files = stream.filter { Files.isRegularFile(it) }.toList()
                count = files.size
                for (path in files) {
                    tryRenameFile(path, nasRoot, results)
                }
            }
        } catch (e: Exception) {
            log.warn("Error scanning {} for bad filenames: {}", dir, e.message)
        }
        return count
    }

    /**
     * Renames directories with bad characters, bottom-up so that child renames
     * don't invalidate parent paths.
     * @return number of directories scanned
     */
    private fun fixupDirectoriesRecursive(dir: Path, nasRoot: Path, results: MutableList<RenameResult>): Int {
        var count = 0
        try {
            Files.walk(dir).use { stream ->
                // Collect directories (excluding the root TV dir itself), sort deepest first
                val dirs = stream
                    .filter { Files.isDirectory(it) && it != dir }
                    .toList()
                    .sortedByDescending { it.nameCount }
                count = dirs.size
                for (d in dirs) {
                    tryRenameDirectory(d, nasRoot, results)
                }
            }
        } catch (e: Exception) {
            log.warn("Error scanning {} for bad directory names: {}", dir, e.message)
        }
        return count
    }

    private fun tryRenameFile(path: Path, nasRoot: Path, results: MutableList<RenameResult>) {
        val fileName = path.fileName.toString()
        if (!needsSanitization(fileName)) return

        val sanitized = sanitize(fileName)
        if (sanitized == fileName) return

        val targetPath = path.resolveSibling(sanitized)
        if (Files.exists(targetPath)) {
            log.warn("Cannot rename file '{}' -> '{}': target already exists", fileName, sanitized)
            return
        }

        try {
            Files.move(path, targetPath, StandardCopyOption.ATOMIC_MOVE)
            val oldRelative = nasRoot.relativize(path).toString().replace('\\', '/')
            val newRelative = nasRoot.relativize(targetPath).toString().replace('\\', '/')
            log.info("Sanitized filename: {} -> {}", oldRelative, newRelative)

            renameForBrowserFile(nasRoot, path, targetPath)

            results.add(RenameResult(oldRelative, newRelative))
        } catch (e: Exception) {
            log.error("Failed to rename file '{}' -> '{}': {}", fileName, sanitized, e.message)
        }
    }

    private fun tryRenameDirectory(path: Path, nasRoot: Path, results: MutableList<RenameResult>) {
        val dirName = path.fileName.toString()
        if (!needsSanitization(dirName)) return

        val sanitized = sanitize(dirName)
        if (sanitized == dirName) return

        val targetPath = path.resolveSibling(sanitized)
        if (Files.exists(targetPath)) {
            log.warn("Cannot rename directory '{}' -> '{}': target already exists", dirName, sanitized)
            return
        }

        try {
            Files.move(path, targetPath)
            val oldRelative = nasRoot.relativize(path).toString().replace('\\', '/')
            val newRelative = nasRoot.relativize(targetPath).toString().replace('\\', '/')
            log.info("Sanitized directory: {} -> {}", oldRelative, newRelative)

            renameForBrowserDirectory(nasRoot, path, targetPath)

            results.add(RenameResult(oldRelative, newRelative))
        } catch (e: Exception) {
            log.error("Failed to rename directory '{}' -> '{}': {}", dirName, sanitized, e.message)
        }
    }

    private fun renameForBrowserFile(nasRoot: Path, oldSourcePath: Path, newSourcePath: Path) {
        try {
            val nasRootFile = nasRoot.toFile()
            val oldForBrowser = TranscoderAgent.getForBrowserPath(
                nasRootFile.absolutePath, oldSourcePath.toString()
            )
            if (oldForBrowser.exists()) {
                val newForBrowser = TranscoderAgent.getForBrowserPath(
                    nasRootFile.absolutePath, newSourcePath.toString()
                )
                newForBrowser.parentFile?.mkdirs()
                Files.move(oldForBrowser.toPath(), newForBrowser.toPath(), StandardCopyOption.ATOMIC_MOVE)
                log.info("Renamed ForBrowser file: {} -> {}",
                    nasRoot.relativize(oldForBrowser.toPath()).toString().replace('\\', '/'),
                    nasRoot.relativize(newForBrowser.toPath()).toString().replace('\\', '/'))
            }
        } catch (e: Exception) {
            log.warn("Failed to rename ForBrowser file for {}: {}", oldSourcePath.fileName, e.message)
        }
    }

    private fun renameForBrowserDirectory(nasRoot: Path, oldDirPath: Path, newDirPath: Path) {
        try {
            val nasRootFile = nasRoot.toFile()
            val relativePath = nasRoot.relativize(oldDirPath).toString().replace('\\', '/')
            val oldForBrowserDir = File(nasRootFile, "ForBrowser/$relativePath")
            if (oldForBrowserDir.isDirectory) {
                val newRelativePath = nasRoot.relativize(newDirPath).toString().replace('\\', '/')
                val newForBrowserDir = File(nasRootFile, "ForBrowser/$newRelativePath")
                newForBrowserDir.parentFile?.mkdirs()
                Files.move(oldForBrowserDir.toPath(), newForBrowserDir.toPath())
                log.info("Renamed ForBrowser directory: ForBrowser/{} -> ForBrowser/{}",
                    relativePath, newRelativePath)
            }
        } catch (e: Exception) {
            log.warn("Failed to rename ForBrowser directory for {}: {}", oldDirPath.fileName, e.message)
        }
    }

    data class RenameResult(val oldPath: String, val newPath: String)
}
