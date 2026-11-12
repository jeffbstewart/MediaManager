package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.BarcodeScan
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.EnrichmentAttempt
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.Transcode
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Detail operations for scanned barcodes: TMDB assignment, purchase info, ownership photos.
 * Shared between Vaadin web UI and gRPC AdminService.
 */
object ScanDetailService {

    private val log = LoggerFactory.getLogger(ScanDetailService::class.java)

    data class ScanDetail(
        val scan: BarcodeScan,
        val mediaItem: MediaItem?,
        val title: Title?,
        val status: BarcodeScanService.CompositeStatus,
        val photos: List<OwnershipPhoto>
    )

    sealed class AssignResult {
        data class Assigned(val titleId: Long) : AssignResult()
        data class Merged(val intoTitleId: Long, val mergedTitleName: String) : AssignResult()
        data class NotFound(val message: String) : AssignResult()
    }

    /**
     * Load full detail for a scan: BarcodeScan, MediaItem, Title, photos.
     */
    fun getDetail(scanId: Long): ScanDetail? {
        val scan = BarcodeScan.findById(scanId) ?: return null
        val mediaItem = scan.media_item_id?.let { MediaItem.findById(it) }
        val title = if (mediaItem != null) {
            MediaItemTitle.findAll()
                .firstOrNull { it.media_item_id == mediaItem.id }
                ?.let { Title.findById(it.title_id) }
        } else null
        val status = BarcodeScanService.compositeStatus(scan, title)
        val photos = if (mediaItem != null) {
            OwnershipPhotoService.findAllForItem(mediaItem.id!!, scan.upc)
        } else {
            OwnershipPhotoService.findByUpc(scan.upc)
        }
        return ScanDetail(scan, mediaItem, title, status, photos)
    }

    /**
     * Assign a TMDB ID to a title. Handles deduplication by merging if another title
     * already has the same (tmdb_id, media_type) pair.
     *
     * Extracted from MediaItemEditView.applyTmdbSelection().
     */
    fun assignTmdb(titleId: Long, tmdbId: Int, mediaType: String): AssignResult {
        val fresh = Title.findById(titleId)
            ?: return AssignResult.NotFound("Title not found: $titleId")

        val newMediaType = if (mediaType == "TV") MediaType.TV.name else MediaType.MOVIE.name

        // Check for duplicate — merge if another title already has this (tmdb_id, media_type)
        val existing = Title.findAll().firstOrNull {
            it.id != fresh.id && it.tmdb_id == tmdbId && it.media_type == newMediaType
        }
        if (existing != null) {
            // Merge: move MediaItemTitle links from fresh → existing
            val freshJoins = MediaItemTitle.findAll().filter { it.title_id == fresh.id }
            for (join in freshJoins) {
                val alreadyLinked = MediaItemTitle.findAll().any {
                    it.title_id == existing.id && it.media_item_id == join.media_item_id
                }
                if (alreadyLinked) join.delete()
                else { join.title_id = existing.id!!; join.save() }
            }
            // Reassign transcodes
            Transcode.findAll().filter { it.title_id == fresh.id }.forEach {
                it.title_id = existing.id!!; it.save()
            }
            // Delete cast, genres, enrichment attempts for the duplicate
            CastMember.findAll().filter { it.title_id == fresh.id }.forEach { it.delete() }
            TitleGenre.findAll().filter { it.title_id == fresh.id }.forEach { it.delete() }
            EnrichmentAttempt.findAll().filter { it.title_id == fresh.id }.forEach { it.delete() }
            Episode.findAll().filter { it.title_id == fresh.id }.forEach { it.delete() }
            DiscoveredFile.findAll().filter { it.matched_title_id == fresh.id }.forEach {
                it.matched_title_id = existing.id!!; it.save()
            }
            fresh.delete()
            SearchIndexService.onTitleChanged(existing.id!!)
            log.info("Merged title {} into existing {} (tmdb_id={}, type={})",
                titleId, existing.id, tmdbId, newMediaType)
            return AssignResult.Merged(existing.id!!, existing.name)
        }

        fresh.tmdb_id = tmdbId
        fresh.media_type = newMediaType
        fresh.enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name
        fresh.save()
        SearchIndexService.onTitleChanged(fresh.id!!)
        log.info("TMDB assigned: title={} tmdb_id={} type={}", titleId, tmdbId, newMediaType)
        return AssignResult.Assigned(titleId)
    }

    /**
     * Update purchase info for a scan's media item.
     */
    fun updatePurchaseInfo(scanId: Long, place: String?, date: LocalDate?, price: BigDecimal?) {
        val scan = BarcodeScan.findById(scanId)
            ?: throw IllegalArgumentException("Scan not found: $scanId")
        val mediaItem = scan.media_item_id?.let { MediaItem.findById(it) }
            ?: throw IllegalArgumentException("Scan $scanId has no linked media item")

        mediaItem.purchase_place = place
        mediaItem.purchase_date = date
        mediaItem.purchase_price = price
        mediaItem.save()
        log.info("Purchase info updated: scan={} mediaItem={}", scanId, mediaItem.id)
    }
}
