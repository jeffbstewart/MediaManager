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

        /**
         * Max UNMATCHED rows retried per cycle. Each retry costs 1–3 MB
         * requests; capping at 60 keeps the burst under MB's 1/sec ceiling
         * for a single cycle while still making steady progress.
         */
        private const val REPROCESS_LIMIT = 60

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

        // Before walking the filesystem, retry resolution on previously-staged
        // UNMATCHED rows. Tag-only identifiers (UPC / catalog# / ISRC) can
        // resolve now that MusicBrainzService has the fallback tiers, even if
        // the originating file hasn't changed.
        reprocessUnmatched()

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
     * Process one directory's worth of audio files. Resolution tiers, most
     * authoritative first — the first tier that produces a release MBID
     * wins:
     *
     *   1. `MUSICBRAINZ_ALBUMID` tag (MBID — unambiguous).
     *   2. `UPC` / `BARCODE` tag → MB barcode search. Authoritative for
     *      physical pressings; rippers that don't write MBIDs still copy
     *      the UPC through from AccurateRip.
     *   3. `CATALOGNUMBER` (+ optional `LABEL`) → MB catno search. The
     *      label catalog # is close to unique across a label's history.
     *   4. Per-track `ISRC` → MB ISRC lookup, projecting recording → release.
     *   5. `ALBUM ARTIST` + `ALBUM` fuzzy search — lowest confidence; only
     *      used when nothing more specific is present.
     *
     *  If none resolves, files park in [UnmatchedAudio] for admin triage.
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

        val resolvedReleaseId = resolveReleaseIdForDir(tagged)

        var linked = 0
        var staged = 0

        if (resolvedReleaseId != null) {
            val title = findOrIngestTitleByRelease(resolvedReleaseId)
            if (title != null) {
                val tracks = Track.findAll().filter { it.title_id == title.id }
                for (tf in tagged) {
                    if (linkTagged(tf, tracks)) {
                        linked++
                        knownTrackPaths += tf.path.toString()
                        populateEmbeddedArtIfMissing(title, tf.path.toFile())
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
     * Walk the directory-level tier ladder and return the release MBID the
     * files resolve to, or null when nothing matched.
     */
    private fun resolveReleaseIdForDir(tagged: List<TaggedFile>): String? {
        val dirHint = tagged.firstOrNull()?.path?.parent?.toString() ?: "(unknown dir)"

        // Tier 1: dominant MBID across tagged files.
        val dominantMbid = tagged
            .mapNotNull { it.tags.musicBrainzReleaseId }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }?.key
        if (dominantMbid != null) {
            log.info("Dir {}: tier=MBID hit={}", dirHint, dominantMbid)
            return dominantMbid
        }

        // Tier 2: dominant UPC via barcode search.
        val dominantUpc = tagged
            .mapNotNull { it.tags.upc?.takeIf { s -> s.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }?.key
        if (dominantUpc != null) {
            val result = musicBrainz.lookupByBarcode(dominantUpc)
            when (result) {
                is MusicBrainzResult.Success -> {
                    log.info("Dir {}: tier=UPC({}) hit={}", dirHint, dominantUpc, result.release.musicBrainzReleaseId)
                    return result.release.musicBrainzReleaseId
                }
                is MusicBrainzResult.NotFound ->
                    log.info("Dir {}: tier=UPC({}) MB returned no releases", dirHint, dominantUpc)
                is MusicBrainzResult.Error ->
                    log.warn("Dir {}: tier=UPC({}) MB error: {} (rateLimited={})",
                        dirHint, dominantUpc, result.message, result.rateLimited)
            }
        }

        // Tier 3: dominant catalog# (scoped by dominant label when present).
        val dominantCatno = tagged
            .mapNotNull { it.tags.catalogNumber?.takeIf { s -> s.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }?.key
        if (dominantCatno != null) {
            val dominantLabel = tagged
                .mapNotNull { it.tags.label?.takeIf { s -> s.isNotBlank() } }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }?.key
            val candidates = musicBrainz.searchByCatalogNumber(dominantCatno, dominantLabel)
            if (candidates.isNotEmpty()) return candidates.first()
        }

        // Tier 4: per-track ISRC. Try the first few; stop at the first
        // release MBID any recording points to.
        val isrcs = tagged.mapNotNull { it.tags.isrc?.takeIf { s -> s.isNotBlank() } }.distinct()
        for (isrc in isrcs.take(3)) {
            val candidates = musicBrainz.searchByIsrc(isrc)
            if (candidates.isNotEmpty()) return candidates.first()
        }

        // Tier 5: album-artist + album fuzzy search. Pick the most common
        // combo — tracks on the same album should all share these.
        val artistAlbumPair = tagged
            .mapNotNull { tf ->
                val artist = tf.tags.albumArtist?.takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
                val album = tf.tags.album?.takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
                artist to album
            }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }?.key
        if (artistAlbumPair != null) {
            val (artist, album) = artistAlbumPair
            val candidates = musicBrainz.searchReleaseByArtistAndAlbum(artist, album)
            if (candidates.isNotEmpty()) return candidates.first()
        }

        return null
    }

    /**
     * Retry resolution on previously-staged UNMATCHED rows using the
     * identifiers we captured at staging time. On success, delete the
     * unmatched row and let the next filesystem walk re-discover the
     * file (which will then link to the now-catalogued track).
     *
     * We only re-query MB — this is one cycle's housekeeping, not a
     * filesystem walk. Bounded to [REPROCESS_LIMIT] per cycle to keep MB
     * request budget reasonable (1 req/sec means 60/min max burst).
     */
    private fun reprocessUnmatched() {
        val allUnmatched = UnmatchedAudio.findAll()
            .filter { it.match_status == UnmatchedAudioStatus.UNMATCHED.name }
        if (allUnmatched.isEmpty()) {
            log.info("Unmatched-audio reprocess: START — no UNMATCHED rows, skipping")
            log.info("Unmatched-audio reprocess: END — nothing to do")
            return
        }
        val rows = allUnmatched.take(REPROCESS_LIMIT)
        val startMillis = System.currentTimeMillis()
        log.info("Unmatched-audio reprocess: START — {} row(s) pending, processing {} this cycle (cap={})",
            allUnmatched.size, rows.size, REPROCESS_LIMIT)
        var resolved = 0
        var noResolve = 0
        var noTitle = 0
        var noLink = 0
        for (row in rows) {
            if (!running.get()) break
            val releaseMbid = resolveReleaseIdForRow(row)
            if (releaseMbid == null) {
                noResolve++
                continue
            }
            try {
                val title = findOrIngestTitleByRelease(releaseMbid)
                if (title == null) {
                    noTitle++
                    log.warn("Reprocess {}: resolved MBID {} but title ingest failed",
                        row.file_path, releaseMbid)
                    continue
                }
                val tracks = Track.findAll().filter { it.title_id == title.id }
                val tf = TaggedFile(
                    path = Path.of(row.file_path),
                    ext = row.file_path.substringAfterLast('.', "").lowercase(),
                    tags = AudioTagReader.AudioTags(
                        title = row.parsed_title,
                        album = row.parsed_album,
                        albumArtist = row.parsed_album_artist,
                        trackArtist = row.parsed_track_artist,
                        trackNumber = row.parsed_track_number,
                        discNumber = row.parsed_disc_number,
                        year = null,
                        durationSeconds = row.parsed_duration_seconds,
                        musicBrainzReleaseId = releaseMbid,
                        musicBrainzReleaseGroupId = row.parsed_mb_release_group_id,
                        musicBrainzRecordingId = row.parsed_mb_recording_id,
                        musicBrainzArtistId = null,
                        upc = row.parsed_upc,
                        isrc = row.parsed_isrc,
                        catalogNumber = row.parsed_catalog_number,
                        label = row.parsed_label
                    )
                )
                if (linkTagged(tf, tracks)) {
                    row.delete()
                    resolved++
                    populateEmbeddedArtIfMissing(title, tf.path.toFile())
                    log.info("Reprocessed unmatched audio: {} → title {} via MBID {}",
                        row.file_path, title.id, releaseMbid)
                } else {
                    noLink++
                    // linkTagged already logged the specific reason; this is
                    // just the roll-up marker so summary counts line up.
                    log.info("Reprocess {}: resolved MBID {} → title {} but no track linked",
                        row.file_path, releaseMbid, title.id)
                }
            } catch (e: Exception) {
                log.warn("Reprocess failed for {}: {}", row.file_path, e.message, e)
            }
        }
        val elapsedSec = (System.currentTimeMillis() - startMillis) / 1000.0
        log.info("Unmatched-audio reprocess: END — resolved={} noResolve={} noTitle={} noLink={} " +
            "remaining={} elapsed={}s",
            resolved, noResolve, noTitle, noLink,
            (allUnmatched.size - resolved), "%.1f".format(elapsedSec))
    }

    private fun resolveReleaseIdForRow(row: UnmatchedAudio): String? {
        val who = row.file_path
        row.parsed_mb_release_id?.takeIf { it.isNotBlank() }?.let {
            log.info("Resolve {}: tier=MBID hit={}", who, it)
            return it
        }

        row.parsed_upc?.takeIf { it.isNotBlank() }?.let { upc ->
            val r = musicBrainz.lookupByBarcode(upc)
            when (r) {
                is MusicBrainzResult.Success -> {
                    log.info("Resolve {}: tier=UPC({}) hit={}", who, upc, r.release.musicBrainzReleaseId)
                    return r.release.musicBrainzReleaseId
                }
                is MusicBrainzResult.NotFound ->
                    log.info("Resolve {}: tier=UPC({}) MB returned no releases for that barcode", who, upc)
                is MusicBrainzResult.Error ->
                    log.warn("Resolve {}: tier=UPC({}) MB error: {} (rateLimited={})",
                        who, upc, r.message, r.rateLimited)
            }
        }
        row.parsed_catalog_number?.takeIf { it.isNotBlank() }?.let { catno ->
            val hits = musicBrainz.searchByCatalogNumber(catno, row.parsed_label)
            if (hits.isNotEmpty()) {
                log.info("Resolve {}: tier=Catno({}/{}) hit={} (of {} candidates)",
                    who, catno, row.parsed_label ?: "-", hits.first(), hits.size)
                return hits.first()
            } else {
                log.info("Resolve {}: tier=Catno({}/{}) no hits", who, catno, row.parsed_label ?: "-")
            }
        }
        row.parsed_isrc?.takeIf { it.isNotBlank() }?.let { isrc ->
            val hits = musicBrainz.searchByIsrc(isrc)
            if (hits.isNotEmpty()) {
                log.info("Resolve {}: tier=ISRC({}) hit={} (of {} candidates)",
                    who, isrc, hits.first(), hits.size)
                return hits.first()
            } else {
                log.info("Resolve {}: tier=ISRC({}) no hits", who, isrc)
            }
        }
        val artist = row.parsed_album_artist?.takeIf { it.isNotBlank() }
        val album = row.parsed_album?.takeIf { it.isNotBlank() }
        if (artist != null && album != null) {
            val hits = musicBrainz.searchReleaseByArtistAndAlbum(artist, album)
            if (hits.isNotEmpty()) {
                log.info("Resolve {}: tier=Artist+Album({} / {}) hit={} (of {} candidates)",
                    who, artist, album, hits.first(), hits.size)
                return hits.first()
            } else {
                log.info("Resolve {}: tier=Artist+Album({} / {}) no hits", who, artist, album)
            }
        } else {
            log.info("Resolve {}: tier=Artist+Album skipped (artist='{}' album='{}')", who, artist, album)
        }
        log.warn("Resolve {}: no tier produced a release MBID (upc={}, catno={}, label={}, isrc={}, artist={}, album={})",
            who, row.parsed_upc, row.parsed_catalog_number, row.parsed_label,
            row.parsed_isrc, row.parsed_album_artist, row.parsed_album)
        return null
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
        val success = when (result) {
            is MusicBrainzResult.Success -> result
            is MusicBrainzResult.NotFound -> {
                log.warn("MusicBrainz release {} returned NotFound — can't ingest title", releaseMbid)
                return null
            }
            is MusicBrainzResult.Error -> {
                log.warn("MusicBrainz lookup for release {} failed: {} (rateLimited={})",
                    releaseMbid, result.message, result.rateLimited)
                return null
            }
        }
        // Digital edition — no UPC; the release-group key still dedupes
        // across a prior CD scan and the new rip directory, so both
        // pressings end up on the same Title.
        val ingest = MusicIngestionService.ingest(
            upc = null,
            mediaFormat = MediaFormat.AUDIO_FLAC,
            lookup = success.release,
            clock = clock
        )
        return ingest.title
    }

    /**
     * Link a tagged file to a [Track] row. Preferred: recording MBID
     * match (dBpoweramp writes these as `MUSICBRAINZ_TRACKID`). Fallback:
     * disc + track number match within the same [Title]. Sets
     * `track.file_path` and returns true on success.
     *
     * Logs the reason at INFO whenever the link can't be made, so an
     * unmatched row stuck after resolution has a trail.
     */
    private fun linkTagged(tf: TaggedFile, tracks: List<Track>): Boolean {
        val now = LocalDateTime.now()
        val recMbid = tf.tags.musicBrainzRecordingId
        val targetByMbid = if (recMbid != null) {
            tracks.firstOrNull { it.musicbrainz_recording_id == recMbid }
        } else null

        val disc = tf.tags.discNumber ?: 1
        val trackNum = tf.tags.trackNumber

        val target = targetByMbid ?: tracks.firstOrNull {
            trackNum != null && it.track_number == trackNum && it.disc_number == disc
        }

        if (target == null) {
            log.info("Link {} → no track match: recording_mbid={} disc={} track={} " +
                "({} tracks on title, track_numbers={})",
                tf.path, recMbid, disc, trackNum, tracks.size,
                tracks.map { "${it.disc_number}.${it.track_number}" })
            return false
        }

        if (target.file_path != null) {
            if (target.file_path == tf.path.toString()) {
                // Already linked to this exact file — treat as success so the
                // reprocess path can clean up the staged unmatched row.
                return true
            }
            log.info("Link {} → track {}/{} already linked to '{}', not stomping",
                tf.path, target.disc_number, target.track_number, target.file_path)
            return false
        }
        target.file_path = tf.path.toString()
        target.updated_at = now
        target.save()
        log.info("Link {} → track id={} disc={} track={} (via {})",
            tf.path, target.id, target.disc_number, target.track_number,
            if (targetByMbid != null) "recording MBID" else "disc/track")
        return true
    }

    /**
     * If [title] doesn't yet have a cached poster, extract embedded cover
     * art from [audioFile] and store it via [PosterCacheService]. Subsequent
     * calls for the same title are no-ops once `poster_cache_id` is set.
     *
     * Embedded art is preferred over the Cover Art Archive proxy because
     * CAA is often missing coverage for the specific pressing we ripped —
     * the rip itself already carries the right image.
     */
    private fun populateEmbeddedArtIfMissing(title: Title, audioFile: File) {
        if (title.poster_cache_id != null) return
        val bytes = AudioCoverExtractor.extractJpeg(audioFile) ?: return
        val stored = PosterCacheService.storeJpegBytes(title, bytes)
        if (stored != null) {
            log.info("Embedded art cached for title {} from {} ({} bytes)",
                title.id, audioFile.absolutePath, bytes.size)
        }
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
            parsed_upc = tf.tags.upc,
            parsed_isrc = tf.tags.isrc,
            parsed_catalog_number = tf.tags.catalogNumber,
            parsed_label = tf.tags.label,
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
