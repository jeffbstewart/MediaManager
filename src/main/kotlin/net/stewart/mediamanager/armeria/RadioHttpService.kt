package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.service.RadioSeedStore
import net.stewart.mediamanager.service.RadioService

/**
 * HTTP façade for Start Radio (M7). Two endpoints:
 *
 *   POST /api/v2/radio/start  — body {seed_type, seed_id}. Creates a
 *                               radio session, returns the first batch
 *                               + radio_seed_id for follow-up calls.
 *   POST /api/v2/radio/next   — body {radio_seed_id, history}. Generates
 *                               the next batch, respecting skip signals.
 *
 * Session state — seed artist MBIDs, seed metadata — lives in the
 * [RadioSeedStore] with a 4-hour TTL. Clients never see the internal
 * [RadioService.RadioSeed]; they get the `radio_seed_id` back and
 * replay it on the next call.
 */
@Blocking
class RadioHttpService {

    private val gson = Gson()

    @Post("/api/v2/radio/start")
    fun start(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val bodyStr = ctx.request().aggregate().join().contentUtf8()
        @Suppress("UNCHECKED_CAST")
        val body = gson.fromJson(bodyStr, Map::class.java) as? Map<String, Any?>
            ?: return badRequest("invalid body")
        val seedType = (body["seed_type"] as? String)?.lowercase()
            ?: return badRequest("seed_type required")
        val seedId = (body["seed_id"] as? Number)?.toLong()
            ?: return badRequest("seed_id required")

        val batch = when (seedType) {
            "album" -> RadioService.startFromAlbum(seedId)
            "track" -> RadioService.startFromTrack(seedId)
            else -> return badRequest("seed_type must be album or track")
        } ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val radioSeedId = RadioSeedStore.put(batch.seed)
        return jsonResponse(gson.toJson(mapOf(
            "radio_seed_id" to radioSeedId,
            "seed" to mapOf(
                "seed_type" to batch.seed.seedType,
                "seed_id" to batch.seed.seedId,
                "seed_name" to batch.seed.seedName,
                "seed_artist_name" to batch.seed.seedArtistName
            ),
            "tracks" to batch.tracks.map { it.toMap() }
        )))
    }

    @Post("/api/v2/radio/next")
    fun next(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val bodyStr = ctx.request().aggregate().join().contentUtf8()
        @Suppress("UNCHECKED_CAST")
        val body = gson.fromJson(bodyStr, Map::class.java) as? Map<String, Any?>
            ?: return badRequest("invalid body")
        val radioSeedId = body["radio_seed_id"] as? String
            ?: return badRequest("radio_seed_id required")
        val seed = RadioSeedStore.get(radioSeedId)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        @Suppress("UNCHECKED_CAST")
        val historyJson = (body["history"] as? List<Map<String, Any?>>).orEmpty()
        val history = historyJson.mapNotNull { h ->
            val trackId = (h["track_id"] as? Number)?.toLong() ?: return@mapNotNull null
            val skipped = (h["skipped_early"] as? Boolean) ?: false
            RadioService.HistoryEntry(trackId = trackId, skippedEarly = skipped)
        }

        val batch = RadioService.nextBatch(seed, history)
        return jsonResponse(gson.toJson(mapOf(
            "radio_seed_id" to radioSeedId,
            "tracks" to batch.tracks.map { it.toMap() }
        )))
    }

    private fun RadioService.TrackRef.toMap(): Map<String, Any?> = mapOf(
        "track_id" to trackId,
        "track_name" to trackName,
        "album_title_id" to albumTitleId,
        "album_name" to albumName,
        "artist_name" to artistName,
        "disc_number" to discNumber,
        "track_number" to trackNumber
    )

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(message: String): HttpResponse =
        HttpResponse.builder()
            .status(HttpStatus.BAD_REQUEST)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to message)))
            .build()
}
