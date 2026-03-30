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
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.service.CameraAdminService
import net.stewart.mediamanager.service.Go2rtcAgent
import java.time.LocalDateTime

@Blocking
class CameraSettingsHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/cameras")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val cameras = CameraAdminService.listAll().map { c ->
            mapOf("id" to c.id, "name" to c.name, "go2rtc_name" to c.go2rtc_name,
                "enabled" to c.enabled, "display_order" to c.display_order)
        }

        val go2rtcStatus = try {
            val agent = Go2rtcAgent.instance
            if (agent == null) "not_configured"
            else if (agent.isHealthy()) "running"
            else if (agent.currentProcess?.isAlive == true) "unhealthy"
            else "stopped"
        } catch (_: Exception) { "unknown" }

        return jsonResponse(gson.toJson(mapOf("cameras" to cameras, "go2rtc_status" to go2rtcStatus)))
    }

    @Post("/api/v2/admin/cameras")
    fun create(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val name = (map["name"] as? String)?.trim() ?: return badRequest("name required")
        val rtspUrl = (map["rtsp_url"] as? String)?.trim() ?: return badRequest("rtsp_url required")
        val snapshotUrl = (map["snapshot_url"] as? String)?.trim() ?: ""

        val go2rtcName = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trimEnd('_')
        val maxOrder = Camera.findAll().maxOfOrNull { it.display_order } ?: -1

        val camera = Camera(name = name, rtsp_url = rtspUrl, snapshot_url = snapshotUrl,
            go2rtc_name = go2rtcName, display_order = maxOrder + 1, enabled = true, created_at = LocalDateTime.now())
        camera.save()
        Go2rtcAgent.instance?.reconfigure()

        return jsonResponse(gson.toJson(mapOf("ok" to true, "id" to camera.id)))
    }

    @Post("/api/v2/admin/cameras/{cameraId}")
    fun update(ctx: ServiceRequestContext, @Param("cameraId") cameraId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val camera = Camera.findById(cameraId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)

        if (map.containsKey("name")) camera.name = (map["name"] as String).trim()
        if (map.containsKey("rtsp_url")) camera.rtsp_url = (map["rtsp_url"] as String).trim()
        if (map.containsKey("snapshot_url")) camera.snapshot_url = (map["snapshot_url"] as? String)?.trim() ?: ""
        if (map.containsKey("enabled")) camera.enabled = map["enabled"] as Boolean
        camera.save()
        Go2rtcAgent.instance?.reconfigure()

        return jsonResponse("""{"ok":true}""")
    }

    @Delete("/api/v2/admin/cameras/{cameraId}")
    fun delete(ctx: ServiceRequestContext, @Param("cameraId") cameraId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        Camera.findById(cameraId)?.delete()
        Go2rtcAgent.instance?.reconfigure()
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/cameras/reorder")
    fun reorder(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        @Suppress("UNCHECKED_CAST")
        val ids = (gson.fromJson(body, Map::class.java)["ids"] as? List<Number>)?.map { it.toLong() }
            ?: return badRequest("ids required")
        CameraAdminService.reorder(ids)
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
