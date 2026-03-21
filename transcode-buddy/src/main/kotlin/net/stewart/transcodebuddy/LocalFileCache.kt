package net.stewart.transcodebuddy

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages a dedicated temp directory for staging source files locally before processing.
 *
 * Files are copied from the NAS to local temp with a `.copying` marker during transfer.
 * A `manifest.json` tracks the source of each cached file so we can:
 * - Avoid unnecessary recopies on restart (check size match)
 * - Clean up files with no pending work (query server)
 * - Detect and delete incomplete transfers (`.copying` files)
 */
class LocalFileCache(
    private val tempDir: File,
    private val apiClient: BuddyApiClient
) {

    private val log = LoggerFactory.getLogger(LocalFileCache::class.java)
    private val gson = Gson()
    private val manifestFile = File(tempDir, "manifest.json")

    /** In-memory manifest, synchronized for thread safety. */
    private val entries = mutableListOf<ManifestEntry>()
    private val lock = Any()

    data class ManifestEntry(
        val transcodeId: Long,
        val relativePath: String,
        val localFilename: String,
        val sourceSizeBytes: Long,
        val copiedAt: String
    )

    init {
        tempDir.mkdirs()
    }

    /**
     * Startup cleanup:
     * 1. Delete `.copying` files (incomplete transfers)
     * 2. Load manifest, drop entries whose local file is missing or size doesn't match
     * 3. Query server for pending work on surviving entries
     * 4. Delete cached files with no pending work
     */
    fun startupCleanup() {
        log.info("Local file cache startup cleanup in {}", tempDir.absolutePath)

        // 1. Delete incomplete transfers
        val copyingFiles = tempDir.listFiles()?.filter { it.name.endsWith(".copying") } ?: emptyList()
        for (f in copyingFiles) {
            log.info("Deleting incomplete transfer: {}", f.name)
            f.delete()
        }

        // 2. Load and validate manifest
        loadManifest()
        synchronized(lock) {
            val toRemove = entries.filter { entry ->
                val localFile = File(tempDir, entry.localFilename)
                if (!localFile.exists()) {
                    log.info("Cached file missing, removing from manifest: {}", entry.localFilename)
                    true
                } else if (localFile.length() != entry.sourceSizeBytes) {
                    log.info("Cached file size mismatch (expected={}, actual={}), deleting: {}",
                        entry.sourceSizeBytes, localFile.length(), entry.localFilename)
                    localFile.delete()
                    true
                } else {
                    false
                }
            }
            entries.removeAll(toRemove.toSet())
        }

        // 3. Query server for pending work
        val cachedIds = synchronized(lock) { entries.map { it.transcodeId } }
        if (cachedIds.isNotEmpty()) {
            val pending = try {
                apiClient.checkPending(cachedIds)
            } catch (e: Exception) {
                log.warn("Failed to check pending work on startup: {}", e.message)
                cachedIds // Keep all if server unreachable
            }
            val pendingSet = pending.toSet()

            synchronized(lock) {
                val stale = entries.filter { it.transcodeId !in pendingSet }
                for (entry in stale) {
                    log.info("No pending work for cached file, deleting: {} (transcode_id={})",
                        entry.localFilename, entry.transcodeId)
                    File(tempDir, entry.localFilename).delete()
                }
                entries.removeAll(stale.toSet())
            }
        }

        saveManifest()

        val remaining = synchronized(lock) { entries.size }
        log.info("Cache cleanup complete: {} cached file(s) retained", remaining)
    }

    /** Returns the set of transcode IDs currently in the local cache. */
    fun getCachedTranscodeIds(): Set<Long> = synchronized(lock) {
        entries.map { it.transcodeId }.toSet()
    }

    /** Returns the local cached file for a transcode ID, or null if not cached. */
    fun getCachedFile(transcodeId: Long): File? = synchronized(lock) {
        val entry = entries.find { it.transcodeId == transcodeId } ?: return null
        val file = File(tempDir, entry.localFilename)
        if (file.exists()) file else null
    }

    /**
     * Copies a source file from NAS to local temp. Uses `.copying` marker during transfer.
     * Returns the local file on success, null on failure.
     */
    fun stageFile(transcodeId: Long, relativePath: String, sourceFile: File): File? {
        if (!sourceFile.exists()) {
            log.error("Source file not found for staging: {}", sourceFile.absolutePath)
            return null
        }

        val localFilename = buildLocalFilename(transcodeId, relativePath)
        val targetFile = File(tempDir, localFilename)
        val copyingFile = File(tempDir, "$localFilename.copying")

        // Check if already cached and valid
        if (targetFile.exists() && targetFile.length() == sourceFile.length()) {
            log.info("File already cached locally: {}", localFilename)
            return targetFile
        }

        // Check available disk space (need at least file size + 1 GB buffer)
        val freeSpace = tempDir.usableSpace
        val needed = sourceFile.length() + 1_073_741_824L
        if (freeSpace < needed) {
            log.error("Insufficient disk space for staging: need {} bytes, have {} bytes",
                needed, freeSpace)
            return null
        }

        log.info("Staging {} -> {} ({} bytes)", sourceFile.name, localFilename, sourceFile.length())
        val startTime = System.currentTimeMillis()

        try {
            // Copy with .copying marker
            Files.copy(sourceFile.toPath(), copyingFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

            // Verify size
            if (copyingFile.length() != sourceFile.length()) {
                log.error("Copy size mismatch: expected={}, actual={}", sourceFile.length(), copyingFile.length())
                copyingFile.delete()
                return null
            }

            // Atomic rename: .copying -> final
            if (!copyingFile.renameTo(targetFile)) {
                log.error("Failed to rename {} -> {}", copyingFile.name, targetFile.name)
                copyingFile.delete()
                return null
            }

            val elapsedSecs = (System.currentTimeMillis() - startTime) / 1000.0
            val mbPerSec = if (elapsedSecs > 0) sourceFile.length() / (1_048_576.0 * elapsedSecs) else 0.0
            log.info("Staged {} in {:.1f}s ({:.1f} MB/s)", localFilename, elapsedSecs, mbPerSec)

            // Update manifest
            val entry = ManifestEntry(
                transcodeId = transcodeId,
                relativePath = relativePath,
                localFilename = localFilename,
                sourceSizeBytes = sourceFile.length(),
                copiedAt = java.time.LocalDateTime.now().toString()
            )
            synchronized(lock) {
                entries.removeAll { it.transcodeId == transcodeId }
                entries.add(entry)
            }
            saveManifest()

            return targetFile

        } catch (e: IOException) {
            log.error("Failed to stage file {}: {}", sourceFile.name, e.message)
            copyingFile.delete()
            targetFile.delete()
            return null
        }
    }

    /** Removes a cached file and its manifest entry. */
    fun remove(transcodeId: Long) {
        synchronized(lock) {
            val entry = entries.find { it.transcodeId == transcodeId }
            if (entry != null) {
                val file = File(tempDir, entry.localFilename)
                if (file.exists()) {
                    log.info("Removing cached file: {}", entry.localFilename)
                    file.delete()
                }
                entries.remove(entry)
            }
        }
        saveManifest()
    }

    private fun buildLocalFilename(transcodeId: Long, relativePath: String): String {
        val sanitized = relativePath.replace('/', '_').replace('\\', '_')
        return "${transcodeId}_$sanitized"
    }

    private fun loadManifest() {
        synchronized(lock) {
            entries.clear()
            if (!manifestFile.exists()) return
            try {
                val type = object : TypeToken<List<ManifestEntry>>() {}.type
                val loaded: List<ManifestEntry> = gson.fromJson(manifestFile.readText(), type) ?: emptyList()
                entries.addAll(loaded)
                log.info("Loaded {} manifest entries", entries.size)
            } catch (e: Exception) {
                log.warn("Failed to load manifest, starting fresh: {}", e.message)
                entries.clear()
            }
        }
    }

    private fun saveManifest() {
        try {
            val snapshot = synchronized(lock) { entries.toList() }
            manifestFile.writeText(gson.toJson(snapshot))
        } catch (e: Exception) {
            log.warn("Failed to save manifest: {}", e.message)
        }
    }
}
