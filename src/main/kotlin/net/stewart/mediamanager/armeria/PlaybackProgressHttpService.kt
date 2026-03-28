package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.service.PlaybackProgressService

/**
 * REST endpoint for playback progress tracking.
 *
 * POST /playback-progress/{transcodeId}  — record position
 * GET  /playback-progress/{transcodeId}  — get saved position
 * DELETE /playback-progress/{transcodeId} — clear progress
 */
class PlaybackProgressHttpService {

    private val gson = Gson()

    @Post("/playback-progress/{transcodeId}")
    fun record(ctx: ServiceRequestContext, @Param("transcodeId") transcodeId: Long): HttpResponse {
        val userId = resolveUserId(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val body = try {
            val content = ctx.request().aggregate().join().contentUtf8()
            gson.fromJson(content, Map::class.java)
        } catch (e: Exception) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST)
        }

        val position = (body["position"] as? Number)?.toDouble()
            ?: return HttpResponse.of(HttpStatus.BAD_REQUEST)
        val duration = (body["duration"] as? Number)?.toDouble()

        PlaybackProgressService.recordProgressForUser(userId, transcodeId, position, duration)
        return HttpResponse.of(HttpStatus.NO_CONTENT)
    }

    @Get("/playback-progress/{transcodeId}")
    fun get(ctx: ServiceRequestContext, @Param("transcodeId") transcodeId: Long): HttpResponse {
        val userId = resolveUserId(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val progress = PlaybackProgressService.getProgressForUser(userId, transcodeId)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val result = mapOf(
            "position" to progress.position_seconds,
            "duration" to progress.duration_seconds
        )
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, gson.toJson(result))
    }

    @Delete("/playback-progress/{transcodeId}")
    fun delete(ctx: ServiceRequestContext, @Param("transcodeId") transcodeId: Long): HttpResponse {
        val userId = resolveUserId(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val progress = PlaybackProgressService.getProgressForUser(userId, transcodeId)
        if (progress != null) {
            progress.delete()
        }
        return HttpResponse.of(HttpStatus.NO_CONTENT)
    }

    private fun resolveUserId(ctx: ServiceRequestContext): Long? {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return null
        if (user.id == null) {
            return PlaybackProgressService.getOrCreateRokuUser().id
        }
        return user.id
    }
}
