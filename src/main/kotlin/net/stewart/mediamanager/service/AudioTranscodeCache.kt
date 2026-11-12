package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * On-the-fly FLAC (/ OGG / WAV / OPUS) -> AAC m4a transcode cache. See
 * docs/MUSIC.md (M5). The first request for a given (track_id,
 * target_codec) pays the encode and writes to a sharded file under
 * `data/audio-transcode-cache/`; subsequent range requests serve the
 * completed file with no re-encode.
 *
 * The cache is intentionally small and ephemeral — at the configured
 * LRU cap (a few GB) the oldest-accessed files drop off and will be
 * re-encoded on next play. No DB row; filesystem is the source of truth.
 *
 * Concurrency: per-track locking prevents two concurrent first-requests
 * for the same track from racing ffmpeg writes. Different tracks encode
 * in parallel.
 */
object AudioTranscodeCache {

    private val log = LoggerFactory.getLogger(AudioTranscodeCache::class.java)

    private val cacheRoot: Path = Path.of("data", "audio-transcode-cache")

    /** Cap the cache at this many bytes before LRU-evicting. */
    private const val MAX_CACHE_BYTES = 4L * 1024 * 1024 * 1024   // 4 GiB

    /** Per-track mutex so two concurrent plays don't race ffmpeg onto the same temp file. */
    private val trackLocks = ConcurrentHashMap<Long, Any>()

