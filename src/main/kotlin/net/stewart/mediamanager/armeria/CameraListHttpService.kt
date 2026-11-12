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
import net.stewart.mediamanager.entity.Camera

/**
 * REST endpoint returning enabled cameras for the Angular camera grid.
 */
@Blocking
class CameraListHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/cameras")
    fun listCameras(ctx: ServiceRequestContext): HttpResponse {
        ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val cameras = Camera.findAll()
            .filter { it.enabled }
            .sortedBy { it.display_order }
            .map { mapOf("id" to it.id, "name" to it.name) }

        val bytes = gson.toJson(mapOf("cameras" to cameras, "total" to cameras.size))
            .toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
