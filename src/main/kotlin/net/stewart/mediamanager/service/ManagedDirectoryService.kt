package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Ensures mediamanager-managed directories exist under the NAS root and contain
 * a `.mm-ignore` marker file so the NAS scanner skips them.
 *
 * Managed directories are owned by mediamanager (transcoded outputs, thumbnails, etc.)
 * and should never be scanned as media source directories.
 */
object ManagedDirectoryService {

    private val log = LoggerFactory.getLogger(ManagedDirectoryService::class.java)

    const val MARKER_FILE = ".mm-ignore"

    /** Directories managed by mediamanager — always excluded from NAS scanning. */
    val MANAGED_DIRS = listOf(
        "ForBrowser",
        "ForMobile"
    )

    /**
     * For each managed directory: create it if missing, then ensure `.mm-ignore` exists.
     * Called on startup after NAS root is configured.
     */
    fun ensureManagedDirectories() {
        val nasRoot = getNasRootPath() ?: return
        val rootDir = File(nasRoot)
        if (!rootDir.isDirectory) return

        for (dirName in MANAGED_DIRS) {
            val dir = File(rootDir, dirName)
            if (!dir.exists()) {
                if (dir.mkdirs()) {
                    log.info("Created managed directory: {}", dir.absolutePath)
                } else {
                    log.warn("Failed to create managed directory: {}", dir.absolutePath)
                    continue
                }
            }
            ensureMarkerFile(dir)
        }
    }

    /**
     * Returns true if the given directory contains a `.mm-ignore` marker file.
     */
    fun isIgnored(dir: File): Boolean = File(dir, MARKER_FILE).exists()

    private fun ensureMarkerFile(dir: File) {
        val marker = File(dir, MARKER_FILE)
        if (!marker.exists()) {
            try {
                marker.writeText("This directory is managed by mediamanager and excluded from NAS scanning.\n")
                log.info("Created {} in {}", MARKER_FILE, dir.absolutePath)
            } catch (e: Exception) {
                log.warn("Failed to create {} in {}: {}", MARKER_FILE, dir.absolutePath, e.message)
            }
        }
    }

    private fun getNasRootPath(): String? =
        AppConfig.findAll().firstOrNull { it.config_key == "nas_root_path" }?.config_val
}
