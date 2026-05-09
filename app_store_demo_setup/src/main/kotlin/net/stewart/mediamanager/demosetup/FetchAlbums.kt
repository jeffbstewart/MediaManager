package net.stewart.mediamanager.demosetup

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.notExists

/**
 * archive.org → `<demoMedia>/music/<Artist>/<Album>/<NN-Title>.mp3`.
 *
 * Per row in `fixtures/albums.tsv`:
 *   1. Hit the archive.org metadata API.
 *   2. Filter `files[]` to entries whose `format` is `"VBR MP3"`
 *      (the per-track derivative the IA produces from the original
 *      24-bit FLAC). One per audio track.
 *   3. Sort by the `track` field (numeric prefix; `"01"`, `"1"`, or
 *      `"1/12"` are all handled by taking leading digits).
 *   4. Stream each track to disk under
 *      `<artist>/<album>/<NN>-<sanitized-title>.mp3`. Track number
 *      derived from the file's `track` metadata; falls back to the
 *      array order if the field is missing.
 *
 * The MusicScannerAgent walks `music_root_path` for audio files and
 * picks up id3 tags from each MP3 — so the directory layout is
 * really for human readability, not server correctness.
 *
 * Parallelism: a single shared work pool of size `parallelism` runs
 * **track downloads** in parallel — flattened across albums, not
 * per-album. Bach Goldberg has 32 tracks; without flattening, one
 * worker would chew through that album sequentially while the
 * others starved on 1-track 78rpm items. Default 6.
 *
 * The metadata API calls (one per album) run in parallel up front
 * to compute the flat track list before downloads start. Faster
 * cold-start than a row-by-row loop that interleaves metadata + DL.
 *
 * Idempotent — track files already on disk at non-zero size are
 * skipped. Re-running an item after a new track has been added on
 * the archive.org side picks up only the new file.
 *
 * Note on sources: Musopen used to be the natural home for PD
 * classical recordings, but its catalog is now login-gated and the
 * download endpoints reject non-browser clients. archive.org's
 * 78rpm + classical-recording collections cover the same ground for
 * pre-1929 recordings (and several later rights-cleared ones).
 */
internal object FetchAlbums {

    const val DEFAULT_PARALLELISM = 6

    fun run(demoMedia: Path, parallelism: Int = DEFAULT_PARALLELISM) {
        val fixtures = Tsv.locateFixtures().resolve("albums.tsv")
        if (fixtures.notExists()) {
            error("missing fixtures file: $fixtures")
        }
        val rows = Tsv.read(fixtures)
        val destRoot = demoMedia.resolve("music").also { Files.createDirectories(it) }

        // Phase 1: parallel metadata + flat track-job assembly.
        println("Fetching ${rows.size} album metadata in parallel (parallelism=$parallelism)")
        val jobs = mutableListOf<TrackJob>()
        val jobsLock = Any()
        Concurrency.parallel(rows, parallelism) { row ->
            val archiveId = row["archive_id"].orEmpty()
            val artist    = row["artist"].orEmpty()
            val album     = row["album"].orEmpty()
            require(archiveId.isNotEmpty() && artist.isNotEmpty() && album.isNotEmpty()) {
                "incomplete albums.tsv row: $row"
            }

            val tag = "$artist - $album"
            val albumDir = destRoot.resolve(sanitizePathSegment(artist))
                .resolve(sanitizePathSegment(album))
            Files.createDirectories(albumDir)

            val tracks = listMp3Tracks(archiveId)
            if (tracks.isEmpty()) {
                error("archive.org item '$archiveId' has no VBR MP3 files")
            }
            logTagged(tag, "${tracks.size} track(s) [archive.org/$archiveId]")

            val rowJobs = tracks.mapIndexed { idx, track ->
                val trackNum = (track.trackNumber ?: (idx + 1)).coerceAtLeast(1)
                val title = track.title?.takeIf { it.isNotBlank() }
                    ?: track.fileName.substringBeforeLast('.', track.fileName)
                val destName = "%02d-%s.mp3".format(trackNum, sanitizePathSegment(title))
                TrackJob(
                    tag = tag,
                    archiveId = archiveId,
                    fileName = track.fileName,
                    trackNum = trackNum,
                    title = title,
                    dest = albumDir.resolve(destName),
                )
            }
            synchronized(jobsLock) { jobs.addAll(rowJobs) }
        }

        // Phase 2: parallel track downloads, flat across all albums.
        println()
        println("Downloading ${jobs.size} track(s) with parallelism=$parallelism")
        val skipped = AtomicInteger(0)
        val fetched = AtomicInteger(0)
        Concurrency.parallel(jobs, parallelism) { job ->
            if (job.dest.exists() && Files.size(job.dest) > 0L) {
                skipped.incrementAndGet()
                return@parallel
            }
            val url = "https://archive.org/download/${job.archiveId}/${urlEncodePathSegment(job.fileName)}"
            logTagged(job.tag, "[${"%02d".format(job.trackNum)}] ${job.title}")
            Http.download(url, job.dest)
            fetched.incrementAndGet()
        }

        println()
        println("Done — ${rows.size} album(s); ${fetched.get()} track(s) downloaded, ${skipped.get()} already present.")
    }

    private data class TrackFile(
        val fileName: String,
        val title: String?,
        val trackNumber: Int?,
    )

    private data class TrackJob(
        val tag: String,
        val archiveId: String,
        val fileName: String,
        val trackNum: Int,
        val title: String,
        val dest: Path,
    )

    /** Hit the archive.org metadata API and project files[] -> MP3 track records. */
    private fun listMp3Tracks(archiveId: String): List<TrackFile> {
        val body = Http.getString("https://archive.org/metadata/$archiveId")
        if (body.isBlank() || body.trim() == "{}") {
            error("archive.org item '$archiveId' returned empty metadata")
        }
        val root = Gson().fromJson(body, JsonObject::class.java)
        if (root.has("is_dark") && root.get("is_dark").asBoolean) {
            error("archive.org item '$archiveId' is dark (taken offline)")
        }
        val files = root.getAsJsonArray("files")
            ?: error("archive.org item '$archiveId' metadata has no files[] array")

        val mp3s = files.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val format = obj.get("format")?.asString ?: return@mapNotNull null
            if (!format.equals("VBR MP3", ignoreCase = true)) return@mapNotNull null
            val name = obj.get("name")?.asString ?: return@mapNotNull null
            val title = obj.get("title")?.asString
            // `track` may be "01", "1", or "1/12"; take leading digits.
            val trackRaw = obj.get("track")?.asString
            val trackNum = trackRaw?.takeWhile { it.isDigit() }?.toIntOrNull()
            TrackFile(fileName = name, title = title, trackNumber = trackNum)
        }
        return mp3s.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
    }

    /**
     * Strip path-illegal characters so an archive name like "AC/DC"
     * doesn't escape the album dir. Conservative — only allow
     * alphanumerics, common punctuation, spaces, hyphens, underscores.
     * Replaces forbidden characters with `_`.
     */
    private fun sanitizePathSegment(s: String): String {
        val cleaned = buildString(s.length) {
            for (c in s) {
                when {
                    c.isLetterOrDigit() -> append(c)
                    c in " -_.,()'!&" -> append(c)
                    else -> append('_')
                }
            }
        }.trim('.', ' ')
        return cleaned.ifBlank { "_" }
    }

    /** RFC 3986 path-segment encode (spaces, parens, quotes, etc.). */
    private fun urlEncodePathSegment(s: String): String {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
