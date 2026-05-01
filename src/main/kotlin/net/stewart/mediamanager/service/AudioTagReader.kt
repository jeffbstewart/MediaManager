package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Reads audio-file metadata via FFprobe. Decision recorded in docs/MUSIC.md
 * (pre-M1 verifications): use FFprobe rather than adding a Java audio-tag
 * library. ffprobe ships with the FFmpeg install we already have in the
 * Docker image and its JSON output surfaces every tag we need
 * (MusicBrainz IDs, canonical title / artist / album / track / disc / date,
 * plus stream duration).
 *
 * The reader is a pure transformation over the ffprobe JSON — no DB,
 * no network, no concurrency. Swap the exec helper in tests.
 */
object AudioTagReader {

    private val log = LoggerFactory.getLogger(AudioTagReader::class.java)
    private val mapper = ObjectMapper()

    data class AudioTags(
        val title: String?,
        val album: String?,
        val albumArtist: String?,
        val trackArtist: String?,
        val trackNumber: Int?,
        val discNumber: Int?,
        val year: Int?,
        val durationSeconds: Int?,
        /** MUSICBRAINZ_ALBUMID = MB release MBID. */
        val musicBrainzReleaseId: String?,
        /** MUSICBRAINZ_RELEASEGROUPID. */
        val musicBrainzReleaseGroupId: String?,
        /** MUSICBRAINZ_TRACKID on Vorbis, `MusicBrainz Track Id` on ID3 = recording MBID. */
        val musicBrainzRecordingId: String?,
        /** MUSICBRAINZ_ARTISTID; semicolon-separated if there are multiple. First ID only. */
        val musicBrainzArtistId: String?,
        /**
         * EAN-13 / UPC barcode from the release. Vorbis `UPC` or `BARCODE`,
         * ID3 `TXXX:BARCODE` or `TXXX:UPC`. When present this is
         * authoritative for matching — MB's barcode search returns the
         * exact pressing.
         */
        val upc: String?,
        /**
         * Per-track ISRC (International Standard Recording Code). Vorbis
         * `ISRC`, ID3 `TSRC`. MB has an `/isrc/{isrc}` endpoint that
         * resolves to a recording; good fallback when UPC is missing but
         * individual tracks carry ISRCs.
         */
        val isrc: String?,
        /**
         * Label's catalog number, e.g. "CDP 593178". Vorbis
         * `CATALOGNUMBER` / `LABELNO`, ID3 `TXXX:CATALOGNUMBER`.
         */
        val catalogNumber: String?,
        /** Label / publisher name. Vorbis `LABEL` / `ORGANIZATION`, ID3 `TPUB`. */
        val label: String?,
        /**
         * Zero or more genre strings. ID3v2 `TCON`, Vorbis `GENRE`,
         * MP4 `©gen`. We split on `;`, `,`, and ` / ` because taggers
         * freely choose among them when writing multi-genre values
         * ("Rock; Pop-Rock" and "Rock, Pop-Rock" both show up). Each
         * split value is trimmed but NOT normalized — normalization is
         * the applicator's job so the raw ffprobe output stays
         * inspectable in tests.
         */
        val genres: List<String>,
        /**
         * Zero or more style strings. No standard ID3 frame — written
         * by Picard-style taggers to `TXXX:Style` (ID3), `STYLE`
         * (Vorbis), or sometimes `----:com.apple.iTunes:Style` (MP4).
         * Same multi-value splitting rules as [genres].
         */
        val styles: List<String>,
        /** Raw BPM from `TBPM` / `BPM` / `tmpo`. Integer; null if absent or unparseable. */
        val bpm: Int?,
        /**
         * Raw time signature. No standard ID3 frame — Picard writes
         * `TXXX:TIME_SIGNATURE` / Vorbis `TIMESIGNATURE`. Typically
         * null. Stored verbatim (e.g. "3/4", "4/4", "6/8") so callers
         * can render or compare as they see fit.
         */
        val timeSignature: String?
    ) {
        companion object {
            val EMPTY = AudioTags(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                emptyList(), emptyList(), null, null
            )
        }
    }

    /**
     * Reads tags from [file]. Returns [AudioTags.EMPTY] on any error — the
     * caller then parks the file in `unmatched_audio` for admin triage
     * rather than crashing a scanner run.
     */
    fun read(file: File, ffprobePath: String = "ffprobe"): AudioTags {
        if (!file.isFile) return AudioTags.EMPTY
        return readFromPathString(file.absolutePath, ffprobePath)
    }

    /**
     * Path-typed overload — primary for callers walking via NIO. The
     * caller is responsible for the existence check (`Files.isRegularFile`).
     * This is the seam that lets AlbumRescanService walk a Jimfs tree
     * and feed the path strings (which only the in-memory FS knows about)
     * straight into ffprobe via the [Subprocesses.current] fake without
     * a `Path.toFile()` round-trip that Jimfs doesn't support.
     */
    fun read(path: java.nio.file.Path, ffprobePath: String = "ffprobe"): AudioTags {
        if (!java.nio.file.Files.isRegularFile(path)) return AudioTags.EMPTY
        return readFromPathString(path.toString(), ffprobePath)
    }

    private fun readFromPathString(pathStr: String, ffprobePath: String): AudioTags {
        val stdout = runCatching { runFfprobe(ffprobePath, pathStr) }
            .onFailure { log.warn("ffprobe failed for {}: {}", pathStr, it.message) }
            .getOrNull() ?: return AudioTags.EMPTY

        return try {
            parse(stdout)
        } catch (e: Exception) {
            log.warn("ffprobe parse failed for {}: {}", pathStr, e.message)
            AudioTags.EMPTY
        }
    }

