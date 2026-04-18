package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.service.AudioTranscodeCache
import net.stewart.mediamanager.service.ListeningProgressService
import net.stewart.mediamanager.service.MetricsRegistry
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile

/**
 * Serves audio files for the web player. Two paths:
 *
 * - **Source passthrough.** If the client's `Accept` header covers the
 *   source format (FLAC is universally supported on the browsers MM
 *   targets, per docs/MUSIC.md's pre-M1 verifications), stream the source
 *   file directly with HTTP Range support. Zero CPU, zero latency.
 * - **On-the-fly AAC transcode.** Otherwise, delegate to
 *   [AudioTranscodeCache] which encodes to AAC m4a @ 256 kbps on first
 *   request and serves from a sharded disk cache on subsequent hits.
 *   The browser's audio element sends `Range` requests while scrubbing;
 *   the faststart-flagged moov atom at the head of the m4a makes that
 *   work from the cache file.
 *
 * Progress reporting is a separate POST endpoint mirroring the
 * reading-progress shape.
 */
@Blocking
class AudioStreamHttpService {

    private val log = LoggerFactory.getLogger(AudioStreamHttpService::class.java)
    private val gson = Gson()

    @Get("/audio/{trackId}")
    fun stream(ctx: ServiceRequestContext, @Param("trackId") trackId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val track = Track.findById(trackId) ?: return notFound()
        val sourcePath = track.file_path ?: return notFound()
        val sourceFile = File(sourcePath)
        if (!sourceFile.isFile) {
            log.warn("Track {} file_path {} no longer exists on disk", trackId, sourcePath)
            return notFound()
        }

        // Rating ceiling — MM doesn't stamp a content_rating on ALBUM titles
        // today, but if a future admin flow ever sets one this gate protects
        // the music library the same way the video one is protected.
        val albumTitle = net.stewart.mediamanager.entity.Title.findById(track.title_id)
        if (albumTitle != null && !user.canSeeRating(albumTitle.content_rating)) {
            MetricsRegistry.countHttpResponse("audio", 403)
            return HttpResponse.of(HttpStatus.FORBIDDEN)
        }

        val sourceExt = sourceFile.extension.lowercase()
        val fileToServe = resolveFileToServe(trackId, sourceFile, sourceExt, ctx)
            ?: run {
                MetricsRegistry.countHttpResponse("audio", 500)
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)
            }

