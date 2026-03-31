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
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.service.HdHomeRunService
import net.stewart.mediamanager.service.LiveTvStreamManager
import java.time.LocalDateTime

@Blocking
class LiveTvSettingsHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/live-tv")
    fun getSettings(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val tuners = LiveTvTuner.findAll().sortedBy { it.name.lowercase() }.map { t ->
            mapOf("id" to t.id, "name" to t.name, "device_id" to t.device_id, "ip_address" to t.ip_address,
                "model_number" to t.model_number, "tuner_count" to t.tuner_count,
                "firmware_version" to t.firmware_version, "enabled" to t.enabled)
        }

        val channels = LiveTvChannel.findAll().sortedWith(compareBy({ it.tuner_id }, { it.guide_number })).map { ch ->
            mapOf("id" to ch.id, "tuner_id" to ch.tuner_id, "guide_number" to ch.guide_number,
                "guide_name" to ch.guide_name, "network_affiliation" to ch.network_affiliation,
                "reception_quality" to ch.reception_quality, "enabled" to ch.enabled)
        }

        val configs = AppConfig.findAll()
        val settings = mapOf(
            "live_tv_max_streams" to (configs.firstOrNull { it.config_key == "live_tv_max_streams" }?.config_val?.toIntOrNull() ?: 2),
            "live_tv_idle_timeout_seconds" to (configs.firstOrNull { it.config_key == "live_tv_idle_timeout_seconds" }?.config_val?.toIntOrNull() ?: 60),
            "live_tv_min_rating" to (configs.firstOrNull { it.config_key == "live_tv_min_rating" }?.config_val?.toIntOrNull() ?: 4)
        )

        val activeStreams = LiveTvStreamManager.activeStreamCount()

        return jsonResponse(gson.toJson(mapOf("tuners" to tuners, "channels" to channels,
            "settings" to settings, "active_streams" to activeStreams)))
    }

    @Post("/api/v2/admin/live-tv/settings")
    fun saveSettings(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        for (key in listOf("live_tv_max_streams", "live_tv_idle_timeout_seconds", "live_tv_min_rating")) {
            val value = (map[key] as? Number)?.toInt()?.toString() ?: continue
            val existing = AppConfig.findAll().firstOrNull { it.config_key == key }
            if (existing != null) { existing.config_val = value; existing.save() }
            else AppConfig(config_key = key, config_val = value).save()
        }
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/live-tv/tuners/discover")
    fun discoverTuner(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val ip = map["ip"] as? String ?: return badRequest("ip required")

        val discovery = HdHomeRunService.discoverDevice(ip)
            ?: return jsonResponse("""{"ok":false,"error":"Could not connect to device at $ip"}""")

        // Check for existing tuner with same device_id
        val existing = LiveTvTuner.findAll().firstOrNull { it.device_id == discovery.deviceId }
        if (existing != null) return jsonResponse("""{"ok":false,"error":"Tuner already registered (${existing.name})"}""")

        val tuner = LiveTvTuner(
            name = discovery.friendlyName, device_id = discovery.deviceId, ip_address = ip.trim(),
            model_number = discovery.modelNumber, tuner_count = discovery.tunerCount,
            firmware_version = discovery.firmwareVersion, enabled = true, created_at = LocalDateTime.now()
        )
        tuner.save()

        // Auto-sync channels
        val sync = HdHomeRunService.syncChannels(tuner.id!!, ip.trim())

        return jsonResponse(gson.toJson(mapOf("ok" to true, "tuner_id" to tuner.id, "name" to tuner.name,
            "channels_added" to (sync?.added ?: 0))))
    }

    @Post("/api/v2/admin/live-tv/tuners/{tunerId}/update")
    fun updateTuner(ctx: ServiceRequestContext, @Param("tunerId") tunerId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val tuner = LiveTvTuner.findById(tunerId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        if (map.containsKey("name")) tuner.name = (map["name"] as String).trim()
        if (map.containsKey("ip_address")) tuner.ip_address = (map["ip_address"] as String).trim()
        tuner.save()
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/live-tv/tuners/{tunerId}/toggle")
    fun toggleTuner(ctx: ServiceRequestContext, @Param("tunerId") tunerId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val tuner = LiveTvTuner.findById(tunerId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        tuner.enabled = !tuner.enabled; tuner.save()
        return jsonResponse("""{"ok":true,"enabled":${tuner.enabled}}""")
    }

    @Post("/api/v2/admin/live-tv/tuners/{tunerId}/sync")
    fun syncChannels(ctx: ServiceRequestContext, @Param("tunerId") tunerId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val tuner = LiveTvTuner.findById(tunerId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val sync = HdHomeRunService.syncChannels(tunerId, tuner.ip_address)
            ?: return jsonResponse("""{"ok":false,"error":"Failed to fetch lineup"}""")

        return jsonResponse(gson.toJson(mapOf("ok" to true, "added" to sync.added, "updated" to sync.updated, "deleted" to sync.deleted)))
    }

    @Delete("/api/v2/admin/live-tv/tuners/{tunerId}")
    fun deleteTuner(ctx: ServiceRequestContext, @Param("tunerId") tunerId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        LiveTvChannel.findAll().filter { it.tuner_id == tunerId }.forEach { it.delete() }
        LiveTvTuner.findById(tunerId)?.delete()
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/live-tv/channels/{channelId}/toggle")
    fun toggleChannel(ctx: ServiceRequestContext, @Param("channelId") channelId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val ch = LiveTvChannel.findById(channelId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        ch.enabled = !ch.enabled; ch.save()
        return jsonResponse("""{"ok":true,"enabled":${ch.enabled}}""")
    }

    @Post("/api/v2/admin/live-tv/channels/{channelId}/update")
    fun updateChannel(ctx: ServiceRequestContext, @Param("channelId") channelId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val ch = LiveTvChannel.findById(channelId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)

        if (map.containsKey("network_affiliation")) ch.network_affiliation = (map["network_affiliation"] as? String)?.ifBlank { null }
        if (map.containsKey("reception_quality")) ch.reception_quality = (map["reception_quality"] as? Number)?.toInt() ?: 3
        ch.save()
        return jsonResponse("""{"ok":true}""")
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON_UTF_8).contentLength(bytes.size.toLong()).build(), HttpData.wrap(bytes))
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.BAD_REQUEST).contentType(MediaType.JSON_UTF_8).contentLength(bytes.size.toLong()).build(), HttpData.wrap(bytes))
    }
}
