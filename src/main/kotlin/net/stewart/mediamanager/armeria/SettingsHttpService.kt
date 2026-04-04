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
import net.stewart.mediamanager.entity.BuddyApiKey
import net.stewart.mediamanager.service.BuddyKeyService
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.util.toIsoUtc


/**
 * REST endpoints for the admin settings page.
 */
@Blocking
class SettingsHttpService {

    private val gson = Gson()
    private val isDocker = java.io.File("/.dockerenv").exists()

    private val configKeys = listOf(
        "nas_root_path", "ffmpeg_path", "roku_base_url",
        "personal_video_enabled", "personal_video_nas_dir",
        "buddy_lease_duration_minutes",
        "keepa_enabled", "keepa_api_key", "keepa_tokens_per_minute",
        "privacy_policy_url", "privacy_policy_version",
        "ios_terms_of_use_url", "ios_terms_of_use_version",
        "web_terms_of_use_url", "web_terms_of_use_version",
        "android_tv_terms_of_use_url", "android_tv_terms_of_use_version"
    )

    @Get("/api/v2/admin/settings")
    fun getSettings(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val configs = AppConfig.findAll()
        val settings = configKeys.associateWith { key ->
            configs.firstOrNull { it.config_key == key }?.config_val ?: ""
        }

        val buddyKeys = BuddyKeyService.getAllKeys().map { key ->
            mapOf(
                "id" to key.id,
                "name" to key.name,
                "created_at" to toIsoUtc(key.created_at)
            )
        }

        return jsonResponse(gson.toJson(mapOf(
            "settings" to settings,
            "buddy_keys" to buddyKeys,
            "is_docker" to isDocker
        )))
    }

    @Post("/api/v2/admin/settings")
    fun saveSettings(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        @Suppress("UNCHECKED_CAST")
        val settings = gson.fromJson(body, Map::class.java) as Map<String, String?>

        for ((key, value) in settings) {
            if (key !in configKeys) continue
            if (isDocker && key in listOf("nas_root_path", "ffmpeg_path")) continue

            val trimmed = value?.trim() ?: ""
            val existing = AppConfig.findAll().firstOrNull { it.config_key == key }
            if (existing != null) {
                existing.config_val = trimmed.ifBlank { null }
                existing.save()
            } else if (trimmed.isNotBlank()) {
                AppConfig(config_key = key, config_val = trimmed).save()
            }
        }

        LegalRequirements.refresh()
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/settings/buddy-keys")
    fun createBuddyKey(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val name = (map["name"] as? String)?.trim()
        if (name.isNullOrBlank()) return badRequest("name required")

        val rawKey = BuddyKeyService.createKey(name)
        return jsonResponse(gson.toJson(mapOf("ok" to true, "key" to rawKey, "name" to name)))
    }

    @Delete("/api/v2/admin/settings/buddy-keys/{keyId}")
    fun deleteBuddyKey(ctx: ServiceRequestContext, @Param("keyId") keyId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        BuddyKeyService.deleteKey(keyId)
        return jsonResponse("""{"ok":true}""")
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
