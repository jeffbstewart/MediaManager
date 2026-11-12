package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.service.AdvancedSearchPresets
import net.stewart.mediamanager.service.TrackSearchService

/**
 * REST endpoints for the Advanced Search surface (BPM range + time
 * signature + text query over tracks, plus a preset list for the
 * ballroom-dance flow). Mirrors the SearchTracks / ListAdvancedSearchPresets
 * RPCs on CatalogService so iOS and the web client see the same
 * semantics.
 */
@Blocking
class AdvancedSearchHttpService {

    private val gson = Gson()

    /** Dance presets — Slow Waltz, Foxtrot, Rumba, Hustle, etc. */
    @Get("/api/v2/search/presets")
    fun listPresets(ctx: ServiceRequestContext): HttpResponse {
        ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val rows = AdvancedSearchPresets.ALL.map { p ->
            mapOf(
                "key" to p.key,
                "name" to p.name,
                "description" to p.description,
                "bpm_min" to p.bpmMin,
                "bpm_max" to p.bpmMax,
                "time_signature" to p.timeSignature
            )
        }
        return jsonResponse(gson.toJson(mapOf("presets" to rows)))
    }

    /**
     * Filtered track search. All filter params optional; sending none
     * of them returns an empty list (the browse grids are the right
     * tool for unfiltered listing).
     */
    @Get("/api/v2/search/tracks")
    fun searchTracks(
        ctx: ServiceRequestContext,
        @Param("q") @Default("") q: String,
        @Param("bpm_min") @Default("0") bpmMin: Int,
        @Param("bpm_max") @Default("0") bpmMax: Int,
        @Param("time_signature") @Default("") timeSignature: String,
        @Param("limit") @Default("200") limit: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val hits = TrackSearchService.search(
            user,
            TrackSearchService.Filters(
                query = q.takeIf { it.isNotBlank() },
                bpmMin = bpmMin.takeIf { it > 0 },
                bpmMax = bpmMax.takeIf { it > 0 },
                timeSignature = timeSignature.takeIf { it.isNotBlank() },
                limit = limit
            )
        )
        val rows = hits.map { h ->
            mapOf(
                "track_id" to h.trackId,
                "title_id" to h.titleId,
                "name" to h.name,
                "album_name" to h.albumName,
                "artist_name" to h.artistName,
                "bpm" to h.bpm,
                "time_signature" to h.timeSignature,
                "duration_seconds" to h.durationSeconds,
                "poster_url" to h.posterUrl,
                "playable" to h.playable
            )
        }
        return jsonResponse(gson.toJson(mapOf("tracks" to rows)))
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
