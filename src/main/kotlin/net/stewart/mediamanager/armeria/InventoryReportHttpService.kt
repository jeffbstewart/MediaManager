package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.service.InventoryReportGenerator
import net.stewart.mediamanager.service.OwnershipPhotoService
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * REST endpoints for inventory report generation. Both CSV and PDF run in
 * background threads with progress polling to avoid request timeouts.
 */
@Blocking
class InventoryReportHttpService {

    private data class ReportJob(
        val status: String,    // "generating", "complete", "error"
        val phase: String,
        val current: Int,
        val total: Int,
        val file: File?,
        val contentType: String?,
        val fileName: String?,
        val error: String?
    )

    private val activeJob = AtomicReference<ReportJob?>(null)

    @Get("/api/v2/admin/report/info")
    fun info(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val photoCount = OwnershipPhotoService.totalCount()
        val itemCount = OwnershipPhotoService.itemsWithPhotos()
        return jsonResponse("""{"photo_count":$photoCount,"items_with_photos":$itemCount}""")
    }

    /** Start CSV generation in background. */
    @Post("/api/v2/admin/report/csv/start")
    fun startCsv(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        if (activeJob.get()?.status == "generating") return jsonResponse("""{"ok":true,"already_running":true}""")

        activeJob.set(ReportJob("generating", "Loading data...", 0, 0, null, null, null, null))
        val timestamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        startBackground {
            val csvBytes = InventoryReportGenerator.generateCsv()
            val tempFile = File.createTempFile("inventory-report-", ".csv")
            tempFile.writeBytes(csvBytes)
            activeJob.set(ReportJob("complete", "Complete", 0, 0, tempFile,
                "text/csv; charset=utf-8", "inventory-report-$timestamp.csv", null))
        }
        return jsonResponse("""{"ok":true}""")
    }

    /** Start PDF generation in background. */
    @Post("/api/v2/admin/report/pdf/start")
    fun startPdf(ctx: ServiceRequestContext, @Param("photos") @Default("false") includePhotos: Boolean): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        if (activeJob.get()?.status == "generating") return jsonResponse("""{"ok":true,"already_running":true}""")

        activeJob.set(ReportJob("generating", "Starting...", 0, 0, null, null, null, null))
        val timestamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        startBackground {
            val tempFile = File.createTempFile("inventory-report-", ".pdf")
            InventoryReportGenerator.generatePdf(includePhotos, tempFile) { current, total, phase ->
                activeJob.set(ReportJob("generating", phase, current, total, null, null, null, null))
            }
            activeJob.set(ReportJob("complete", "Complete", 0, 0, tempFile,
                "application/pdf", "inventory-report-$timestamp.pdf", null))
        }
        return jsonResponse("""{"ok":true}""")
    }

    /** Poll generation progress (works for both CSV and PDF). */
    @Get("/api/v2/admin/report/status")
    fun status(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val job = activeJob.get() ?: return jsonResponse("""{"status":"idle"}""")
        val errorJson = if (job.error != null) "\"${job.error.replace("\"", "\\\"").replace("\n", " ")}\"" else "null"
        return jsonResponse("""{"status":"${job.status}","phase":"${job.phase}","current":${job.current},"total":${job.total},"error":$errorJson}""")
    }

    /** Download the completed report (CSV or PDF). */
    @Get("/api/v2/admin/report/download")
    fun download(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val job = activeJob.get()
        if (job == null || job.status != "complete" || job.file == null || !job.file.exists()) {
            return HttpResponse.of(HttpStatus.NOT_FOUND)
        }

        val bytes = job.file.readBytes()
        job.file.delete()
        activeJob.set(null)

        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.parse(job.contentType ?: "application/octet-stream"))
            .contentLength(bytes.size.toLong())
            .add("Content-Disposition", "attachment; filename=\"${job.fileName ?: "report"}\"")
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun startBackground(work: () -> Unit) {
        Thread {
            val hikari = com.gitlab.mvysny.jdbiorm.JdbiOrm.getDataSource() as com.zaxxer.hikari.HikariDataSource
            val saved = hikari.leakDetectionThreshold
            hikari.leakDetectionThreshold = 300_000
            try {
                work()
            } catch (e: Exception) {
                activeJob.set(ReportJob("error", "", 0, 0, null, null, null, e.message))
            } finally {
                hikari.leakDetectionThreshold = saved
            }
        }.apply { isDaemon = true; name = "report-gen" }.start()
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
