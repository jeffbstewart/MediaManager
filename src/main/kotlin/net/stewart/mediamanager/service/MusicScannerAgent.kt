package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Background daemon that walks [CONFIG_KEY_MUSIC_ROOT] for audio files,
 * reads tags via [AudioTagReader], and either links them to existing
 * [Track] rows, auto-catalogues new albums via [MusicBrainzService] +
 * [MusicIngestionService], or parks them in [UnmatchedAudio] for admin
 * triage. See docs/MUSIC.md (M4).
 *
 * Mirrors [BookScannerAgent]: same daemon lifecycle, same 45 s startup
 * delay, same hourly cycle, same `scanNow()` public trigger for the
 * post-NAS-scan hook.
 */
class MusicScannerAgent(
    private val clock: Clock = SystemClock,
    private val musicBrainz: MusicBrainzService = MusicBrainzHttpService(),
    private val tagReader: (File) -> AudioTagReader.AudioTags = { AudioTagReader.read(it) }
) {

    private val log = LoggerFactory.getLogger(MusicScannerAgent::class.java)
    internal val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val scanMutex = Any()

    init { INSTANCE = this }

    companion object {
        /** app_config key pointing at the folder containing .flac / .mp3 / .m4a / .ogg / .wav files. */
        const val CONFIG_KEY_MUSIC_ROOT = "music_root_path"

        private val CYCLE_INTERVAL = 1.hours
        private val STARTUP_DELAY = 45.seconds
        private val AUDIO_EXTENSIONS = setOf("flac", "mp3", "m4a", "ogg", "oga", "wav", "opus")

        /** Max files walked per cycle — keeps the scan bounded on large shelves. */
        private const val MAX_FILES_PER_CYCLE = 1000

        @Volatile private var INSTANCE: MusicScannerAgent? = null

        fun scanNowIfAvailable() { INSTANCE?.scanNow() }
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("MusicScannerAgent started (cycle every {}m, root key '{}')",
                CYCLE_INTERVAL.inWholeMinutes, CONFIG_KEY_MUSIC_ROOT)
            try { clock.sleep(STARTUP_DELAY) } catch (_: InterruptedException) { return@Thread }
            while (running.get()) {
                try {
                    synchronized(scanMutex) { scanOnce() }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("MusicScannerAgent error: {}", e.message, e)
                }
                try { clock.sleep(CYCLE_INTERVAL) } catch (_: InterruptedException) { break }
            }
            log.info("MusicScannerAgent stopped")
        }, "music-scanner").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /** Synchronized one-off — same shape as BookScannerAgent.scanNow. */
    fun scanNow() {
        synchronized(scanMutex) {
            try {
                scanOnce()
            } catch (e: Exception) {
                log.error("MusicScannerAgent.scanNow error: {}", e.message, e)
            }
        }
    }

    internal fun scanOnce() {
        val rootStr = musicRoot() ?: run {
            log.debug("MusicScannerAgent: '{}' not configured, skipping", CONFIG_KEY_MUSIC_ROOT)
            return
        }
        val root = Path.of(rootStr)
        if (!Files.isDirectory(root)) {
            log.warn("MusicScannerAgent: music_root_path '{}' is not a directory, skipping", rootStr)
            return
        }

        val knownTrackPaths = Track.findAll().mapNotNullTo(HashSet()) { it.file_path }
        val knownUnmatchedPaths = UnmatchedAudio.findAll().mapTo(HashSet()) { it.file_path }

        // Bucket candidate files by their containing directory. dBpoweramp's
        // default layout is `<Artist>/<Album>/NN-<Track>.<ext>`, so grouping
        // by parent directory cleanly separates albums and lets us fetch MB
        // once per album rather than once per track.
        val candidatesByDir = mutableMapOf<Path, MutableList<Pair<Path, String>>>()
        Files.walk(root).use { stream ->
            var count = 0
            for (p in stream) {
                if (!Files.isRegularFile(p)) continue
                val ext = p.fileName.toString().substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTENSIONS) continue
                val canonical = p.toString()
                if (canonical in knownTrackPaths || canonical in knownUnmatchedPaths) continue
                val dir = p.parent ?: continue
                candidatesByDir.getOrPut(dir) { mutableListOf() }.add(p to ext)
                count++
                if (count >= MAX_FILES_PER_CYCLE) {
                    log.info("MusicScannerAgent: hit MAX_FILES_PER_CYCLE={}, rest next cycle",
                        MAX_FILES_PER_CYCLE)
                    break
                }
            }
        }

        if (candidatesByDir.isEmpty()) return

        var processed = 0
        var linked = 0
        var staged = 0
        for ((dir, files) in candidatesByDir.toSortedMap()) {
            if (!running.get()) break
            try {
                val stats = handleAlbumDir(dir, files, knownTrackPaths, knownUnmatchedPaths)
                linked += stats.linked
                staged += stats.staged
                processed += files.size
            } catch (e: Exception) {
                log.warn("MusicScannerAgent: failed processing {}: {}", dir, e.message, e)
            }
        }
        if (processed > 0) {
            log.info("MusicScannerAgent: processed {} file(s) under {} — {} linked, {} staged",
                processed, rootStr, linked, staged)
        }
    }

    private data class DirStats(val linked: Int, val staged: Int)

    /**
     * Process one directory's worth of audio files. The core decision tree:
     *
     *   - If every file shares a `MUSICBRAINZ_ALBUMID` tag, that's the MB
     *     release MBID. Look up or create the matching [Title] + tracks,
     *     then link each file to its [Track] via recording MBID or
     *     (disc,track) fallback.
     *   - If tags are sparse/inconsistent, park the remaining files in
     *     [UnmatchedAudio] for admin triage.
     */
    private fun handleAlbumDir(
        dir: Path,
        files: List<Pair<Path, String>>,
        knownTrackPaths: HashSet<String>,
        knownUnmatchedPaths: HashSet<String>
    ): DirStats {
        val tagged = files.map { (path, ext) ->
            val tags = tagReader(path.toFile())
            TaggedFile(path, ext, tags)
        }

        // Pick the dominant release MBID in the directory. If at least two
        // files agree, that's the album. Stray files with no or mismatched
        // MBIDs fall to unmatched.
        val dominantReleaseId = tagged
            .mapNotNull { it.tags.musicBrainzReleaseId }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }?.key

        var linked = 0
        var staged = 0

        if (dominantReleaseId != null) {
            val title = findOrIngestTitleByRelease(dominantReleaseId)
            if (title != null) {
                val tracks = Track.findAll().filter { it.title_id == title.id }
                for (tf in tagged) {
                    if (tf.tags.musicBrainzReleaseId != dominantReleaseId) {
                        staged += stageUnmatched(tf, knownUnmatchedPaths)
                        continue
                    }
                    if (linkTagged(tf, tracks)) {
                        linked++
                        knownTrackPaths += tf.path.toString()
                    } else {
                        staged += stageUnmatched(tf, knownUnmatchedPaths)
                    }
                }
                return DirStats(linked, staged)
            }
        }

        // Tags didn't resolve to an album we can auto-catalogue. Park
        // everything for triage.
        for (tf in tagged) {
            staged += stageUnmatched(tf, knownUnmatchedPaths)
        }
        return DirStats(linked, staged)
    }

    /**
     * Returns the [Title] for the given MusicBrainz release MBID, ingesting
     * via MB if no matching Title exists yet. Null when MB itself can't
     * resolve the MBID or the lookup errors out.
     */
    private fun findOrIngestTitleByRelease(releaseMbid: String): Title? {
        val existing = Title.findAll().firstOrNull {
            it.media_type == MediaType.ALBUM.name &&
                it.musicbrainz_release_id == releaseMbid
        }
        if (existing != null) return existing

        val result = musicBrainz.lookupByReleaseMbid(releaseMbid)
        if (result !is MusicBrainzResult.Success) {
            if (result is MusicBrainzResult.Error) {
                log.warn("MusicBrainz lookup for release {} failed: {}",
                    releaseMbid, result.message)
            }
            return null
        }
        // Digital edition — no UPC; the release-group key still dedupes
        // across a prior CD scan and the new rip directory, so both
        // pressings end up on the same Title.
        val ingest = MusicIngestionService.ingest(
            upc = null,
            mediaFormat = MediaFormat.AUDIO_FLAC,
            lookup = result.release,
            clock = clock
        )
        return ingest.title
    }

    /**
     * Link a tagged file to a [Track] row. Preferred: recording MBID
     * match (dBpoweramp writes these as `MUSICBRAINZ_TRACKID`). Fallback:
     * disc + track number match within the same [Title]. Sets
     * `track.file_path` and returns true on success.
     */
    private fun linkTagged(tf: TaggedFile, tracks: List<Track>): Boolean {
        val now = LocalDateTime.now()
        val target = tracks.firstOrNull {
            it.musicbrainz_recording_id != null &&
                it.musicbrainz_recording_id == tf.tags.musicBrainzRecordingId
        } ?: tracks.firstOrNull {
            tf.tags.trackNumber != null &&
                it.track_number == tf.tags.trackNumber &&
                it.disc_number == (tf.tags.discNumber ?: 1)
        } ?: return false

        if (target.file_path != null) return false  // already linked, don't stomp
        target.file_path = tf.path.toString()
        target.updated_at = now
        target.save()
        return true
    }

    private fun stageUnmatched(
        tf: TaggedFile,
        knownUnmatchedPaths: HashSet<String>
    ): Int {
        val canonical = tf.path.toString()
        if (canonical in knownUnmatchedPaths) return 0
        val file = tf.path.toFile()
        val format = extensionToFormat(tf.ext)
        UnmatchedAudio(
            file_path = canonical,
            file_name = file.name,
            file_size_bytes = runCatching { file.length() }.getOrNull(),
            media_format = format.name,
            parsed_title = tf.tags.title,
            parsed_album = tf.tags.album,
            parsed_album_artist = tf.tags.albumArtist,
            parsed_track_artist = tf.tags.trackArtist,
            parsed_track_number = tf.tags.trackNumber,
            parsed_disc_number = tf.tags.discNumber,
            parsed_duration_seconds = tf.tags.durationSeconds,
            parsed_mb_release_id = tf.tags.musicBrainzReleaseId,
            parsed_mb_release_group_id = tf.tags.musicBrainzReleaseGroupId,
            parsed_mb_recording_id = tf.tags.musicBrainzRecordingId,
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()
        ).save()
        knownUnmatchedPaths += canonical
        log.info("Unmatched audio staged: {} (album='{}' track='{}')",
            canonical, tf.tags.album, tf.tags.title)
        return 1
    }

    internal fun extensionToFormat(ext: String): MediaFormat = when (ext) {
        "flac" -> MediaFormat.AUDIO_FLAC
        "mp3" -> MediaFormat.AUDIO_MP3
        "m4a" -> MediaFormat.AUDIO_AAC
        "aac" -> MediaFormat.AUDIO_AAC
        "ogg", "oga", "opus" -> MediaFormat.AUDIO_OGG
        "wav" -> MediaFormat.AUDIO_WAV
        else -> MediaFormat.UNKNOWN
    }

    private fun musicRoot(): String? =
        AppConfig.findAll()
            .firstOrNull { it.config_key == CONFIG_KEY_MUSIC_ROOT }
            ?.config_val
            ?.takeIf { it.isNotBlank() }

    private data class TaggedFile(
        val path: Path,
        val ext: String,
        val tags: AudioTagReader.AudioTags
    )
}
