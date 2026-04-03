package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.CommandLineFlags
import net.stewart.mediamanager.entity.LeaseStatus
import net.stewart.mediamanager.entity.LeaseType
import net.stewart.mediamanager.service.NasScannerService
import net.stewart.mediamanager.service.TranscodeLeaseService
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.mediamanager.util.toIsoUtc
import java.time.Duration
import java.time.LocalDateTime

/**
 * REST endpoint for the transcode status dashboard in the Angular web app.
 */
@Blocking
class TranscodeStatusHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/transcode-status")
    fun status(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val stats = TranscodeLeaseService.getThroughputStats()
        val pending = TranscodeLeaseService.countPendingWork()
        val eta = stats.estimateSecondsLeft(pending)

        val thumbStats = TranscodeLeaseService.getThumbnailStats()
        val subStats = TranscodeLeaseService.getSubtitleStats()
        val chapterStats = TranscodeLeaseService.getChapterStats()

        // Active leases (buddy workers)
        val activeLeases = TranscodeLeaseService.getActiveLeases()
        val buddyLeases = activeLeases.filter { it.buddy_name != "local" }
        val localLeases = activeLeases.filter { it.buddy_name == "local" }

        val activeBuddies = buddyLeases.map { lease ->
            val elapsed = if (lease.claimed_at != null) {
                Duration.between(lease.claimed_at, LocalDateTime.now()).seconds
            } else 0L
            mapOf(
                "id" to lease.id,
                "buddy_name" to lease.buddy_name,
                "file_name" to lease.relative_path.substringAfterLast('/'),
                "relative_path" to lease.relative_path,
                "lease_type" to lease.lease_type,
                "status" to lease.status,
                "progress_percent" to lease.progress_percent,
                "encoder" to lease.encoder,
                "elapsed_seconds" to elapsed
            )
        }

        // Recent completions/failures
        val recentLeases = TranscodeLeaseService.getRecentLeases(20)
            .filter { it.buddy_name != "local" }
        val recentItems = recentLeases.map { lease ->
            val elapsed = if (lease.claimed_at != null && lease.completed_at != null) {
                Duration.between(lease.claimed_at, lease.completed_at).seconds
            } else null
            mapOf(
                "id" to lease.id,
                "buddy_name" to lease.buddy_name,
                "file_name" to lease.relative_path.substringAfterLast('/'),
                "lease_type" to lease.lease_type,
                "status" to lease.status,
                "file_size_bytes" to lease.file_size_bytes,
                "elapsed_seconds" to elapsed,
                "error_message" to lease.error_message,
                "completed_at" to toIsoUtc(lease.completed_at)
            )
        }

        // Local transcoder status
        val localDisabled = CommandLineFlags.disableLocalTranscoding
        val localStatus = if (!localDisabled && localLeases.isNotEmpty()) {
            val lease = localLeases.first()
            mapOf(
                "active" to true,
                "file_name" to lease.relative_path.substringAfterLast('/'),
                "progress_percent" to lease.progress_percent,
                "lease_type" to lease.lease_type
            )
        } else {
            mapOf("active" to false)
        }

        val result = mapOf(
            "local_disabled" to localDisabled,
            "local_status" to localStatus,
            "overall" to mapOf(
                "total_completed" to stats.totalCompleted,
                "total_bytes" to stats.formatTotalBytes(),
                "pending" to mapOf(
                    "transcodes" to pending.transcodes,
                    "mobile" to pending.mobileTranscodes,
                    "thumbnails" to pending.thumbnails,
                    "subtitles" to pending.subtitles,
                    "chapters" to pending.chapters,
                    "total" to pending.total
                ),
                "throughput" to mapOf(
                    "transcode_rate" to round1(stats.transcodeRate),
                    "mobile_rate" to round1(stats.mobileRate),
                    "thumbnail_rate" to round1(stats.thumbnailRate),
                    "subtitle_rate" to round1(stats.subtitleRate),
                    "chapter_rate" to round1(stats.chapterRate),
                    "bytes_per_hour" to stats.formatBytesPerHour()
                ),
                "eta_seconds" to eta,
                "active_workers" to stats.activeWorkers,
                "failed_count" to stats.failedCount,
                "thumbnail_stats" to mapOf("total" to thumbStats.first, "today" to thumbStats.second),
                "subtitle_stats" to mapOf("total" to subStats.first, "today" to subStats.second),
                "chapter_stats" to mapOf("total" to chapterStats.first, "today" to chapterStats.second)
            ),
            "active_buddies" to activeBuddies,
            "recent" to recentItems
        )

        val bytes = gson.toJson(result).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    @Post("/api/v2/admin/transcode-status/scan")
    fun scanNas(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        if (NasScannerService.isRunning()) {
            return jsonResponse("""{"ok":false,"reason":"Scan already in progress"}""")
        }
        NasScannerService.scan()
        return jsonResponse("""{"ok":true}""")
    }

    @Post("/api/v2/admin/transcode-status/clear-failures")
    fun clearFailures(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val cleared = TranscodeLeaseService.clearAllFailures()
        return jsonResponse("""{"ok":true,"cleared":$cleared}""")
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun round1(d: Double): Double = Math.round(d * 10.0) / 10.0
}
