package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.TranscodeLeaseService

/**
 * Handles GET /api/v1/admin/... endpoints (admin-only, access_level >= 2):
 * - /admin/transcode-status — pending work counts + active leases
 * - /admin/buddy-status — active leases, completed today, poison pills
 */
object AdminHandler {

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        if (!user.isAdmin()) {
            ApiV1Servlet.sendError(resp, 403, "admin_required")
            MetricsRegistry.countHttpResponse("api_v1", 403)
            return
        }

        if (req.method != "GET") {
            ApiV1Servlet.sendError(resp, 405, "method_not_allowed")
            MetricsRegistry.countHttpResponse("api_v1", 405)
            return
        }

        when (path) {
            "transcode-status" -> handleTranscodeStatus(resp, mapper)
            "buddy-status" -> handleBuddyStatus(resp, mapper)
            else -> {
                ApiV1Servlet.sendError(resp, 404, "not_found")
                MetricsRegistry.countHttpResponse("api_v1", 404)
            }
        }
    }

    private fun handleTranscodeStatus(resp: HttpServletResponse, mapper: ObjectMapper) {
        val pending = TranscodeLeaseService.countPendingWork()
        val activeLeases = TranscodeLeaseService.getActiveLeases()

        val leaseItems = activeLeases.map { lease ->
            mapOf(
                "lease_id" to lease.id,
                "buddy_name" to lease.buddy_name,
                "relative_path" to lease.relative_path,
                "lease_type" to lease.lease_type,
                "status" to lease.status,
                "progress_percent" to lease.progress_percent,
                "encoder" to lease.encoder,
                "claimed_at" to lease.claimed_at?.toString()
            )
        }

        val response = mapOf(
            "pending" to mapOf(
                "transcodes" to pending.transcodes,
                "thumbnails" to pending.thumbnails,
                "subtitles" to pending.subtitles,
                "chapters" to pending.chapters,
                "total" to pending.total
            ),
            "active_leases" to leaseItems
        )

        ApiV1Servlet.sendJson(resp, 200, response, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleBuddyStatus(resp: HttpServletResponse, mapper: ObjectMapper) {
        val activeLeases = TranscodeLeaseService.getActiveLeases()
        val recentLeases = TranscodeLeaseService.getRecentLeases(20)

        // Group active leases by buddy
        val buddies = activeLeases.groupBy { it.buddy_name }.map { (name, leases) ->
            mapOf(
                "name" to name,
                "active_leases" to leases.size,
                "current_work" to leases.map { lease ->
                    mapOf(
                        "lease_id" to lease.id,
                        "relative_path" to lease.relative_path,
                        "lease_type" to lease.lease_type,
                        "progress_percent" to lease.progress_percent,
                        "encoder" to lease.encoder
                    )
                }
            )
        }

        val recentItems = recentLeases.map { lease ->
            mapOf(
                "lease_id" to lease.id,
                "buddy_name" to lease.buddy_name,
                "relative_path" to lease.relative_path,
                "lease_type" to lease.lease_type,
                "status" to lease.status,
                "encoder" to lease.encoder,
                "completed_at" to lease.completed_at?.toString(),
                "error_message" to lease.error_message
            )
        }

        val response = mapOf(
            "buddies" to buddies,
            "recent_leases" to recentItems
        )

        ApiV1Servlet.sendJson(resp, 200, response, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
