package net.stewart.mediamanager.demosetup

import com.google.gson.Gson
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
 * archive.org → `<demoMedia>/movies/<Series> (<Year>)/Season <SS>/<renamed>.mp4`.
 *
 * Each row in `fixtures/tv.tsv` is one season of one series, mapped to a
 * single archive.org item that ships every episode as a separate MP4.
 * The fetcher:
 *
 *   1. Hits the archive.org metadata API for the item id.
 *   2. Filters `files[]` to MP4 entries (originals or 512Kb-MPEG4
 *      derivatives, same scoring as FetchMovies).
 *   3. Parses the file name with the per-row `ep_pattern` regex —
 *      capture groups must be (episode_num, episode_title). Lets
 *      different upload conventions per series (the Sherlock 1954
 *      ships as "Sherlock Holmes 01 The Case of the Cunningham
 *      Heritage.mp4"; other series use different layouts).
 *   4. Renames each episode to the format
 *      `<Series> - S<SS>E<EE> - <Episode Title>.mp4`. That's exactly
 *      what TranscodeFileParser.parseTvEpisodeFile expects, so the
 *      server's NAS scanner picks the episode up with no manual
 *      tagging.
 *   5. Streams the source MP4 to a `.fetch.raw` sibling.
 *   6. Runs ffmpeg with the same H.264 + AAC + faststart settings as
 *      the movie fetcher so the episode is browser-streamable
 *      without buddy intervention.
 *
 * NasScannerService classifies any top-level dir under nas_root_path
 * with nested video files (depth >= 2) as TV — so the
 * `<Series> (<Year>)/Season <SS>/...mp4` layout sorts itself
 * automatically alongside flat-layout movie folders.
 *
 * Default parallelism 2: like the movies fetcher, ffmpeg dominates
 * runtime, and libx264 already multithreads. Override with
 * `--parallel=N`.
 *
 * Idempotent — episodes already on disk at non-zero size are
 * skipped. Loud failures: dark items, no usable MP4s, bad regex
 * pattern, ffmpeg non-zero exit.
 */
internal object FetchTvSeries {

    /** Default parallelism. Conservative because libx264 is CPU-bound. */
    const val DEFAULT_PARALLELISM = 2

    fun run(demoMedia: Path, parallelism: Int = DEFAULT_PARALLELISM) {
        requireFfmpeg()
        val fixtures = Tsv.locateFixtures().resolve("tv.tsv")
        if (fixtures.notExists()) {
            error("missing fixtures file: $fixtures")
        }
        val rows = Tsv.read(fixtures)
        // Same scan root as movies — both share nas_root_path. Folder
        // structure (flat vs nested) drives the TV/MOVIE classification.
        val nasRoot = demoMedia.resolve("movies").also { Files.createDirectories(it) }

        // Phase 1: parallel metadata fetch + work-list assembly.
        println("Resolving ${rows.size} series-season(s) with parallelism=$parallelism")
        val jobs = mutableListOf<EpisodeJob>()
        val jobsLock = Any()
        Concurrency.parallel(rows, parallelism) { row ->
            val archiveId  = row["archive_id"].orEmpty()
            val seriesName = row["series_name"].orEmpty()
            val year       = row["year"].orEmpty()
            val season     = row["season"].orEmpty()
            val epPattern  = row["ep_pattern"].orEmpty()
            require(archiveId.isNotEmpty() && seriesName.isNotEmpty() && year.isNotEmpty()
                && season.isNotEmpty() && epPattern.isNotEmpty()) {
                "incomplete tv.tsv row: $row"
            }
            val seasonNum = season.toIntOrNull()
                ?: error("season '$season' must be numeric")
            val regex = Regex(epPattern)

            val seasonDir = nasRoot.resolve("$seriesName ($year)")
                .resolve("Season ${"%02d".format(seasonNum)}")
            Files.createDirectories(seasonDir)

            val tag = "$seriesName S${"%02d".format(seasonNum)}"
            val mp4s = listMp4Files(archiveId)
            if (mp4s.isEmpty()) {
                error("archive.org item '$archiveId' has no MP4 files")
            }

            val matched = mp4s.mapNotNull { file ->
                val m = regex.matchEntire(file.name) ?: return@mapNotNull null
                require(m.groupValues.size >= 3) {
                    "ep_pattern '$epPattern' must capture (ep_num, ep_title); got ${m.groupValues}"
                }
                val epNum = m.groupValues[1].toInt()
                val epTitle = m.groupValues[2].trim()
                val destName = sanitizeFileName(
                    "$seriesName - S${"%02d".format(seasonNum)}E${"%02d".format(epNum)} - $epTitle.mp4"
                )
                EpisodeJob(
                    tag = tag,
                    archiveId = archiveId,
                    sourceFile = file,
                    epNum = epNum,
                    epTitle = epTitle,
                    seasonDir = seasonDir,
                    destName = destName,
                )
            }
            if (matched.isEmpty()) {
                error("archive.org item '$archiveId' had ${mp4s.size} MP4(s) but " +
                    "ep_pattern '$epPattern' matched none of them. First name: '${mp4s.first().name}'")
            }
            logTagged(tag, "${matched.size} episode(s) [archive.org/$archiveId]")
            synchronized(jobsLock) { jobs.addAll(matched) }
        }

        // Phase 2: parallel download + ffmpeg per episode.
        println()
        println("Fetching ${jobs.size} episode(s) with parallelism=$parallelism")
        val skipped = AtomicInteger(0)
        val fetched = AtomicInteger(0)
        Concurrency.parallel(jobs, parallelism) { job ->
            val finalDest = job.seasonDir.resolve(job.destName)
            if (finalDest.exists() && Files.size(finalDest) > 0L) {
                logTagged(job.tag, "SKIP E${"%02d".format(job.epNum)} — already at $finalDest")
                skipped.incrementAndGet()
                return@parallel
            }

            val raw = job.seasonDir.resolve(".fetch.E${"%02d".format(job.epNum)}.raw")
            Files.deleteIfExists(raw)

            val downloadUrl = "https://archive.org/download/${job.archiveId}/${urlEncodePathSegment(job.sourceFile.name)}"
            logTagged(job.tag, "FETCH E${"%02d".format(job.epNum)}: ${job.epTitle}")
            Http.download(downloadUrl, raw)

            logTagged(job.tag, "ffmpeg -> ${job.destName}")
            runFfmpeg(raw, finalDest, "${job.tag}E${"%02d".format(job.epNum)}")
            Files.deleteIfExists(raw)
            logTagged(job.tag, "OK E${"%02d".format(job.epNum)}")
            fetched.incrementAndGet()
        }

        println()
        println("Done — ${rows.size} season(s); ${fetched.get()} episode(s) downloaded, ${skipped.get()} already present.")
    }

    private data class FilePick(val name: String, val size: Long, val source: String)
    private data class EpisodeJob(
        val tag: String,
        val archiveId: String,
        val sourceFile: FilePick,
        val epNum: Int,
        val epTitle: String,
        val seasonDir: Path,
        val destName: String,
    )

    /** Walk the metadata files[] for every MP4. Sorted by size desc — */
    /** the original (largest) file wins when an item ships originals + */
    /** derivatives for the same logical track. */
    private fun listMp4Files(archiveId: String): List<FilePick> {
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

        val byName = mutableMapOf<String, FilePick>()
        for (el in files) {
            val obj = el as? JsonObject ?: continue
            val name = obj.get("name")?.asString ?: continue
            if (!name.endsWith(".mp4", ignoreCase = true)) continue
            val format = obj.get("format")?.asString?.lowercase() ?: continue
            if ("mpeg4" !in format && "h.264" !in format) continue
            val size = obj.get("size")?.asString?.toLongOrNull() ?: 0L
            val source = obj.get("source")?.asString ?: ""
            // Prefer originals over derivatives at the same name (they
            // share base names with extensions); pick the largest entry
            // among same-named candidates.
            val existing = byName[name]
            if (existing == null || size > existing.size) {
                byName[name] = FilePick(name, size, source)
            }
        }
        return byName.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Strip Windows-illegal characters and shorten so the destination
     * filename never breaks NTFS limits. Replaces forbidden characters
     * (`<>:"/\|?*`) with an underscore.
     */
    private fun sanitizeFileName(s: String): String {
        return buildString(s.length) {
            for (c in s) {
                when {
                    c in "<>:\"/\\|?*" -> append('_')
                    c.code < 32 -> { /* drop control chars */ }
                    else -> append(c)
                }
            }
        }.trim()
    }

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
        proc.inputStream.bufferedReader().lineSequence().forEach { logTagged(tag, "ffmpeg: $it") }
        val exit = proc.waitFor()
        if (exit != 0) error("ffmpeg exited $exit on $input ($tag)")
    }
}
