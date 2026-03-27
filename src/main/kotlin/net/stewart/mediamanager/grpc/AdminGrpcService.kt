package net.stewart.mediamanager.grpc

import com.github.vokorm.findAll
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.DiscoveredFileStatus
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.TranscodeLease
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.entity.CastMember as CastMemberEntity
import net.stewart.mediamanager.entity.EnrichmentStatus as EnrichmentStatusEnum
import net.stewart.mediamanager.entity.Episode as EpisodeEntity
import net.stewart.mediamanager.entity.Tag as TagEntity
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.entity.Transcode as TranscodeEntity
import net.stewart.mediamanager.linkDiscoveredFileToTitle
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.BarcodeScanService
import net.stewart.mediamanager.service.Broadcaster
import net.stewart.mediamanager.service.CameraAdminService
import net.stewart.mediamanager.service.UriCredentialRedactor
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.service.OwnershipPhotoService
import net.stewart.mediamanager.service.ScanDetailService
import net.stewart.mediamanager.service.BuddyProgressEvent
import net.stewart.mediamanager.service.ScanUpdateEvent
import net.stewart.mediamanager.service.TitleUpdateEvent
import net.stewart.mediamanager.service.FuzzyMatchService
import net.stewart.mediamanager.service.NasScannerService
import net.stewart.mediamanager.service.PasswordService
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TagService
import net.stewart.mediamanager.service.TranscodeLeaseService
import net.stewart.mediamanager.service.WishListService
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime

/**
 * Admin-only service. Auth interceptor enforces admin role for all RPCs.
 */