        val contentType = contentTypeFor(fileToServe.extension.lowercase())
        return serveWithRange(ctx, fileToServe, contentType)
    }

    /**
     * Upsert a listening-progress report. Called every ~10 s by the web
     * player while audio is playing, plus once on pause / close.
     */
    @Post("/api/v2/audio/progress")
    fun reportProgress(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val trackId = (map["track_id"] as? Number)?.toLong()
            ?: return badRequest("track_id required")
        val position = (map["position_seconds"] as? Number)?.toInt()
            ?: return badRequest("position_seconds required")
        val duration = (map["duration_seconds"] as? Number)?.toInt()

        ListeningProgressService.save(user.id!!, trackId, position, duration)
        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("ok" to true)))
            .build()
    }

    /**
     * Decide whether to serve the source file verbatim or hand off to the
     * transcode cache. Browsers that accept the source extension get the
     * zero-CPU passthrough; everything else falls through to AAC.
     */
    private fun resolveFileToServe(
        trackId: Long,
        sourceFile: File,
        sourceExt: String,
        ctx: ServiceRequestContext
    ): File? {
        val acceptHeader = ctx.request().headers().get("accept")?.lowercase().orEmpty()
        val sourceMime = contentTypeFor(sourceExt)
        val clientAcceptsSource = clientAcceptsMimeType(acceptHeader, sourceMime)

        // Already-compressed lossy formats never go through the transcoder —
        // re-encoding MP3/AAC is a quality loss for no size win.
        val isAlreadyCompressed = sourceExt in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus")
        if (clientAcceptsSource || isAlreadyCompressed) {
            return sourceFile
        }

        // FLAC / WAV source, client didn't explicitly accept it — transcode.
        val cached = AudioTranscodeCache.cacheAndServe(trackId, sourceFile)
        return cached?.toFile()
    }

    /**
     * Range-request file server. Same shape as the video service's Range
     * handler but simpler (audio doesn't need the Roku-vs-browser chunk-cap
     * split that the video path carries, and the file sizes are small enough
     * that a single response covers a full track).
     */
    private fun serveWithRange(
        ctx: ServiceRequestContext,
        file: File,
        contentType: String
    ): HttpResponse {
        val fileLength = file.length()
        val rangeHeader = ctx.request().headers().get("range")

        val (start, end) = if (rangeHeader == null) {
            0L to (fileLength - 1)
        } else {
            val parsed = parseRange(rangeHeader, fileLength) ?: run {
                MetricsRegistry.countHttpResponse("audio", 416)
                return HttpResponse.of(ResponseHeaders.builder(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .add("Content-Range", "bytes */$fileLength")
                    .build())
            }
            parsed
        }
        val contentLength = end - start + 1

        val status = if (rangeHeader == null) HttpStatus.OK else HttpStatus.PARTIAL_CONTENT
        val headers = ResponseHeaders.builder(status)
            .contentType(MediaType.parse(contentType))
            .add("Accept-Ranges", "bytes")
            .also {
                if (rangeHeader != null) it.add("Content-Range", "bytes $start-$end/$fileLength")
            }
            .contentLength(contentLength)
            .build()

        return try {
            // Audio file sizes are small enough (a typical 4-min AAC m4a @
            // 256k is ~7.5 MiB) to send in-process rather than streamed.
            // For larger tracks the client will Range-chunk naturally.
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val total = contentLength.toInt()
                val out = ByteArray(total)
                var offset = 0
                while (offset < total) {
                    val read = raf.read(out, offset, total - offset)
                    if (read == -1) break
                    offset += read
                }
                MetricsRegistry.countHttpResponse("audio", status.code())
                HttpResponse.of(headers, HttpData.wrap(out, 0, offset))
            }
        } catch (e: Exception) {
            log.warn("Audio range read error on {}: {}", file, e.message)
            MetricsRegistry.countHttpResponse("audio", 500)
            HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)
        }
    }

    private fun parseRange(rangeHeader: String, fileLength: Long): Pair<Long, Long>? {
        if (!rangeHeader.startsWith("bytes=")) return null
        val range = rangeHeader.removePrefix("bytes=").trim()
        val dashIndex = range.indexOf('-')
        if (dashIndex < 0) return null
        return try {
            val startStr = range.substring(0, dashIndex).trim()
            val endStr = range.substring(dashIndex + 1).trim()
            if (startStr.isEmpty()) {
                val suffix = endStr.toLong()
                val start = maxOf(fileLength - suffix, 0)
                start to (fileLength - 1)
            } else {
                val start = startStr.toLong()
                val end = if (endStr.isEmpty()) fileLength - 1 else minOf(endStr.toLong(), fileLength - 1)
                if (start > end || start >= fileLength) null else start to end
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * True when the client's Accept header includes [mime] (or its wildcard
     * family). Also true for the wide-open catch-all `star-slash-star`
     * browsers send when unsure but willing to try anything.
     */
    private fun clientAcceptsMimeType(accept: String, mime: String): Boolean {
        if (accept.isBlank() || accept.contains("*/*")) return true
        val family = mime.substringBefore('/') + "/*"
        return accept.contains(mime) || accept.contains(family)
    }

    private fun contentTypeFor(ext: String): String = when (ext) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private fun notFound(): HttpResponse {
        MetricsRegistry.countHttpResponse("audio", 404)
        return HttpResponse.of(HttpStatus.NOT_FOUND)
    }

    private fun badRequest(message: String): HttpResponse =
        HttpResponse.builder()
            .status(HttpStatus.BAD_REQUEST)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to message)))
            .build()
}
