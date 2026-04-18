package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

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
        val musicBrainzArtistId: String?
    ) {
        companion object {
            val EMPTY = AudioTags(
                null, null, null, null, null, null, null, null, null, null, null, null
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
        val stdout = runCatching { runFfprobe(ffprobePath, file) }
            .onFailure { log.warn("ffprobe failed for {}: {}", file.absolutePath, it.message) }
            .getOrNull() ?: return AudioTags.EMPTY

        return try {
            parse(stdout)
        } catch (e: Exception) {
            log.warn("ffprobe parse failed for {}: {}", file.absolutePath, e.message)
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
                "musicbrainz album id"
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
            )?.substringBefore(';')?.trim()?.ifBlank { null }
        )
    }

    /** Parse "3/12" or "3" into 3. Returns null on non-numeric input. */
    internal fun parseSlashPrefixInt(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val head = raw.substringBefore('/').trim()
        return head.toIntOrNull()
    }

    private fun runFfprobe(ffprobePath: String, file: File): String? {
        val proc = ProcessBuilder(
            ffprobePath,
            "-v", "error",
            "-print_format", "json",
            "-show_format",
            "-show_streams",
            file.absolutePath
        ).redirectErrorStream(false).start()
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            log.warn("ffprobe timed out on {}", file.absolutePath)
            return null
        }
        return if (proc.exitValue() == 0) {
            proc.inputStream.bufferedReader().readText()
        } else {
            log.warn("ffprobe exit {} on {}", proc.exitValue(), file.absolutePath)
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