class AdminGrpcService : AdminServiceGrpcKt.AdminServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(AdminGrpcService::class.java)

    // ========================================================================
    // Transcode Monitoring
    // ========================================================================

    override suspend fun getTranscodeStatus(request: Empty): TranscodeStatusResponse {
        val pending = TranscodeLeaseService.countPendingWork()
        val activeLeases = TranscodeLeaseService.getActiveLeases()
        val poisonPillCount = TranscodeLeaseService.getPoisonPillTranscodeIds().size

        return transcodeStatusResponse {
            pendingTranscode = pending.transcodes
            pendingThumbnails = pending.thumbnails
            pendingSubtitles = pending.subtitles
            pendingChapters = pending.chapters
            pendingLowStorage = pending.mobileTranscodes
            poisonPills = poisonPillCount
            this.activeLeases.addAll(activeLeases.map { it.toActiveLease() })
        }
    }

    override suspend fun getBuddyStatus(request: Empty): BuddyStatusResponse {
        val activeLeases = TranscodeLeaseService.getActiveLeases()
        val recentLeases = TranscodeLeaseService.getRecentLeases(20)

        // Group active leases by buddy_name to build BuddyInfo
        val buddyGroups = activeLeases.groupBy { it.buddy_name }
        val buddies = buddyGroups.map { (name, leases) ->
            buddyInfo {
                this.name = name
                this.activeLeases = leases.size
                val maxClaimedAt = leases.mapNotNull { it.claimed_at }.maxOrNull()
                maxClaimedAt?.let { lastSeen = it.toProtoTimestamp() }
            }
        }

        return buddyStatusResponse {
            this.buddies.addAll(buddies)
            this.recentLeases.addAll(recentLeases.map { it.toRecentLease() })
        }
    }

    override suspend fun scanNas(request: Empty): Empty {
        if (NasScannerService.isRunning()) {
            throw StatusException(Status.ALREADY_EXISTS.withDescription("NAS scan already in progress"))
        }
        NasScannerService.scan()
        return empty {}
    }

    override suspend fun clearFailures(request: Empty): ClearFailuresResponse {
        val count = TranscodeLeaseService.clearAllFailures()
        return clearFailuresResponse {
            cleared = count
        }
    }

    override fun watchTranscodeProgress(request: Empty): Flow<TranscodeProgressEvent> = callbackFlow {
        val listener: (BuddyProgressEvent) -> Unit = { event ->
            trySend(event.toProtoProgressEvent())
        }
        Broadcaster.registerBuddyListener(listener)
        awaitClose { Broadcaster.unregisterBuddyListener(listener) }
    }

    override fun monitorTranscodeStatus(request: Empty): Flow<TranscodeStatusUpdate> = callbackFlow {
        // Send initial snapshot
        trySend(transcodeStatusUpdate {
            snapshot = getTranscodeStatus(request)
        })

        val listener: (BuddyProgressEvent) -> Unit = { event ->
            // Send the delta event
            trySend(transcodeStatusUpdate {
                this.event = event.toProtoProgressEvent()
            })

            // On terminal states, send a refreshed snapshot so pending counts stay accurate
            if (event.status in listOf("COMPLETED", "FAILED", "EXPIRED")) {
                try {
                    trySend(transcodeStatusUpdate {
                        snapshot = kotlinx.coroutines.runBlocking { getTranscodeStatus(request) }
                    })
                } catch (e: Exception) {
                    log.warn("MonitorTranscodeStatus: failed to send post-completion snapshot", e)
                }
            }
        }
        Broadcaster.registerBuddyListener(listener)
        awaitClose { Broadcaster.unregisterBuddyListener(listener) }
    }

    // ========================================================================
    // Barcode Scanning
    // ========================================================================

    override suspend fun submitBarcode(request: SubmitBarcodeRequest): SubmitBarcodeResponse {
        return when (val result = BarcodeScanService.submit(request.upc)) {
            is BarcodeScanService.SubmitResult.Created -> submitBarcodeResponse {
                this.result = SubmitBarcodeResult.SUBMIT_BARCODE_RESULT_CREATED
                scanId = result.scanId
                message = "Scanned: ${result.upc}"
            }
            is BarcodeScanService.SubmitResult.Duplicate -> submitBarcodeResponse {
                this.result = SubmitBarcodeResult.SUBMIT_BARCODE_RESULT_DUPLICATE
                titleName = result.titleName
                message = "Already scanned: ${result.upc} (${result.titleName})"
            }
            is BarcodeScanService.SubmitResult.Invalid -> submitBarcodeResponse {
                this.result = SubmitBarcodeResult.SUBMIT_BARCODE_RESULT_INVALID
                message = result.reason
            }
        }
    }

    override suspend fun listRecentScans(request: Empty): RecentScansResponse {
        return recentScansResponse {
            scans.addAll(BarcodeScanService.getRecentScans().map { it.toProtoRecentScan() })
        }
    }

    override fun monitorScanProgress(request: Empty): Flow<ScanProgressUpdate> = callbackFlow {
        // Send initial snapshot
        trySend(scanProgressUpdate {
            snapshot = recentScansResponse {
                scans.addAll(BarcodeScanService.getRecentScans().map { it.toProtoRecentScan() })
            }
        })

        val scanListener: (ScanUpdateEvent) -> Unit = { event ->
            // Look up full scan info to get title data
            val scanInfo = try {
                val scan = net.stewart.mediamanager.entity.BarcodeScan.findAll()
                    .firstOrNull { it.id == event.scanId }
                scan?.let { BarcodeScanService.scanToInfo(it) }
            } catch (e: Exception) {
                log.warn("MonitorScanProgress: failed to look up scan {}", event.scanId, e)
                null
            }

            trySend(scanProgressUpdate {
                this.event = scanProgressEvent {
                    scanId = event.scanId
                    upc = event.upc
                    status = scanInfo?.status?.toProtoScanStatus() ?: ScanStatus.SCAN_STATUS_UNKNOWN
                    scanInfo?.titleName?.let { titleName = it }
                    scanInfo?.posterUrl?.let { posterUrl = it }
                    scanInfo?.titleId?.let { titleId = it }
                }
            })
        }

        val titleListener: (TitleUpdateEvent) -> Unit = { event ->
            // Find the scan(s) linked to this title to send updates
            try {
                val scans = net.stewart.mediamanager.entity.BarcodeScan.findAll()
                for (scan in scans) {
                    val info = BarcodeScanService.scanToInfo(scan)
                    if (info.titleId == event.titleId) {
                        trySend(scanProgressUpdate {
                            this.event = scanProgressEvent {
                                scanId = info.scanId
                                upc = info.upc
                                status = info.status.toProtoScanStatus()
                                info.titleName?.let { titleName = it }
                                info.posterUrl?.let { posterUrl = it }
                                info.titleId?.let { titleId = it }
                            }
                        })
                    }
                }
            } catch (e: Exception) {
                log.warn("MonitorScanProgress: failed to handle title update for {}", event.titleId, e)
            }
        }

        Broadcaster.register(scanListener)
        Broadcaster.registerTitleListener(titleListener)
        awaitClose {
            Broadcaster.unregister(scanListener)
            Broadcaster.unregisterTitleListener(titleListener)
        }
    }

    // ========================================================================
    // Scan Detail
    // ========================================================================

    override suspend fun getScanDetail(request: ScanIdRequest): ScanDetailResponse {
        val detail = ScanDetailService.getDetail(request.scanId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Scan not found: ${request.scanId}"))

        return scanDetailResponse {
            scanId = detail.scan.id!!
            upc = detail.scan.upc
            status = detail.status.toProtoScanStatus()
            detail.mediaItem?.product_name?.let { productName = it }
            mediaFormat = (detail.mediaItem?.media_format ?: "DVD").toProtoMediaFormat()
            detail.title?.id?.let { titleId = it }
            detail.title?.name?.let { titleName = it }
            detail.title?.posterUrl(net.stewart.mediamanager.entity.PosterSize.FULL)?.let { posterUrl = it }
            detail.title?.release_year?.let { releaseYear = it }
            detail.title?.description?.let { description = it }
            enrichmentStatus = (detail.title?.enrichment_status ?: "PENDING").toProtoEnrichmentStatus()
            detail.mediaItem?.purchase_place?.let { purchasePlace = it }
            detail.mediaItem?.purchase_date?.let { purchaseDate = it.toProtoCalendarDate() }
            detail.mediaItem?.purchase_price?.let { purchasePrice = it.toDouble() }
            photos.addAll(detail.photos.map { it.toProtoPhotoInfo() })
        }
    }

    override suspend fun assignTmdb(request: AssignTmdbRequest): AssignTmdbResponse {
        val mediaType = when (request.mediaType) {
            MediaType.MEDIA_TYPE_TV -> "TV"
            else -> "MOVIE"
        }
        return when (val result = ScanDetailService.assignTmdb(request.titleId, request.tmdbId, mediaType)) {
            is ScanDetailService.AssignResult.Assigned -> assignTmdbResponse {
                merged = false
            }
            is ScanDetailService.AssignResult.Merged -> assignTmdbResponse {
                merged = true
                mergedTitleName = result.mergedTitleName
            }
            is ScanDetailService.AssignResult.NotFound -> throw StatusException(
                Status.NOT_FOUND.withDescription(result.message)
            )
        }
    }

    override suspend fun addTitle(request: AddTitleRequest): AddTitleResponse {
        if (request.tmdbId == 0) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("TMDB ID required"))
        }
        if (request.mediaType == MediaType.MEDIA_TYPE_UNKNOWN) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Media type required"))
        }
        if (request.mediaFormat == MediaFormat.MEDIA_FORMAT_UNKNOWN) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Media format required"))
        }

        val entityMediaType = request.mediaType.toEntityMediaType()
        val entityFormat = request.mediaFormat.toEntityMediaFormat()

        val result = net.stewart.mediamanager.service.AddTitleService.addFromTmdb(
            tmdbId = request.tmdbId,
            mediaType = entityMediaType,
            mediaFormat = entityFormat,
            seasonsInput = if (request.hasSeasons()) request.seasons else null
        )

        return addTitleResponse {
            titleId = result.titleId
            titleName = result.titleName
            alreadyExisted = result.alreadyExisted
        }
    }

    override suspend fun updatePurchaseInfo(request: UpdatePurchaseInfoRequest): Empty {
        val date = if (request.hasPurchaseDate()) {
            java.time.LocalDate.of(request.purchaseDate.year, request.purchaseDate.month.number, request.purchaseDate.day)
        } else null
        val price = if (request.hasPurchasePrice()) {
            java.math.BigDecimal.valueOf(request.purchasePrice)
        } else null
        val place = if (request.hasPurchasePlace()) request.purchasePlace else null

        ScanDetailService.updatePurchaseInfo(request.scanId, place, date, price)
        return empty {}
    }

    override suspend fun uploadOwnershipPhoto(request: UploadOwnershipPhotoRequest): UploadOwnershipPhotoResponse {
        val scan = net.stewart.mediamanager.entity.BarcodeScan.findById(request.scanId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Scan not found: ${request.scanId}"))

        val photoId = if (scan.media_item_id != null) {
            OwnershipPhotoService.store(request.photoData.toByteArray(), request.contentType, scan.media_item_id!!)
        } else {
            OwnershipPhotoService.storeForUpc(request.photoData.toByteArray(), request.contentType, scan.upc)
        }

        return uploadOwnershipPhotoResponse { this.photoId = photoId }
    }

    override suspend fun deleteOwnershipPhoto(request: DeleteOwnershipPhotoRequest): Empty {
        OwnershipPhotoService.delete(request.photoId)
        return empty {}
    }

    // ========================================================================
    // Transcode Management
    // ========================================================================

    override suspend fun listLinkedTranscodes(request: PaginationRequest): LinkedTranscodeResponse {
        val allTranscodes = TranscodeEntity.findAll()
            .filter { it.file_path != null }
        val titlesById = TitleEntity.findAll().associateBy { it.id }

        val sorted = allTranscodes.sortedBy { tc ->
            titlesById[tc.title_id]?.name?.lowercase() ?: ""
        }

        val page = maxOf(request.page, 1)
        val limit = if (request.limit > 0) request.limit else 50
        val total = sorted.size
        val totalPages = if (total == 0) 1 else (total + limit - 1) / limit
        val start = (page - 1) * limit
        val pageItems = sorted.drop(start).take(limit)

        return linkedTranscodeResponse {
            transcodes.addAll(pageItems.map { tc ->
                val title = titlesById[tc.title_id]
                linkedTranscodeItem {
                    transcodeId = tc.id!!
                    titleName = title?.name ?: "Unknown"
                    titleId = tc.title_id
                    tc.file_path?.let { filePath = it }
                    mediaFormat = tc.media_format.toProtoMediaFormat()
                    matchMethod = tc.match_method.toProtoMatchMethod()
                    fileExists = tc.file_path?.let { File(it).exists() } ?: false
                    highQualityTranscodeAvailable = tc.file_path != null
                    lowStorageTranscodeAvailable = tc.for_mobile_available
                }
            })
            pagination = paginationInfo {
                this.total = total
                this.page = page
                this.limit = limit
                this.totalPages = totalPages
            }
        }
    }

    override suspend fun unlinkTranscode(request: TranscodeIdRequest): Empty {
        val transcode = TranscodeEntity.findById(request.transcodeId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Transcode not found"))

        // Reset the corresponding DiscoveredFile to UNMATCHED
        val discoveredFiles = DiscoveredFile.findAll().filter {
            it.file_path == transcode.file_path && it.match_status != DiscoveredFileStatus.UNMATCHED.name
        }
        for (df in discoveredFiles) {
            df.match_status = DiscoveredFileStatus.UNMATCHED.name
            df.matched_title_id = null
            df.matched_episode_id = null
            df.match_method = null
            df.save()
        }

        // Delete associated TranscodeLease records
        TranscodeLease.findAll()
            .filter { it.transcode_id == transcode.id }
            .forEach { it.delete() }

        // Check if the episode should be deleted (no other transcodes reference it)
        val episodeId = transcode.episode_id
        transcode.delete()

        if (episodeId != null) {
            val otherTranscodes = TranscodeEntity.findAll().any { it.episode_id == episodeId }
            if (!otherTranscodes) {
                EpisodeEntity.findById(episodeId)?.delete()
            }
        }

        return empty {}
    }

    override suspend fun listUnmatchedFiles(request: Empty): UnmatchedResponse {
        val unmatchedFiles = DiscoveredFile.findAll()
            .filter { it.match_status == DiscoveredFileStatus.UNMATCHED.name }

        val allTitles = TitleEntity.findAll()

        val items = unmatchedFiles.map { df ->
            val suggestions = if (!df.parsed_title.isNullOrBlank()) {
                FuzzyMatchService.findSuggestions(df.parsed_title!!, allTitles)
            } else {
                emptyList()
            }
            val best = suggestions.firstOrNull()

            unmatchedFile {
                id = df.id!!
                filePath = df.file_path
                best?.let {
                    suggestedTitle = it.title.name
                    suggestedTitleId = it.title.id!!
                    matchScore = it.score
                }
            }
        }

        return unmatchedResponse {
            unmatched.addAll(items)
            total = items.size
        }
    }

    override suspend fun acceptUnmatched(request: UnmatchedIdRequest): AcceptUnmatchedResponse {
        val df = DiscoveredFile.findById(request.unmatchedId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Unmatched file not found"))

        if (df.parsed_title.isNullOrBlank()) {
            throw StatusException(Status.FAILED_PRECONDITION.withDescription("No parsed title to match"))
        }

        val allTitles = TitleEntity.findAll()
        val suggestions = FuzzyMatchService.findSuggestions(df.parsed_title!!, allTitles)
        val best = suggestions.firstOrNull()
            ?: throw StatusException(Status.NOT_FOUND.withDescription("No matching title found"))

        val count = linkDiscoveredFileToTitle(df, best.title)

        return acceptUnmatchedResponse {
            linked = count > 0
            titleName = best.title.name
        }
    }

    override suspend fun ignoreUnmatched(request: UnmatchedIdRequest): Empty {
        val df = DiscoveredFile.findById(request.unmatchedId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Unmatched file not found"))

        df.match_status = DiscoveredFileStatus.IGNORED.name
        df.save()

        return empty {}
    }

    override suspend fun linkUnmatched(request: LinkUnmatchedRequest): AcceptUnmatchedResponse {
        val df = DiscoveredFile.findById(request.unmatchedId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Unmatched file not found"))

        val title = TitleEntity.findById(request.titleId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Title not found"))

        val count = linkDiscoveredFileToTitle(df, title)

        return acceptUnmatchedResponse {
            linked = count > 0
            titleName = title.name
        }
    }

    // ========================================================================
    // Data Quality
    // ========================================================================

    override suspend fun listDataQuality(request: DataQualityRequest): DataQualityResponse {
        var titles = TitleEntity.findAll()

        // Filter by enrichment status if provided
        if (request.hasStatus()) {
            val statusName = when (request.status) {
                EnrichmentStatus.ENRICHMENT_STATUS_PENDING -> EnrichmentStatusEnum.PENDING.name
                EnrichmentStatus.ENRICHMENT_STATUS_ENRICHED -> EnrichmentStatusEnum.ENRICHED.name
                EnrichmentStatus.ENRICHMENT_STATUS_SKIPPED -> EnrichmentStatusEnum.SKIPPED.name
                EnrichmentStatus.ENRICHMENT_STATUS_FAILED -> EnrichmentStatusEnum.FAILED.name
                EnrichmentStatus.ENRICHMENT_STATUS_REASSIGNMENT_REQUESTED -> EnrichmentStatusEnum.REASSIGNMENT_REQUESTED.name
                EnrichmentStatus.ENRICHMENT_STATUS_ABANDONED -> EnrichmentStatusEnum.ABANDONED.name
                else -> null
            }
            if (statusName != null) {
                titles = titles.filter { it.enrichment_status == statusName }
            }
        }

        titles = titles.sortedBy { it.name.lowercase() }

        // Pre-load cast and genre data for issue detection
        val castByTitle = CastMemberEntity.findAll().groupBy { it.title_id }
        val genresByTitle = TitleGenre.findAll().groupBy { it.title_id }

        val page = maxOf(request.page, 1)
        val limit = if (request.limit > 0) request.limit else 50
        val total = titles.size
        val totalPages = if (total == 0) 1 else (total + limit - 1) / limit
        val start = (page - 1) * limit
        val pageItems = titles.drop(start).take(limit)

        return dataQualityResponse {
            items.addAll(pageItems.map { title ->
                dataQualityItem {
                    titleId = title.id!!
                    name = title.name
                    enrichmentStatus = title.enrichment_status.toProtoEnrichmentStatus()
                    mediaType = title.media_type.toProtoMediaType()
                    title.posterUrl(PosterSize.THUMBNAIL)?.let { posterUrl = it }
                    // Compute issues
                    val issueList = mutableListOf<DataQualityIssue>()
                    if (title.poster_path == null) issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_POSTER)
                    if (title.description.isNullOrBlank()) issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_DESCRIPTION)
                    if (title.tmdb_id == null) issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_TMDB_ID)
                    if (title.release_year == null) issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_YEAR)
                    if (title.content_rating == null) issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CONTENT_RATING)
                    if (title.backdrop_path == null) issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_BACKDROP)
                    if (castByTitle[title.id].isNullOrEmpty()) issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CAST)
                    if (genresByTitle[title.id].isNullOrEmpty()) issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_GENRES)
                    if (title.enrichment_status == EnrichmentStatusEnum.FAILED.name) {
                        issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_ENRICHMENT_FAILED)
                    }
                    if (title.enrichment_status == EnrichmentStatusEnum.ABANDONED.name) {
                        issueList.add(DataQualityIssue.DATA_QUALITY_ISSUE_ENRICHMENT_ABANDONED)
                    }
                    issues.addAll(issueList)
                }
            })
            pagination = paginationInfo {
                this.total = total
                this.page = page
                this.limit = limit
                this.totalPages = totalPages
            }
        }
    }

    override suspend fun reEnrich(request: TitleIdRequest): Empty {
        val title = TitleEntity.findById(request.titleId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Title not found"))

        title.enrichment_status = EnrichmentStatusEnum.PENDING.name
        title.retry_after = null
        title.updated_at = LocalDateTime.now()
        title.save()

        return empty {}
    }

    override suspend fun deleteTitle(request: TitleIdRequest): Empty {
        val titleId = request.titleId
        TitleEntity.findById(titleId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Title not found"))

        // Cascade delete all related records
        TitleTag.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        TitleGenre.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        CastMemberEntity.findAll().filter { it.title_id == titleId }.forEach { it.delete() }

        // Delete transcode leases, then transcodes
        val transcodeIds = TranscodeEntity.findAll()
            .filter { it.title_id == titleId }
            .mapNotNull { it.id }
            .toSet()
        if (transcodeIds.isNotEmpty()) {
            TranscodeLease.findAll()
                .filter { it.transcode_id in transcodeIds }
                .forEach { it.delete() }
        }
        TranscodeEntity.findAll().filter { it.title_id == titleId }.forEach { it.delete() }

        EpisodeEntity.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        MediaItemTitle.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        UserTitleFlag.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        TitleSeason.findAll().filter { it.title_id == titleId }.forEach { it.delete() }

        TitleEntity.deleteById(titleId)
        SearchIndexService.onTitleDeleted(titleId)

        return empty {}
    }

    override suspend fun updateTitleMetadata(request: UpdateTitleMetadataRequest): Empty {
        val title = TitleEntity.findById(request.titleId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Title not found"))

        if (request.hasName()) title.name = request.name
        if (request.hasDescription()) title.description = request.description
        if (request.hasReleaseYear()) title.release_year = request.releaseYear

        title.updated_at = LocalDateTime.now()
        title.save()
        SearchIndexService.onTitleChanged(request.titleId)

        return empty {}
    }

    // ========================================================================
    // Purchase Wishes
    // ========================================================================

    override suspend fun listPurchaseWishes(request: Empty): PurchaseWishListResponse {
        val aggregates = WishListService.getMediaWishVoteCounts()

        return purchaseWishListResponse {
            wishes.addAll(aggregates.map { agg ->
                purchaseWish {
                    tmdbId = agg.tmdbId
                    mediaType = agg.tmdbMediaType.toProtoMediaType()
                    title = agg.displayTitle
                    agg.tmdbPosterPath?.let { posterUrl = "https://image.tmdb.org/t/p/w500$it" }
                    agg.tmdbReleaseYear?.let { releaseYear = it }
                    voteCount = agg.voteCount
                    voters.addAll(agg.voters)
                    acquisitionStatus = agg.acquisitionStatus.toProtoAcquisitionStatus()
                    agg.seasonNumber?.let { seasonNumber = it }
                    lifecycleStage = agg.lifecycleStage.toProtoWishLifecycleStage()
                    agg.titleId?.let { titleId = it }
                }
            })
        }
    }

    override suspend fun updatePurchaseWishStatus(request: UpdatePurchaseWishStatusRequest): Empty {
        // Find the matching aggregate to pass to WishListService
        val aggregates = WishListService.getMediaWishVoteCounts()
        val requestedSeason = if (request.hasSeasonNumber()) request.seasonNumber else null
        val requestedMediaType = when (request.mediaType) {
            MediaType.MEDIA_TYPE_MOVIE -> net.stewart.mediamanager.entity.MediaType.MOVIE.name
            MediaType.MEDIA_TYPE_TV -> net.stewart.mediamanager.entity.MediaType.TV.name
            MediaType.MEDIA_TYPE_PERSONAL -> net.stewart.mediamanager.entity.MediaType.PERSONAL.name
            else -> null
        }
        val agg = aggregates.firstOrNull {
            it.tmdbId == request.tmdbId &&
                it.tmdbMediaType == requestedMediaType &&
                it.seasonNumber == requestedSeason
        } ?: throw StatusException(
            Status.NOT_FOUND.withDescription(
                "No visible wish found for tmdb_id=${request.tmdbId} media_type=$requestedMediaType season=$requestedSeason"
            )
        )

        val entityStatus = when (request.status) {
            AcquisitionStatus.ACQUISITION_STATUS_UNKNOWN -> net.stewart.mediamanager.entity.AcquisitionStatus.UNKNOWN
            AcquisitionStatus.ACQUISITION_STATUS_NOT_AVAILABLE -> net.stewart.mediamanager.entity.AcquisitionStatus.NOT_AVAILABLE
            AcquisitionStatus.ACQUISITION_STATUS_REJECTED -> net.stewart.mediamanager.entity.AcquisitionStatus.REJECTED
            AcquisitionStatus.ACQUISITION_STATUS_ORDERED -> net.stewart.mediamanager.entity.AcquisitionStatus.ORDERED
            AcquisitionStatus.ACQUISITION_STATUS_OWNED -> net.stewart.mediamanager.entity.AcquisitionStatus.OWNED
            AcquisitionStatus.ACQUISITION_STATUS_NEEDS_ASSISTANCE -> net.stewart.mediamanager.entity.AcquisitionStatus.NEEDS_ASSISTANCE
            else -> net.stewart.mediamanager.entity.AcquisitionStatus.UNKNOWN
        }

        WishListService.setAcquisitionStatus(agg, entityStatus)

        return empty {}
    }

    // ========================================================================
    // Rip Backlog
    // ========================================================================

    override suspend fun listRipBacklog(request: PaginationRequest): RipBacklogResponse {
        val allTranscodes = TranscodeEntity.findAll()
        val transcodesByTitle = allTranscodes.groupBy { it.title_id }
        val titlesWithLinkedFile = allTranscodes
            .filter { it.file_path != null }
            .map { it.title_id }
            .toSet()

        // Titles with MediaItemTitle (owned) but no file-linked transcode
        val ownedTitleIds = MediaItemTitle.findAll().map { it.title_id }.toSet()

        val allTitles = TitleEntity.findAll()
        val backlogTitles = allTitles.filter { title ->
            title.enrichment_status == EnrichmentStatusEnum.ENRICHED.name &&
                !title.hidden &&
                title.id in ownedTitleIds &&
                title.id !in titlesWithLinkedFile
        }

        val wishCounts = WishListService.getRipPriorityCounts()

        // Sort by wish count desc, then by name
        val sorted = backlogTitles.sortedWith(
            compareByDescending<TitleEntity> { wishCounts[it.id] ?: 0 }
                .thenBy { it.name.lowercase() }
        )

        val page = maxOf(request.page, 1)
        val limit = if (request.limit > 0) request.limit else 50
        val total = sorted.size
        val totalPages = if (total == 0) 1 else (total + limit - 1) / limit
        val start = (page - 1) * limit
        val pageItems = sorted.drop(start).take(limit)

        return ripBacklogResponse {
            items.addAll(pageItems.map { title ->
                ripBacklogItem {
                    titleId = title.id!!
                    name = title.name
                    title.posterUrl(PosterSize.THUMBNAIL)?.let { posterUrl = it }
                    mediaType = title.media_type.toProtoMediaType()
                    title.release_year?.let { year = it }
                    wishCount = wishCounts[title.id] ?: 0
                }
            })
            pagination = paginationInfo {
                this.total = total
                this.page = page
                this.limit = limit
                this.totalPages = totalPages
            }
        }
    }

    // ========================================================================
    // Tags
    // ========================================================================

    override suspend fun listTags(request: Empty): AdminTagListResponse {
        val tags = TagService.getAllTags()
        val counts = TagService.getTagTitleCounts()

        return adminTagListResponse {
            this.tags.addAll(tags.map { tag ->
                adminTagListItem {
                    id = tag.id!!
                    name = tag.name
                    color = tag.bg_color.toProtoColor()
                    titleCount = counts[tag.id] ?: 0
                }
            })
        }
    }

    override suspend fun createTag(request: CreateTagRequest): CreateTagResponse {
        val name = request.name.trim()
        val bgColor = request.color.hex

        if (name.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Tag name cannot be blank"))
        }

        if (!TagService.isNameUnique(name)) {
            // Find the existing tag with this name
            val existing = TagEntity.findAll().firstOrNull { it.name.equals(name, ignoreCase = true) }
            return createTagResponse {
                result = CreateTagResult.CREATE_TAG_RESULT_DUPLICATE
                id = existing?.id ?: 0
            }
        }

        val user = currentUser()
        val tag = TagService.createTag(name, bgColor, user.id)

        return createTagResponse {
            result = CreateTagResult.CREATE_TAG_RESULT_CREATED
            id = tag.id!!
        }
    }

    override suspend fun updateTag(request: UpdateTagRequest): Empty {
        val existing = TagEntity.findById(request.tagId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Tag not found"))

        val name = if (request.hasName()) request.name.trim() else existing.name
        val bgColor = if (request.hasColor()) request.color.hex else existing.bg_color

        if (name.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Tag name cannot be blank"))
        }

        if (!TagService.isNameUnique(name, request.tagId)) {
            throw StatusException(Status.ALREADY_EXISTS.withDescription("Tag name already exists"))
        }

        TagService.updateTag(request.tagId, name, bgColor)

        return empty {}
    }

    override suspend fun deleteTag(request: TagIdRequest): Empty {
        TagEntity.findById(request.tagId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Tag not found"))

        TagService.deleteTag(request.tagId)

        return empty {}
    }

    // ========================================================================
    // User Management
    // ========================================================================

    override suspend fun listUsers(request: Empty): UserListResponse {
        val users = AppUser.findAll().sortedBy { it.username.lowercase() }
        return userListResponse {
            this.users.addAll(users.map { it.toUserInfo() })
        }
    }

    override suspend fun createUser(request: CreateUserRequest): CreateUserResponse {
        val username = request.username.trim()
        if (username.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Username cannot be blank"))
        }

        // Check username uniqueness (case-insensitive)
        val existingUser = AppUser.findAll().firstOrNull {
            it.username.equals(username, ignoreCase = true)
        }
        if (existingUser != null) {
            throw StatusException(Status.ALREADY_EXISTS.withDescription("Username already taken"))
        }

        // Validate password
        val violations = PasswordService.validate(request.password, username)
        if (violations.isNotEmpty()) {
            throw StatusException(
                Status.INVALID_ARGUMENT.withDescription("Password policy: ${violations.joinToString("; ")}")
            )
        }

        val now = LocalDateTime.now()
        val user = AppUser(
            username = username,
            display_name = request.displayName.ifBlank { username },
            password_hash = PasswordService.hash(request.password),
            access_level = 1,
            must_change_password = request.forceChange,
            created_at = now,
            updated_at = now
        )
        user.save()

        log.info("Admin created user '{}' (id={})", user.username, user.id)

        return createUserResponse {
            id = user.id!!
            this.username = user.username
        }
    }

    override suspend fun updateUserRole(request: UpdateUserRoleRequest): Empty {
        val user = AppUser.findById(request.userId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("User not found"))

        val newLevel = when (request.accessLevel) {
            AccessLevel.ACCESS_LEVEL_VIEWER -> 1
            AccessLevel.ACCESS_LEVEL_ADMIN -> 2
            else -> throw StatusException(Status.INVALID_ARGUMENT.withDescription("Invalid access level"))
        }

        // Prevent demoting the last admin
        if (user.isAdmin() && newLevel < 2 && AuthService.countAdmins() <= 1) {
            throw StatusException(
                Status.FAILED_PRECONDITION.withDescription("Cannot demote the last admin")
            )
        }

        user.access_level = newLevel
        user.updated_at = LocalDateTime.now()
        user.save()

        // Invalidate sessions so the user picks up the new role
        AuthService.invalidateUserSessions(user.id!!)

        return empty {}
    }

    override suspend fun updateUserRatingCeiling(request: UpdateUserRatingCeilingRequest): Empty {
        val user = AppUser.findById(request.userId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("User not found"))

        val ceiling: Int? = if (request.hasCeiling()) {
            when (request.ceiling) {
                RatingLevel.RATING_LEVEL_CHILDREN -> 2
                RatingLevel.RATING_LEVEL_GENERAL -> 3
                RatingLevel.RATING_LEVEL_TEEN -> 4
                RatingLevel.RATING_LEVEL_MATURE -> 5
                RatingLevel.RATING_LEVEL_ADULT -> 6
                else -> null // UNKNOWN → unrestricted
            }
        } else {
            null // absent → unrestricted
        }

        user.rating_ceiling = ceiling
        user.updated_at = LocalDateTime.now()
        user.save()

        return empty {}
    }

    override suspend fun unlockUser(request: UserIdRequest): Empty {
        val user = AppUser.findById(request.userId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("User not found"))

        user.locked = false
        user.updated_at = LocalDateTime.now()
        user.save()

        return empty {}
    }

    override suspend fun forcePasswordChange(request: UserIdRequest): Empty {
        val user = AppUser.findById(request.userId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("User not found"))

        user.must_change_password = true
        user.updated_at = LocalDateTime.now()
        user.save()

        return empty {}
    }

    override suspend fun resetPassword(request: ResetPasswordRequest): Empty {
        val user = AppUser.findById(request.userId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("User not found"))

        val violations = PasswordService.validate(request.newPassword, user.username, user.password_hash)
        if (violations.isNotEmpty()) {
            throw StatusException(
                Status.INVALID_ARGUMENT.withDescription("Password policy: ${violations.joinToString("; ")}")
            )
        }

        user.password_hash = PasswordService.hash(request.newPassword)
        user.must_change_password = request.forceChange
        user.updated_at = LocalDateTime.now()
        user.save()

        AuthService.invalidateUserSessions(user.id!!)

        return empty {}
    }

    override suspend fun deleteUser(request: UserIdRequest): Empty {
        val caller = currentUser()
        val targetId = request.userId

        // Prevent self-deletion
        if (caller.id == targetId) {
            throw StatusException(Status.FAILED_PRECONDITION.withDescription("Cannot delete your own account"))
        }

        val user = AppUser.findById(targetId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("User not found"))

        // Prevent deleting the last admin
        if (user.isAdmin() && AuthService.countAdmins() <= 1) {
            throw StatusException(
                Status.FAILED_PRECONDITION.withDescription("Cannot delete the last admin")
            )
        }

        AuthService.invalidateUserSessions(targetId)
        user.delete()

        log.info("Admin deleted user '{}' (id={})", user.username, targetId)

        return empty {}
    }

    // ========================================================================
    // Settings
    // ========================================================================

    override suspend fun getSettings(request: Empty): SettingsResponse {
        val allConfig = AppConfig.findAll()
        val configByKey = allConfig.associateBy { it.config_key }

        return settingsResponse {
            // Iterate all known setting keys and emit only those that have a mapping
            for (settingKey in SettingKey.entries) {
                val configKey = settingKey.toConfigKey() ?: continue
                val rawVal = configByKey[configKey]?.config_val ?: ""
                // Mask sensitive values — show presence but not content
                val configVal = if (settingKey in SENSITIVE_SETTINGS && rawVal.isNotBlank()) {
                    "••••••••"
                } else {
                    rawVal
                }
                settings.add(setting {
                    key = settingKey
                    value = configVal
                })
            }
        }
    }

    companion object {
        /** Settings whose values should be masked in GetSettings responses. */
        private val SENSITIVE_SETTINGS = setOf(
            SettingKey.SETTING_KEY_KEEPA_API_KEY
        )
    }

    override suspend fun updateSetting(request: UpdateSettingRequest): Empty {
        val configKey = request.key.toConfigKey()
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("Unknown setting key"))

        val existing = AppConfig.findAll().firstOrNull { it.config_key == configKey }
        if (existing != null) {
            existing.config_val = request.value
            existing.save()
        } else {
            AppConfig(
                config_key = configKey,
                config_val = request.value
            ).save()
        }

        // Refresh legal requirements cache if a legal setting was changed
        val legalKeys = setOf("privacy_policy_url", "privacy_policy_version",
            "ios_terms_of_use_url", "ios_terms_of_use_version",
            "web_terms_of_use_url", "web_terms_of_use_version")
        if (configKey in legalKeys) {
            net.stewart.mediamanager.service.LegalRequirements.refresh()
        }

        return empty {}
    }

    // ========================================================================
    // Camera Admin
    // ========================================================================

    override suspend fun listAdminCameras(request: Empty): AdminCameraListResponse {
        return adminCameraListResponse {
            cameras.addAll(CameraAdminService.listAll().map { it.toAdminProto() })
        }
    }

    override suspend fun createCamera(request: CreateCameraRequest): AdminCamera {
        val streamName = if (request.hasStreamName()) request.streamName
            else CameraAdminService.generateStreamName(request.name)
        val camera = CameraAdminService.create(
            name = request.name,
            rtspUrl = request.rtspUrl,
            snapshotUrl = request.snapshotUrl,
            streamName = streamName,
            enabled = request.enabled
        )
        return camera.toAdminProto()
    }

    override suspend fun updateCamera(request: UpdateCameraRequest): AdminCamera {
        val camera = CameraAdminService.update(
            id = request.cameraId,
            name = request.name,
            rtspUrl = request.rtspUrl,
            snapshotUrl = request.snapshotUrl,
            streamName = request.streamName,
            enabled = request.enabled
        )
        return camera.toAdminProto()
    }

    override suspend fun deleteCamera(request: CameraIdRequest): Empty {
        CameraAdminService.delete(request.cameraId)
        return empty {}
    }

    override suspend fun reorderCameras(request: ReorderCamerasRequest): Empty {
        CameraAdminService.reorder(request.cameraIdsList)
        return empty {}
    }
}
