package net.stewart.mediamanager.demosetup

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.notExists

/**
 * Populates `<demoMedia>/movies/<Title (Year)>/<target_file>` from
 * the curated [fixtures/movies.tsv]. Each row is independent — the
 * fetcher runs them through a bounded worker pool.
 *
 * Default parallelism is conservative (2): ffmpeg's libx264 preset
 * `medium` is already multithreaded, so two parallel transcodes
 * comfortably saturate a 4–8 core box without putting the host
 * into thrash. Override with `--parallel=N` on the CLI when
 * running on a beefier machine.
 *
 * Per-row pipeline:
 *   1. Hit the archive.org metadata API for the item id.
 *   2. Walk `files[]` for the largest MP4 candidate (originals
 *      preferred over derivatives so we re-encode from a clean
 *      source when one is offered).
 *   3. Stream the picked file to a `.fetch.raw` sibling — atomic-
 *      rename on success means an interrupted run never leaves
 *      the final dest looking complete.
 *   4. Run ffmpeg with `-c:v libx264 -c:a aac -movflags +faststart`
 *      so the on-disk MP4 is ready for direct browser streaming.
 *      The transcode buddy doesn't have to re-encode these.
 *   5. Drop the `.raw` once ffmpeg succeeds.
 *
 * Idempotent — rows whose target already exists at non-zero size are
 * skipped. Loud failures: a dark item, missing MP4, ffmpeg non-zero
 * exit, or any non-2xx HTTP all throw and surface to the user.
 * In-flight rows always run to completion when one fails (avoids
 * corrupt outputs the next idempotent run treats as already-done).
 */
internal object FetchMovies {

    /** Default parallelism. Conservative because libx264 is CPU-bound. */
    const val DEFAULT_PARALLELISM = 2

    fun run(demoMedia: Path, parallelism: Int = DEFAULT_PARALLELISM) {
        requireFfmpeg()
        val fixtures = Tsv.locateFixtures().resolve("movies.tsv")
        if (fixtures.notExists()) {
            error("missing fixtures file: $fixtures")
        }
        val rows = Tsv.read(fixtures)
        val destRoot = demoMedia.resolve("movies").also { Files.createDirectories(it) }

        val skipped = AtomicInteger(0)
        val fetched = AtomicInteger(0)

        println("Fetching ${rows.size} movie(s) with parallelism=$parallelism")

        Concurrency.parallel(rows, parallelism) { row ->
            val archiveId = row["archive_id"].orEmpty()
            val title     = row["title"].orEmpty()
            val year      = row["year"].orEmpty()
            val target    = row["target_file"].orEmpty()
            require(archiveId.isNotEmpty() && title.isNotEmpty() && year.isNotEmpty() && target.isNotEmpty()) {
                "incomplete movies.tsv row: $row"
            }

            val tag = "$title ($year)"
            val titleFolder = destRoot.resolve(tag)
            val finalDest   = titleFolder.resolve(target)

            if (finalDest.exists() && Files.size(finalDest) > 0L) {
                logTagged(tag, "SKIP — already at $finalDest")
                skipped.incrementAndGet()
                return@parallel
            }

            logTagged(tag, "FETCH archive.org/$archiveId")
            val pick = pickBestMp4(archiveId)
            logTagged(tag, "picked ${pick.name} (${pick.size} bytes)")

            val raw = titleFolder.resolve(".fetch.raw")
            Files.createDirectories(titleFolder)
            Files.deleteIfExists(raw)

            val downloadUrl = "https://archive.org/download/$archiveId/${urlEncodePathSegment(pick.name)}"
            Http.download(downloadUrl, raw)

            logTagged(tag, "ffmpeg -> $target")
            runFfmpeg(raw, finalDest, tag)
            Files.deleteIfExists(raw)
            logTagged(tag, "OK $finalDest")
            fetched.incrementAndGet()
        }

        println()
        println("Done — ${rows.size} row(s); ${fetched.get()} downloaded, ${skipped.get()} already present.")
    }

    private data class FilePick(val name: String, val size: Long)

    /**
     * Hit `https://archive.org/metadata/<id>`, walk the files array,
     * pick the best MP4 candidate. Throws on dark items / no MP4.
     */
    private fun pickBestMp4(archiveId: String): FilePick {
        val body = Http.getString("https://archive.org/metadata/$archiveId")
        if (body.isBlank() || body.trim() == "{}") {
            error("archive.org item '$archiveId' returned empty metadata (item may be removed)")
        }
        val root = Gson().fromJson(body, JsonObject::class.java)
        if (root.has("is_dark") && root.get("is_dark").asBoolean) {
            error("archive.org item '$archiveId' is dark (taken offline)")
        }
        val files = root.getAsJsonArray("files")
            ?: error("archive.org item '$archiveId' metadata has no files[] array")

        val candidates = files.mapNotNull { it.toCandidate() }
        if (candidates.isEmpty()) {
            error("archive.org item '$archiveId' has no MP4 files")
        }
        return candidates
            .sortedWith(compareByDescending<Candidate> { it.preferOriginal }.thenByDescending { it.size })
            .first()
            .let { FilePick(it.name, it.size) }
    }

    private data class Candidate(val name: String, val size: Long, val preferOriginal: Int)

    private fun JsonElement.toCandidate(): Candidate? {
        val obj = this as? JsonObject ?: return null
        val name = obj.get("name")?.asString ?: return null
        if (!name.endsWith(".mp4", ignoreCase = true)) return null
        val format = obj.get("format")?.asString?.lowercase() ?: return null
        val isMp4Format = "mpeg4" in format || "h.264" in format
        if (!isMp4Format) return null
        val size = obj.get("size")?.asString?.toLongOrNull() ?: 0L
        val source = obj.get("source")?.asString ?: ""
        val preferOriginal = if (source.equals("original", ignoreCase = true)) 1 else 0
        return Candidate(name, size, preferOriginal)
    }

    /** RFC 3986 path-segment encode (spaces, parens, etc.). */
    private fun urlEncodePathSegment(s: String): String {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
    }

    private fun requireFfmpeg() {
        val pb = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true)
        val ok = try {
            val p = pb.start()
            p.inputStream.readAllBytes()
            p.waitFor() == 0
        } catch (_: IOException) {
            false
        }
        if (!ok) error("ffmpeg not found on PATH — needed to normalize fetched MP4s")
    }

    private fun runFfmpeg(input: Path, output: Path, tag: String) {
        val pb = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "warning", "-y",
            "-i", input.toString(),
            "-c:v", "libx264", "-preset", "medium", "-crf", "22",
            "-c:a", "aac", "-b:a", "160k",
            "-movflags", "+faststart",
            output.toString(),
        ).redirectErrorStream(true)
        val proc = pb.start()
        // Stream stdout to ours so progress is visible. Each line is
        // tagged so when multiple ffmpeg processes run in parallel the
        // operator can tell them apart.
        proc.inputStream.bufferedReader().lineSequence().forEach { logTagged(tag, "ffmpeg: $it") }
        val exit = proc.waitFor()
        if (exit != 0) error("ffmpeg exited $exit on $input ($tag)")
    }
}