    /**
     * Returns the path to the transcoded file for [trackId], encoding it
     * from [sourceFile] if not already cached. Null on ffmpeg failure.
     */
    fun cacheAndServe(
        trackId: Long,
        sourceFile: File,
        targetCodec: TargetCodec = TargetCodec.AAC_M4A,
        ffmpegPath: String = "ffmpeg"
    ): Path? {
        val dest = shardedPath(trackId, targetCodec)
        if (Files.exists(dest)) {
            // Touch the mtime so LRU eviction treats this as recently-used.
            runCatching { Files.setLastModifiedTime(dest, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
            return dest
        }

        val lock = trackLocks.computeIfAbsent(trackId) { Any() }
        synchronized(lock) {
            // Recheck after taking the lock in case another thread finished while we waited.
            if (Files.exists(dest)) return dest
            Files.createDirectories(dest.parent)
            val tmp = Files.createTempFile(dest.parent, "transcode-", ".tmp")
            val ok = encodeBlocking(sourceFile, tmp.toFile(), targetCodec, ffmpegPath)
            if (!ok) {
                runCatching { Files.deleteIfExists(tmp) }
                return null
            }
            // Atomic rename so range-seekers never observe a partial file.
            Files.move(tmp, dest,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)

            // Fire-and-forget eviction pass. Doesn't block the caller.
            Thread({ evictIfOverCap() }, "audio-cache-evict").apply {
                isDaemon = true
                start()
            }
            return dest
        }
    }

    /**
     * Encode a source audio file to AAC m4a on disk. Blocking — the
     * caller is expected to either use a small file (audio is fast) or
     * hold a mutex while this runs.
     *
     * `-ac 2` downmixes to stereo (MM doesn't do surround audio yet),
     * `-b:a 256k` hits the transparent threshold for AAC, and
     * `-movflags +faststart` puts the moov atom at the front of the
     * file so HTTP Range requests can seek without downloading the end
     * of the file first.
     */
    private fun encodeBlocking(
        source: File,
        dest: File,
        codec: TargetCodec,
        ffmpegPath: String
    ): Boolean {
        val args = mutableListOf(
            ffmpegPath,
            "-v", "error",
            "-i", source.absolutePath,
            "-vn",          // drop any embedded art; we don't want it in the output
            "-ac", "2"
        )
        args += codec.ffmpegArgs
        args += listOf("-y", dest.absolutePath)
        return try {
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            // Drain output so the ffmpeg process doesn't block on a full pipe.
            val output = proc.inputStream.bufferedReader().readText()
            // Audio encode at 20-40x realtime; 5 min cap covers long classical tracks.
            val finished = proc.waitFor(5, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                log.warn("ffmpeg audio transcode timed out on {}", source)
                return false
            }
            if (proc.exitValue() != 0) {
                log.warn("ffmpeg audio transcode failed on {}: {}", source, output.take(500))
                return false
            }
            true
        } catch (e: Exception) {
            log.warn("ffmpeg audio transcode error on {}: {}", source, e.message)
            false
        }
    }

    /**
     * Walk the cache, sum sizes, drop the least-recently-used files until
     * we're back under [MAX_CACHE_BYTES]. Runs after every successful
     * encode on a daemon thread so the request itself never waits.
     */
    private fun evictIfOverCap() {
        if (!Files.isDirectory(cacheRoot)) return
        val files = try {
            Files.walk(cacheRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { it to Files.getLastModifiedTime(it).toMillis() }
                    .map { (p, mtime) -> CacheEntry(p, Files.size(p), mtime) }
                    .sorted(Comparator.comparingLong { it.mtime })   // oldest first
                    .toList()
            }
        } catch (e: Exception) {
            log.warn("Cache eviction walk failed: {}", e.message)
            return
        }

        var total = files.sumOf { it.size }
        if (total <= MAX_CACHE_BYTES) return

        for (entry in files) {
            if (total <= MAX_CACHE_BYTES) break
            runCatching {
                Files.deleteIfExists(entry.path)
                total -= entry.size
            }
        }
        log.info("Audio transcode cache evicted down to {} bytes", total)
    }

    /** Admin summary. Returns (total bytes, file count, oldest mtime). */
    fun status(): CacheStatus {
        if (!Files.isDirectory(cacheRoot)) return CacheStatus(0, 0, null)
        return try {
            Files.walk(cacheRoot).use { stream ->
                var total = 0L
                var count = 0
                var oldest: Long? = null
                stream.filter { Files.isRegularFile(it) }.forEach { p ->
                    total += Files.size(p)
                    count++
                    val mtime = Files.getLastModifiedTime(p).toMillis()
                    if (oldest == null || mtime < oldest!!) oldest = mtime
                }
                CacheStatus(total, count, oldest)
            }
        } catch (e: Exception) {
            log.warn("AudioTranscodeCache.status walk failed: {}", e.message)
            CacheStatus(0, 0, null)
        }
    }

    /** Admin: clear all cached entries. */
    fun clearAll() {
        if (!Files.isDirectory(cacheRoot)) return
        try {
            Files.walk(cacheRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { p ->
                    runCatching { Files.deleteIfExists(p) }
                }
            }
        } catch (e: Exception) {
            log.warn("AudioTranscodeCache.clearAll walk failed: {}", e.message)
        }
    }

    /** Admin: clear entries for a specific track across all target codecs. */
    fun clearForTrack(trackId: Long) {
        TargetCodec.entries.forEach { codec ->
            runCatching { Files.deleteIfExists(shardedPath(trackId, codec)) }
        }
    }

    data class CacheStatus(val totalBytes: Long, val entryCount: Int, val oldestMtimeEpochMs: Long?)

    private fun shardedPath(trackId: Long, codec: TargetCodec): Path {
        val id = trackId.toString().padStart(6, '0')
        val shard1 = id.takeLast(2)
        val shard2 = id.takeLast(4).take(2)
        return cacheRoot
            .resolve(shard1)
            .resolve(shard2)
            .resolve("$trackId.${codec.extension}")
    }

    private data class CacheEntry(val path: Path, val size: Long, val mtime: Long)

    enum class TargetCodec(
        val extension: String,
        val contentType: String,
        val ffmpegArgs: List<String>
    ) {
        /**
         * AAC in an M4A container at 256 kbps CBR. `movflags +faststart`
         * makes the moov atom seekable from the front — essential for
         * HTTP Range scrubbing in the browser audio element.
         */
        AAC_M4A(
            extension = "m4a",
            contentType = "audio/mp4",
            ffmpegArgs = listOf(
                "-c:a", "aac",
                "-b:a", "256k",
                "-movflags", "+faststart",
                "-f", "ipod"   // m4a via the ipod muxer — widest compatibility
            )
        )
    }
}
