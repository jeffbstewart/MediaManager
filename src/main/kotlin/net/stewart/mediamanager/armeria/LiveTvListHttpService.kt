package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner

/**
 * REST endpoint returning live TV channels for the Angular channel grid.
 */
@Blocking
class LiveTvListHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/live-tv/channels")
    fun listChannels(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        if (!LiveTvStreamHttpService.canAccessLiveTv(user)) {
            return HttpResponse.of(HttpStatus.FORBIDDEN)
        }

        val enabledTunerIds = LiveTvTuner.findAll()
            .filter { it.enabled }
            .mapNotNull { it.id }
            .toSet()

        val minQuality = user.live_tv_min_quality

        val channels = LiveTvChannel.findAll()
            .filter { it.enabled && it.tuner_id in enabledTunerIds }
            .filter { it.reception_quality >= minQuality }
            .sortedWith(compareBy({ it.display_order }, { it.guide_number }))
            .map { ch ->
                mapOf(
                    "id" to ch.id,
                    "guide_number" to ch.guide_number,
                    "guide_name" to ch.guide_name,
                    "network_affiliation" to ch.network_affiliation,
                    "reception_quality" to ch.reception_quality
                )
            }

        val bytes = gson.toJson(mapOf("channels" to channels, "total" to channels.size))
            .toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
