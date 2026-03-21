package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.TranscodeLeaseService
import net.stewart.mediamanager.service.WishListService

/**
 * Handles /api/v1/admin/... endpoints (admin-only, access_level >= 2).
 * Routes to sub-handlers for distinct feature areas:
 * - /admin/transcode-status, /admin/buddy-status, /admin/rip-backlog — direct (GET)
 * - /admin/users/... — UserManagementHandler
 * - /admin/purchase-wishes/... — PurchaseWishHandler
 * - /admin/transcodes/... — TranscodeManagementHandler
 * - /admin/scan-nas, /admin/clear-failures, /admin/data-quality,
 *   /admin/titles/..., /admin/settings, /admin/tags/... — AdminActionHandler
 */
object AdminHandler {

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        if (!user.isAdmin()) {
            ApiV1Servlet.sendError(resp, 403, "admin_required")
            MetricsRegistry.countHttpResponse("api_v1", 403)
            return
        }

        when {
            // Sub-handler delegation
            path == "users" || path.startsWith("users/") ->
                UserManagementHandler.handle(req, resp, path.removePrefix("users").removePrefix("/"), mapper, user)
            path == "purchase-wishes" || path.startsWith("purchase-wishes/") ->
                PurchaseWishHandler.handle(req, resp, path.removePrefix("purchase-wishes").removePrefix("/"), mapper)
            path.startsWith("transcodes/") ->
                TranscodeManagementHandler.handle(req, resp, path.removePrefix("transcodes/"), mapper)

            // AdminActionHandler paths
            path == "scan-nas" || path == "clear-failures" ||
            path == "data-quality" || path.startsWith("titles/") ||
            path == "settings" || path == "tags" || path.startsWith("tags/") ->
                AdminActionHandler.handle(req, resp, path, mapper)

            // Direct GET-only endpoints
            path == "transcode-status" && req.method == "GET" -> handleTranscodeStatus(resp, mapper)
            path == "buddy-status" && req.method == "GET" -> handleBuddyStatus(resp, mapper)
            path == "rip-backlog" && req.method == "GET" -> handleRipBacklog(req, resp, mapper)

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
                "mobile_transcodes" to pending.mobileTranscodes,
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

    /**
     * GET /admin/rip-backlog — owned titles with no rip on the NAS.
     * Sorted by transcode wish count (descending), then TMDB popularity (descending).
     */
    private fun handleRipBacklog(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val titles = Title.findAll()
        val wishCounts = WishListService.getTranscodeWishCounts()

        val titlesWithTranscodes = Transcode.findAll()
            .filter { it.file_path != null }
            .map { it.title_id }
            .toSet()

        val titlesWithMedia = MediaItemTitle.findAll()
            .map { it.title_id }
            .toSet()

        val page = (req.getParameter("page")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val limit = (req.getParameter("limit")?.toIntOrNull() ?: 50).coerceIn(1, 200)

        val rows = titles
            .filter {
                it.enrichment_status == EnrichmentStatus.ENRICHED.name &&
                    !it.hidden &&
                    it.id in titlesWithMedia &&
                    it.id !in titlesWithTranscodes
            }
            .map { title ->
                val posterUrl = if (title.poster_path != null) "/posters/w185/${title.id}" else null
                mapOf(
                    "title_id" to title.id,
                    "title_name" to title.name,
                    "media_type" to title.media_type,
                    "release_year" to title.release_year,
                    "poster_url" to posterUrl,
                    "request_count" to (wishCounts[title.id] ?: 0),
                    "popularity" to (title.popularity ?: 0.0)
                )
            }
            .sortedWith(
                compareByDescending<Map<String, Any?>> { it["request_count"] as Int }
                    .thenByDescending { it["popularity"] as Double }
            )

        val total = rows.size
        val totalPages = if (total == 0) 0 else (total + limit - 1) / limit
        val pageRows = rows.drop((page - 1) * limit).take(limit)

        ApiV1Servlet.sendJson(resp, 200, mapOf(
            "items" to pageRows,
            "total" to total,
            "page" to page,
            "limit" to limit,
            "total_pages" to totalPages
        ), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
