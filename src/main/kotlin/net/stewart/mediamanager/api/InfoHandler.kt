package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.TranscodeLeaseService

/**
 * Handles GET /api/v1/info — server discovery and capability advertisement.
 * Requires JWT authentication.
 */
object InfoHandler {

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val titleCount = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM title")
                .mapTo(Int::class.java)
                .one()
        }

        val capabilities = mutableListOf("catalog", "streaming", "wishlist", "playback_progress")
        if (TranscodeLeaseService.isForMobileEnabled()) {
            capabilities.add("downloads")
        }

        val response = mapOf(
            "server_version" to "0.1.0",
            "api_version" to "v1",
            "capabilities" to capabilities,
            "title_count" to titleCount,
            "user" to mapOf(
                "id" to user.id,
                "username" to user.username,
                "display_name" to user.display_name,
                "is_admin" to user.isAdmin()
            )
        )

        ApiV1Servlet.sendJson(resp, 200, response, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
