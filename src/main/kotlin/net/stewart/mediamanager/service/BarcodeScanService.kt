package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.BarcodeScan
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.LookupStatus
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Shared barcode submission and query logic, used by both the Vaadin web UI
 * and the gRPC AdminService. No UI-layer concerns (notifications, field focus).
 */
object BarcodeScanService {

    private val log = LoggerFactory.getLogger(BarcodeScanService::class.java)

    // --- Submit result types ---

    sealed class SubmitResult {
        data class Created(val scanId: Long, val upc: String) : SubmitResult()
        data class Duplicate(val upc: String, val titleName: String) : SubmitResult()
        data class Invalid(val reason: String) : SubmitResult()
    }

    /** Info about a barcode scan with joined title data. */
    data class ScanInfo(
        val scanId: Long,
        val upc: String,
        val status: CompositeStatus,
        val titleName: String?,
        val posterUrl: String?,
        val titleId: Long?,
        val scannedAt: LocalDateTime?
    )

    enum class CompositeStatus {
        SUBMITTED,
        UPC_FOUND,
        UPC_NOT_FOUND,
        ENRICHING,
        ENRICHED,
        ENRICHMENT_FAILED,
        NO_MATCH
    }

    // --- Public API ---

    /**
     * Validate and submit a UPC barcode for lookup.
     * Creates a [BarcodeScan] record; the existing [UpcLookupAgent] will pick it up.
     */
    fun submit(upc: String): SubmitResult {
        val trimmed = upc.trim()

        if (trimmed.isBlank() || !trimmed.all { it.isDigit() }) {
            return SubmitResult.Invalid("UPC must contain only digits")
        }
        if (trimmed.length < 8 || trimmed.length > 14) {
            return SubmitResult.Invalid("Invalid UPC length: must be 8–14 digits (got ${trimmed.length})")
        }

        val existingScan = BarcodeScan.findAll().firstOrNull { it.upc == trimmed }
        if (existingScan != null) {
            val titleName = findTitleForScan(existingScan)
            if (titleName != null) {
                return SubmitResult.Duplicate(trimmed, titleName)
            }
            // Orphaned scan — media item was unlinked. Delete stale record and allow re-scan.
            existingScan.delete()
        }

        val scan = BarcodeScan(
            upc = trimmed,
            scanned_at = LocalDateTime.now(),
            lookup_status = LookupStatus.NOT_LOOKED_UP.name
        )
        scan.save()
        log.info("Barcode submitted: {} (scan_id={})", trimmed, scan.id)

        return SubmitResult.Created(scan.id!!, trimmed)
    }

    /**
     * Get recent barcode scans with joined title/enrichment info.
     */
    fun getRecentScans(limit: Int = 50): List<ScanInfo> {
        return BarcodeScan.findAll()
            .sortedByDescending { it.scanned_at }
            .take(limit)
            .map { scan -> scanToInfo(scan) }
    }

    /**
     * Build a [ScanInfo] for a single scan, joining to title data.
     */
    fun scanToInfo(scan: BarcodeScan): ScanInfo {
        val titleData = findTitleDataForScan(scan)
        return ScanInfo(
            scanId = scan.id!!,
            upc = scan.upc,
            status = compositeStatus(scan, titleData?.second),
            titleName = titleData?.second?.name,
            posterUrl = titleData?.second?.posterUrl(PosterSize.THUMBNAIL),
            titleId = titleData?.second?.id,
            scannedAt = scan.scanned_at
        )
    }

    /**
     * Map a BarcodeScan + optional Title to a composite status for the client.
     */
    fun compositeStatus(scan: BarcodeScan, title: Title? = null): CompositeStatus {
        return when (scan.lookup_status) {
            LookupStatus.NOT_LOOKED_UP.name -> CompositeStatus.SUBMITTED
            LookupStatus.NOT_FOUND.name -> CompositeStatus.UPC_NOT_FOUND
            LookupStatus.FOUND.name -> {
                val resolvedTitle = title ?: findTitleDataForScan(scan)?.second
                when (resolvedTitle?.enrichment_status) {
                    EnrichmentStatus.ENRICHED.name -> CompositeStatus.ENRICHED
                    EnrichmentStatus.FAILED.name, EnrichmentStatus.ABANDONED.name ->
                        CompositeStatus.ENRICHMENT_FAILED
                    EnrichmentStatus.SKIPPED.name -> CompositeStatus.NO_MATCH
                    else -> CompositeStatus.ENRICHING
                }
            }
            else -> CompositeStatus.SUBMITTED
        }
    }

    // --- Internal ---

    /** Find the title name for a scan (if linked via MediaItem → MediaItemTitle → Title). */
    private fun findTitleForScan(scan: BarcodeScan): String? {
        return findTitleDataForScan(scan)?.second?.name
    }

    /** Find the MediaItemTitle join and Title for a scan. Returns null if not linked. */
    private fun findTitleDataForScan(scan: BarcodeScan): Pair<MediaItemTitle, Title>? {
        val mediaItemId = scan.media_item_id ?: return null
        val join = MediaItemTitle.findAll().firstOrNull { it.media_item_id == mediaItemId } ?: return null
        val title = Title.findById(join.title_id) ?: return null
        return Pair(join, title)
    }
}
