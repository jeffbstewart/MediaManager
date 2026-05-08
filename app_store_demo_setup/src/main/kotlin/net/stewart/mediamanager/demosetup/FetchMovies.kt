package net.stewart.mediamanager.demosetup

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.notExists

/**
 * Populates `<demoMedia>/movies/<Title (Year)>/<target_file>` from
 * the curated [fixtures/movies.tsv]. For each TSV row:
 *
 *   1. Fetch the archive.org metadata API for the item id.
 *   2. Walk the `files[]` array, picking the largest file whose
 *      `format` looks like an MP4 (originals like `MPEG4` first,
 *      derivatives like `512Kb MPEG4` / `h.264 IA` as a fallback)
 *      AND whose name ends in `.mp4`.
 *   3. Stream-download the picked file to a `.raw` sibling.
 *   4. Run ffmpeg with `-c:v libx264 -c:a aac -movflags +faststart`
 *      so the on-disk MP4 is ready for direct browser streaming.
 *      The transcode buddy doesn't have to re-encode these.
 *   5. Drop the `.raw` once ffmpeg succeeds.
 *
 * Idempotent — rows whose target already exists at non-zero size are
 * skipped. Re-run safe; if you edit the TSV, the next run picks up
 * additions without re-downloading anything.
 *
 * Loud failures: a dark item, missing MP4, ffmpeg non-zero exit, or
 * any non-2xx HTTP all throw and surface to the user. Skipping
 * silently would let a demo refresh think it succeeded with half a
 * library.
 */
internal object FetchMovies {

    fun run(demoMedia: Path) {
        requireFfmpeg()
        val fixtures = Tsv.locateFixtures().resolve("movies.tsv")
        if (fixtures.notExists()) {
            error("missing fixtures file: $fixtures")
        }
        val rows = Tsv.read(fixtures)
        val destRoot = demoMedia.resolve("movies").also { Files.createDirectories(it) }

        var processed = 0
        var skipped = 0
        var fetched = 0
        for (row in rows) {
            val archiveId = row["archive_id"].orEmpty()
            val title     = row["title"].orEmpty()
            val year      = row["year"].orEmpty()
            val target    = row["target_file"].orEmpty()
            require(archiveId.isNotEmpty() && title.isNotEmpty() && year.isNotEmpty() && target.isNotEmpty()) {
                "incomplete movies.tsv row: $row"
            }
            processed++

            val titleFolder = destRoot.resolve("$title ($year)")
            val finalDest   = titleFolder.resolve(target)

            if (finalDest.exists() && Files.size(finalDest) > 0L) {
                println("SKIP   $title ($year) — already at $finalDest")
                skipped++
                continue
            }

            println("FETCH  $title ($year) [archive.org/$archiveId]")
            val pick = pickBestMp4(archiveId)
            println("         picked ${pick.name} (${pick.size} bytes)")

            val raw = titleFolder.resolve(".fetch.raw")
            Files.createDirectories(titleFolder)
            Files.deleteIfExists(raw)

            val downloadUrl = "https://archive.org/download/$archiveId/${urlEncodePathSegment(pick.name)}"
            Http.download(downloadUrl, raw)

            println("         ffmpeg -> $target")
            runFfmpeg(raw, finalDest)
            Files.deleteIfExists(raw)
            println("OK     $finalDest")
            fetched++
        }

        println()
        println("Done — $processed row(s); $fetched downloaded, $skipped already present.")
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

        // Score each entry. Originals (source=original) preferred over
        // derivatives so we re-encode from the source whenever possible.
        // Within originals, larger = better (proxy for the actual
        // feature vs. trailers / clips).
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
        // URLEncoder is form-encoder (% encodes + space → +). For a
        // path segment we want spaces as %20.
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

    private fun runFfmpeg(input: Path, output: Path) {
        val pb = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "warning", "-y",
            "-i", input.toString(),
            "-c:v", "libx264", "-preset", "medium", "-crf", "22",
            "-c:a", "aac", "-b:a", "160k",
            "-movflags", "+faststart",
            output.toString(),
        ).redirectErrorStream(true)
        val proc = pb.start()
        // Stream stdout to ours so the user sees progress.
        proc.inputStream.bufferedReader().lineSequence().forEach { println("         ffmpeg: $it") }
        val exit = proc.waitFor()
        if (exit != 0) error("ffmpeg exited $exit on $input")
    }

}