    /** Pure parse over ffprobe JSON output — exposed for tests. */
    internal fun parse(json: String): AudioTags {
        val root = mapper.readTree(json)
        val formatTags = root.path("format").path("tags")
        // FLAC vs ID3 vs M4A vary in casing — gather every tag into a
        // case-insensitive map so lookups don't care about the source format.
        val tags = collectTagsCaseInsensitive(formatTags)
        // Some containers (esp. M4A / MP4) put stream-level tags on the
        // first stream rather than the format. Merge.
        val streamTags = root.path("streams").firstOrNull()?.path("tags")
        if (streamTags != null && streamTags.isObject) {
            collectTagsCaseInsensitive(streamTags).forEach { (k, v) ->
                if (!tags.containsKey(k)) tags[k] = v
            }
        }

        val durationSec = root.path("format").textOrNull("duration")
            ?.toDoubleOrNull()?.toInt()

        return AudioTags(
            title = tags.firstValue("title"),
            album = tags.firstValue("album"),
            albumArtist = tags.firstValue("album_artist", "albumartist"),
            trackArtist = tags.firstValue("artist"),
            trackNumber = parseSlashPrefixInt(tags.firstValue("track", "tracknumber")),
            discNumber = parseSlashPrefixInt(tags.firstValue("disc", "discnumber")),
            year = tags.firstValue("date", "year", "originaldate")?.let { extractYear(it) },
            durationSeconds = durationSec,
            musicBrainzReleaseId = tags.firstValue(
                "musicbrainz_albumid",
                "musicbrainz album id",
                // dBpoweramp writes a plain "MBID" key that carries the
                // release MBID (same value across every track on the
                // release). Non-standard but common in the wild.
                "mbid"
            ),
            musicBrainzReleaseGroupId = tags.firstValue(
                "musicbrainz_releasegroupid",
                "musicbrainz release group id"
            ),
            musicBrainzRecordingId = tags.firstValue(
                "musicbrainz_trackid",
                "musicbrainz track id",
                "musicbrainz_releasetrackid",
                "musicbrainz release track id"
            ),
            musicBrainzArtistId = tags.firstValue(
                "musicbrainz_artistid",
                "musicbrainz artist id"
            )?.substringBefore(';')?.trim()?.ifBlank { null },
            // UPC is usually digits only, but some writers wrap it in spaces
            // or include a hyphenated form — cleanup handled downstream in
            // the MB lookup shim.
            upc = tags.firstValue("upc", "barcode"),
            // ISRCs are 12-character codes (e.g. "GBAYE8200051"). Some writers
            // carry a stack of ISRCs separated by `/`; take the first.
            isrc = tags.firstValue("isrc", "tsrc")?.substringBefore('/')?.trim()?.ifBlank { null },
            catalogNumber = tags.firstValue("catalognumber", "labelno", "catalog_number", "catalog"),
            label = tags.firstValue("label", "organization", "publisher"),
            genres = splitMulti(tags.firstValue("genre", "tcon")),
            // "Style" isn't a standardized tag name but several writers
            // produce it. ffprobe lowercases everything so the variants
            // collapse to the same key on our side.
            styles = splitMulti(tags.firstValue("style", "styles")),
            bpm = tags.firstValue("bpm", "tbpm", "tmpo")
                ?.toDoubleOrNull()?.toInt()?.takeIf { it in 1..999 },
            timeSignature = tags.firstValue(
                "time_signature", "timesignature", "time signature"
            )?.takeIf { TIME_SIG_PATTERN.matches(it.trim()) }?.trim()
        )
    }

    /** Matches "3/4", "4/4", "6/8", "12/8", "5/4", etc. */
    private val TIME_SIG_PATTERN = Regex("""^\d{1,2}/\d{1,2}$""")

    /** Multi-value separators taggers actually write. `/` is NOT included — it's
     *  ambiguous with "12/8" and common within single genre names ("Rock/Pop"
     *  really is one genre in some taxonomies). */
    private val MULTI_SEPARATORS = charArrayOf(';', ',')

    /**
     * Split a raw multi-value tag string on `;` / `,` and trim. Empty
     * or null input returns an empty list. Exposed to tests.
     */
    internal fun splitMulti(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(*MULTI_SEPARATORS)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /** Parse "3/12" or "3" into 3. Returns null on non-numeric input. */
    internal fun parseSlashPrefixInt(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val head = raw.substringBefore('/').trim()
        return head.toIntOrNull()
    }

    private fun runFfprobe(ffprobePath: String, pathStr: String): String? {
        // Routes through Subprocesses.current so tests can script the
        // ffprobe stdout JSON directly without a real binary on PATH.
        val result = Subprocesses.current.run(
            command = listOf(
                ffprobePath,
                "-v", "error",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                pathStr,
            ),
            timeout = java.time.Duration.ofSeconds(30),
            redirectErrorStream = false,
        )
        if (result.timedOut) {
            log.warn("ffprobe timed out on {}", pathStr)
            return null
        }
        return if (result.exitCode == 0) {
            result.stdout
        } else {
            log.warn("ffprobe exit {} on {}", result.exitCode, pathStr)
            null
        }
    }

    @Suppress("DEPRECATION")  // properties() would be the Jackson 3 replacement; fields() is what 2.x ships.
    private fun collectTagsCaseInsensitive(node: JsonNode): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        if (!node.isObject) return result
        node.fields().forEach { (k, v) ->
            if (v.isTextual) {
                // Preserve insertion order but key case-insensitively.
                val key = k.lowercase()
                if (!result.containsKey(key)) result[key] = v.asText()
            }
        }
        return result
    }

    /** First non-null value across a list of candidate keys. */
    private fun Map<String, String>.firstValue(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k.lowercase()]?.trim()?.ifBlank { null }
            if (v != null) return v
        }
        return null
    }
}
