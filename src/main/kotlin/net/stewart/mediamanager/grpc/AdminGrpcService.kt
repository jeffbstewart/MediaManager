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
import net.stewart.mediamanager.service.DiscoveredFileLinkService
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
import net.stewart.mediamanager.service.AmazonImportService
import net.stewart.mediamanager.service.WishListService
import net.stewart.mediamanager.entity.Artist as ArtistEntity
import net.stewart.mediamanager.entity.ArtistMembership as ArtistMembershipEntity
import net.stewart.mediamanager.entity.ArtistType as ArtistTypeEnum
import net.stewart.mediamanager.entity.Author as AuthorEntity
import net.stewart.mediamanager.entity.BookSeries as BookSeriesEntity
import net.stewart.mediamanager.entity.MediaFormat as MediaFormatEnum
import net.stewart.mediamanager.entity.MediaType as MediaTypeEnum
import net.stewart.mediamanager.entity.Track as TrackEntity
import net.stewart.mediamanager.entity.TitleArtist as TitleArtistEntity
import net.stewart.mediamanager.entity.TitleAuthor as TitleAuthorEntity
import net.stewart.mediamanager.entity.UnmatchedAudio as UnmatchedAudioEntity
import net.stewart.mediamanager.entity.UnmatchedAudioStatus as UnmatchedAudioStatusEnum
import net.stewart.mediamanager.entity.UnmatchedBook as UnmatchedBookEntity
import net.stewart.mediamanager.entity.UnmatchedBookStatus as UnmatchedBookStatusEnum
import net.stewart.mediamanager.service.ArtistEnrichmentAgent
import net.stewart.mediamanager.service.AudioTranscodeCache
import net.stewart.mediamanager.service.FirstPartyImageMigrationVerifier
import net.stewart.mediamanager.service.AuthorEnrichmentAgent
import net.stewart.mediamanager.service.BookIngestionService
import net.stewart.mediamanager.service.OpenLibraryHttpService
import net.stewart.mediamanager.service.OpenLibraryResult
import net.stewart.mediamanager.service.OpenLibraryService
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

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
                                titleId = info.titleId
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

        val count = DiscoveredFileLinkService.linkToTitle(df, best.title)

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

        val count = DiscoveredFileLinkService.linkToTitle(df, title)

        return acceptUnmatchedResponse {
            linked = count > 0
            titleName = title.name
        }
    }

    // ========================================================================
    // Data Quality
    // ========================================================================

    override suspend fun listDataQuality(request: DataQualityRequest): DataQualityResponse {
        // Scope maps to the set of media_type strings we consider. UNKNOWN
        // preserves the original behavior (video only) for legacy callers.
        val scopedMediaTypes: Set<String> = when (request.scope) {
            DataQualityScope.DATA_QUALITY_SCOPE_BOOK -> setOf(MediaTypeEnum.BOOK.name)
            DataQualityScope.DATA_QUALITY_SCOPE_AUDIO -> setOf(MediaTypeEnum.ALBUM.name)
            else -> setOf(MediaTypeEnum.MOVIE.name, MediaTypeEnum.TV.name, MediaTypeEnum.PERSONAL.name)
        }

        var titles = TitleEntity.findAll().filter { it.media_type in scopedMediaTypes }

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

        // Pre-load join data needed for the various scope-specific issue
        // checks. Each branch below only uses the maps it cares about,
        // but pre-loading once keeps the per-title loop O(1).
        val castByTitle = CastMemberEntity.findAll().groupBy { it.title_id }
        val genresByTitle = TitleGenre.findAll().groupBy { it.title_id }
        val authorsByTitle = TitleAuthorEntity.findAll().groupBy { it.title_id }
        val artistsByTitle = TitleArtistEntity.findAll().groupBy { it.title_id }
        val tracksByTitle = TrackEntity.findAll().groupBy { it.title_id }

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
                    issues.addAll(computeIssues(
                        title, castByTitle, genresByTitle,
                        authorsByTitle, artistsByTitle, tracksByTitle
                    ))
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

    // Scope-aware issue detection. Each media_type runs only the checks
    // that make sense for it — so an album never reports NO_TMDB_ID, a
    // book never reports NO_CAST, etc.
    private fun computeIssues(
        title: TitleEntity,
        castByTitle: Map<Long, List<CastMemberEntity>>,
        genresByTitle: Map<Long, List<TitleGenre>>,
        authorsByTitle: Map<Long, List<TitleAuthorEntity>>,
        artistsByTitle: Map<Long, List<TitleArtistEntity>>,
        tracksByTitle: Map<Long, List<TrackEntity>>
    ): List<DataQualityIssue> {
        val issues = mutableListOf<DataQualityIssue>()

        // Universal: poster, description, year, enrichment state.
        if (title.poster_path == null) issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_POSTER)
        if (title.description.isNullOrBlank()) issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_DESCRIPTION)
        if (title.release_year == null && title.first_publication_year == null) {
            issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_YEAR)
        }
        if (title.enrichment_status == EnrichmentStatusEnum.FAILED.name) {
            issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_ENRICHMENT_FAILED)
        }
        if (title.enrichment_status == EnrichmentStatusEnum.ABANDONED.name) {
            issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_ENRICHMENT_ABANDONED)
        }

        when (title.media_type) {
            MediaTypeEnum.BOOK.name -> {
                // Content rating applies to books (kids vs adult); TMDB/cast/
                // backdrop/genres do not — skip those.
                if (title.content_rating == null) {
                    issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CONTENT_RATING)
                }
                if (title.open_library_work_id.isNullOrBlank()) {
                    issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_OPENLIBRARY_ID)
                }
                if (authorsByTitle[title.id].isNullOrEmpty()) {
                    issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_AUTHORS)
                }
            }
            MediaTypeEnum.ALBUM.name -> {
                // Albums don't carry content ratings, TMDB ids, cast, or
                // backdrops. They DO have an MBID, tracks, and album artists.
                if (title.musicbrainz_release_group_id.isNullOrBlank()) {
                    issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_MUSICBRAINZ_ID)
                }
                if (tracksByTitle[title.id].isNullOrEmpty()) {
                    issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_TRACKS)
                }
                if (artistsByTitle[title.id].isNullOrEmpty()) {
                    issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_ALBUM_ARTISTS)
                }
            }
            else -> {
                // Video: MOVIE / TV / PERSONAL — original set of checks.
                if (title.tmdb_id == null) issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_TMDB_ID)
                if (title.content_rating == null) issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CONTENT_RATING)
                if (title.backdrop_path == null) issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_BACKDROP)
                if (castByTitle[title.id].isNullOrEmpty()) issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CAST)
                if (genresByTitle[title.id].isNullOrEmpty()) issues.add(DataQualityIssue.DATA_QUALITY_ISSUE_NO_GENRES)
            }
        }

        return issues
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

    override suspend fun addTagToTitle(request: TagTitleRequest): Empty {
        TagService.addTagToTitle(request.titleId, request.tagId)
        return empty {}
    }

    override suspend fun removeTagFromTitle(request: TagTitleRequest): Empty {
        TagService.removeTagFromTitle(request.titleId, request.tagId)
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
        AuthService.invalidateHasUsersCache()

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
        AuthService.invalidateHasUsersCache()

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
        private val MBID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val OL_WORK_RE = Regex("^OL\\d+W$")
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

    // ========================================================================
    // Amazon Import
    // ========================================================================

    override suspend fun importAmazonOrders(request: ImportAmazonOrdersRequest): ImportAmazonOrdersResponse {
        val user = currentUser()
        return try {
            val rows = if (request.filename.endsWith(".zip", ignoreCase = true)) {
                AmazonImportService.parseZip(request.csvData.newInput())
            } else {
                AmazonImportService.parseCsv(request.csvData.newInput())
            }
            val result = AmazonImportService.importRows(user.id!!, rows)
            importAmazonOrdersResponse {
                imported = result.inserted
                skipped = result.skipped
            }
        } catch (e: Exception) {
            importAmazonOrdersResponse {
                error = e.message ?: "Import failed"
            }
        }
    }

    override suspend fun searchAmazonOrders(request: SearchAmazonOrdersRequest): SearchAmazonOrdersResponse {
        val user = currentUser()
        val query = if (request.hasQuery()) request.query else ""
        val limit = if (request.limit > 0) request.limit else 200
        val orders = AmazonImportService.searchOrders(
            userId = user.id!!,
            query = query,
            mediaOnly = request.mediaOnly,
            unlinkedOnly = request.unlinkedOnly,
            hideCancelled = request.hideCancelled,
            limit = limit
        )
        return searchAmazonOrdersResponse {
            this.orders.addAll(orders.map { it.toProto() })
        }
    }

    override suspend fun linkAmazonOrder(request: LinkAmazonOrderRequest): Empty {
        val user = currentUser()
        val order = net.stewart.mediamanager.entity.AmazonOrder.findById(request.amazonOrderId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Order not found"))
        if (order.user_id != user.id) {
            throw StatusException(Status.PERMISSION_DENIED.withDescription("Not your order"))
        }
        AmazonImportService.linkToMediaItem(request.amazonOrderId, request.mediaItemId)
        return empty {}
    }

    override suspend fun unlinkAmazonOrder(request: AmazonOrderIdRequest): Empty {
        val user = currentUser()
        val order = net.stewart.mediamanager.entity.AmazonOrder.findById(request.amazonOrderId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Order not found"))
        if (order.user_id != user.id) {
            throw StatusException(Status.PERMISSION_DENIED.withDescription("Not your order"))
        }
        AmazonImportService.unlinkFromMediaItem(request.amazonOrderId)
        return empty {}
    }

    override suspend fun getAmazonOrderSummary(request: Empty): AmazonOrderSummaryResponse {
        val user = currentUser()
        val (total, _, linked) = AmazonImportService.countOrders(user.id!!)
        // Count media-related separately
        val allOrders = AmazonImportService.searchOrders(user.id!!, "", mediaOnly = true, unlinkedOnly = false, hideCancelled = false, limit = 100000)
        return amazonOrderSummaryResponse {
            this.total = total
            this.mediaRelated = allOrders.size
            this.linked = linked
        }
    }

    private fun net.stewart.mediamanager.entity.AmazonOrder.toProto(): AmazonOrder = amazonOrder {
        id = this@toProto.id!!
        orderId = this@toProto.order_id
        asin = this@toProto.asin
        productName = this@toProto.product_name
        this@toProto.order_date?.let { orderDate = it.toLocalDate().toString() }
        this@toProto.unit_price?.let { unitPrice = it.toDouble() }
        this@toProto.product_condition?.let { productCondition = it }
        this@toProto.order_status?.let { orderStatus = it }
        this@toProto.linked_media_item_id?.let { linkedMediaItemId = it }
        // Look up linked title name
        this@toProto.linked_media_item_id?.let { mediaItemId ->
            val joins = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
                .filter { it.media_item_id == mediaItemId }
            val titleId = joins.firstOrNull()?.title_id
            titleId?.let { tid ->
                net.stewart.mediamanager.entity.Title.findById(tid)?.let { linkedTitleName = it.name }
            }
        }
    }

    // ========================================================================
    // Expand Multi-Packs
    // ========================================================================

    override suspend fun listPendingExpansions(request: Empty): PendingExpansionsResponse {
        val items = net.stewart.mediamanager.entity.MediaItem.findAll()
            .filter { it.expansion_status == net.stewart.mediamanager.entity.ExpansionStatus.NEEDS_EXPANSION.name }
        return pendingExpansionsResponse {
            this.items.addAll(items.map { item ->
                pendingExpansionItem {
                    mediaItemId = item.id!!
                    item.upc?.let { upc = it }
                    productName = item.product_name ?: ""
                    mediaFormat = item.media_format.toProtoMediaFormat()
                    estimatedTitleCount = item.title_count
                }
            })
        }
    }

    override suspend fun getExpansionDetail(request: MediaItemIdRequest): ExpansionDetailResponse {
        val item = net.stewart.mediamanager.entity.MediaItem.findById(request.mediaItemId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Media item not found"))
        val joins = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
            .filter { it.media_item_id == item.id!! }
            .sortedBy { it.disc_number }
        return expansionDetailResponse {
            mediaItemId = item.id!!
            productName = item.product_name ?: ""
            mediaFormat = item.media_format.toProtoMediaFormat()
            estimatedTitleCount = item.title_count
            linkedTitles.addAll(joins.mapNotNull { join ->
                val title = net.stewart.mediamanager.entity.Title.findById(join.title_id) ?: return@mapNotNull null
                expansionLinkedTitle {
                    joinId = join.id!!
                    titleId = title.id!!
                    name = title.name
                    title.release_year?.let { releaseYear = it }
                    title.poster_path?.let { posterPath = it }
                    discNumber = join.disc_number
                }
            })
        }
    }

    override suspend fun addTitleToExpansion(request: AddTitleToExpansionRequest): AddTitleToExpansionResponse {
        val item = net.stewart.mediamanager.entity.MediaItem.findById(request.mediaItemId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Media item not found"))
        // Limit titles per expansion to prevent abuse
        val existingCount = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
            .count { it.media_item_id == item.id!! }
        if (existingCount >= 50) {
            throw StatusException(Status.RESOURCE_EXHAUSTED.withDescription("Maximum 50 titles per multi-pack"))
        }
        val mediaType = request.mediaType.toEntityMediaType()
        val tmdbKey = net.stewart.mediamanager.entity.TmdbId(request.tmdbId, mediaType)
        val tmdbService = net.stewart.mediamanager.service.TmdbService()
        val details = try { tmdbService.getDetails(tmdbKey) } catch (_: Exception) { null }

        val now = java.time.LocalDateTime.now()
        var title = net.stewart.mediamanager.entity.Title.findAll().firstOrNull { it.tmdbKey() == tmdbKey }
        val alreadyExisted = title != null
        if (title == null) {
            title = net.stewart.mediamanager.entity.Title(
                name = details?.title ?: "Unknown",
                media_type = tmdbKey.typeString,
                tmdb_id = tmdbKey.id,
                release_year = details?.releaseYear,
                description = details?.overview,
                poster_path = details?.posterPath,
                enrichment_status = net.stewart.mediamanager.entity.EnrichmentStatus.REASSIGNMENT_REQUESTED.name,
                created_at = now,
                updated_at = now
            )
            title.save()
            net.stewart.mediamanager.service.SearchIndexService.onTitleChanged(title.id!!)
        }

        // Next disc number
        val existingJoins = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
            .filter { it.media_item_id == item.id!! }
        val nextDisc = (existingJoins.maxOfOrNull { it.disc_number } ?: 0) + 1

        val join = net.stewart.mediamanager.entity.MediaItemTitle(
            media_item_id = item.id!!,
            title_id = title.id!!,
            disc_number = nextDisc
        )
        join.save()

        net.stewart.mediamanager.service.WishListService.syncPhysicalOwnership(title.id!!)
        net.stewart.mediamanager.service.WishListService.fulfillMediaWishes(tmdbKey)

        return addTitleToExpansionResponse {
            titleId = title.id!!
            titleName = title.name
            discNumber = nextDisc
            this.alreadyExisted = alreadyExisted
        }
    }

    override suspend fun removeTitleFromExpansion(request: RemoveTitleFromExpansionRequest): Empty {
        val join = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
            .firstOrNull { it.media_item_id == request.mediaItemId && it.title_id == request.titleId }
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Title not linked to this item"))
        join.delete()

        // If title has no TMDB ID and no other links, delete it
        val title = net.stewart.mediamanager.entity.Title.findById(request.titleId)
        if (title != null && title.tmdb_id == null) {
            val otherLinks = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
                .any { it.title_id == request.titleId }
            if (!otherLinks) {
                title.delete()
            }
        }
        return empty {}
    }

    override suspend fun markExpanded(request: MediaItemIdRequest): Empty {
        val item = net.stewart.mediamanager.entity.MediaItem.findById(request.mediaItemId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Media item not found"))
        val joins = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
            .filter { it.media_item_id == item.id!! }

        // Retire placeholder titles (those without tmdb_id that were created during scan)
        val placeholders = joins.mapNotNull { join ->
            val t = net.stewart.mediamanager.entity.Title.findById(join.title_id)
            if (t != null && t.tmdb_id == null && t.enrichment_status == net.stewart.mediamanager.entity.EnrichmentStatus.SKIPPED.name) t else null
        }
        for (ph in placeholders) {
            // Unlink placeholder from this media item
            net.stewart.mediamanager.entity.MediaItemTitle.findAll()
                .filter { it.media_item_id == item.id!! && it.title_id == ph.id!! }
                .forEach { it.delete() }
            // If orphaned, delete
            val otherLinks = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
                .any { it.title_id == ph.id!! }
            if (!otherLinks) {
                ph.delete()
            }
        }

        // Count remaining linked titles
        val remaining = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
            .count { it.media_item_id == item.id!! }

        item.expansion_status = net.stewart.mediamanager.entity.ExpansionStatus.EXPANDED.name
        item.title_count = remaining
        item.updated_at = java.time.LocalDateTime.now()
        item.save()
        return empty {}
    }

    override suspend fun markNotMultiPack(request: MediaItemIdRequest): Empty {
        val item = net.stewart.mediamanager.entity.MediaItem.findById(request.mediaItemId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Media item not found"))
        item.expansion_status = net.stewart.mediamanager.entity.ExpansionStatus.SINGLE.name
        item.title_count = 1
        item.updated_at = java.time.LocalDateTime.now()
        item.save()

        // Reset placeholder title to PENDING so it gets enriched
        val joins = net.stewart.mediamanager.entity.MediaItemTitle.findAll()
            .filter { it.media_item_id == item.id!! }
        for (join in joins) {
            val title = net.stewart.mediamanager.entity.Title.findById(join.title_id)
            if (title != null && title.enrichment_status == net.stewart.mediamanager.entity.EnrichmentStatus.SKIPPED.name) {
                title.enrichment_status = net.stewart.mediamanager.entity.EnrichmentStatus.PENDING.name
                title.updated_at = java.time.LocalDateTime.now()
                title.save()
            }
        }
        return empty {}
    }

    // ========================================================================
    // Valuation
    // ========================================================================

    override suspend fun listValuations(request: ValuationRequest): ValuationResponse {
        val pageSize = if (request.pageSize > 0) request.pageSize else 50
        val query = if (request.hasQuery()) request.query.lowercase() else null

        var items = net.stewart.mediamanager.entity.MediaItem.findAll()
        if (query != null) {
            items = items.filter { it.product_name?.lowercase()?.contains(query) == true }
        }
        if (request.unpricedOnly) {
            items = items.filter { it.purchase_price == null }
        }
        items = items.sortedBy { it.product_name?.lowercase() }

        val total = items.size
        val paged = items.drop(request.page * pageSize).take(pageSize)

        val allItems = net.stewart.mediamanager.entity.MediaItem.findAll()
        val totalPurchase = allItems.mapNotNull { it.purchase_price?.toDouble() }.sum()
        val totalReplacement = allItems.mapNotNull { it.replacement_value?.toDouble() }.sum()

        return valuationResponse {
            this.items.addAll(paged.map { it.toValuationProto() })
            totalCount = total
            totalItems = allItems.size
            totalPurchaseValue = totalPurchase
            totalReplacementValue = totalReplacement
        }
    }

    private fun net.stewart.mediamanager.entity.MediaItem.toValuationProto(): ValuationItem {
        val joins = MediaItemTitle.findAll().filter { it.media_item_id == this.id!! }
        val titleNames = joins.mapNotNull { join ->
            net.stewart.mediamanager.entity.Title.findById(join.title_id)?.name
        }
        val photoCount = OwnershipPhoto.findAll().count { it.media_item_id == this.id!! }
        return valuationItem {
            mediaItemId = this@toValuationProto.id!!
            this@toValuationProto.upc?.let { upc = it }
            productName = this@toValuationProto.product_name ?: ""
            this.titleNames.addAll(titleNames)
            mediaFormat = this@toValuationProto.media_format.toProtoMediaFormat()
            this@toValuationProto.purchase_place?.let { purchasePlace = it }
            this@toValuationProto.purchase_date?.let { purchaseDate = it.toString() }
            this@toValuationProto.purchase_price?.let { purchasePrice = it.toDouble() }
            this@toValuationProto.replacement_value?.let { replacementValue = it.toDouble() }
            this@toValuationProto.replacement_value_updated_at?.let { replacementValueDate = it.toLocalDate().toString() }
            this@toValuationProto.override_asin?.let { overrideAsin = it }
            this.photoCount = photoCount
        }
    }

    override suspend fun updateMediaItem(request: UpdateMediaItemRequest): Empty {
        val item = net.stewart.mediamanager.entity.MediaItem.findById(request.mediaItemId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Media item not found"))
        if (request.hasPurchasePlace()) item.purchase_place = request.purchasePlace.ifBlank { null }
        if (request.hasPurchaseDate()) item.purchase_date = try { java.time.LocalDate.parse(request.purchaseDate) } catch (_: Exception) { null }
        if (request.hasPurchasePrice()) item.purchase_price = java.math.BigDecimal.valueOf(request.purchasePrice)
        if (request.hasReplacementValue()) item.replacement_value = java.math.BigDecimal.valueOf(request.replacementValue)
        if (request.hasOverrideAsin()) item.override_asin = request.overrideAsin.ifBlank { null }
        item.updated_at = LocalDateTime.now()
        item.save()
        return empty {}
    }

    override suspend fun setMediaItemFormat(request: SetMediaItemFormatRequest): Empty {
        val item = net.stewart.mediamanager.entity.MediaItem.findById(request.mediaItemId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Media item not found"))

        // Proto enum values are MEDIA_FORMAT_DVD etc. — strip the
        // prefix so the name matches the Kotlin entity enum (and the
        // validator) which just uses "DVD" / "HARDBACK" / etc.
        val protoName = request.mediaFormat.name
        if (protoName == "MEDIA_FORMAT_UNKNOWN") {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("media_format is required"))
        }
        val entityName = protoName.removePrefix("MEDIA_FORMAT_")
        val validation = net.stewart.mediamanager.service.MediaFormatSwitcher.validate(item, entityName)
        if (!validation.ok) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(validation.reason))
        }

        item.media_format = entityName
        item.updated_at = LocalDateTime.now()
        if (validation.clearPrice) {
            item.replacement_value = null
            item.replacement_value_updated_at = null
        }
        item.save()
        return empty {}
    }

    override suspend fun triggerKeepaLookup(request: MediaItemIdRequest): Empty {
        val item = net.stewart.mediamanager.entity.MediaItem.findById(request.mediaItemId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Media item not found"))
        // Queue for Keepa lookup by clearing the replacement value date
        item.replacement_value_updated_at = null
        item.updated_at = LocalDateTime.now()
        item.save()
        return empty {}
    }

    // ========================================================================
    // Document Ownership
    // ========================================================================

    override suspend fun listUndocumentedItems(request: PaginationRequest): UndocumentedItemsResponse {
        val allPhotos = OwnershipPhoto.findAll()
        val documentedItemIds = allPhotos.map { it.media_item_id }.toSet()
        val undocumented = net.stewart.mediamanager.entity.MediaItem.findAll()
            .filter { it.id!! !in documentedItemIds }
            .sortedBy { it.product_name?.lowercase() }

        val pageSize = if (request.limit > 0) request.limit else 50
        val paged = undocumented.drop((request.page - 1).coerceAtLeast(0) * pageSize).take(pageSize)

        return undocumentedItemsResponse {
            items.addAll(paged.map { item ->
                val joins = MediaItemTitle.findAll().filter { it.media_item_id == item.id!! }
                val titleNames = joins.mapNotNull { join ->
                    net.stewart.mediamanager.entity.Title.findById(join.title_id)?.name
                }
                undocumentedItem {
                    mediaItemId = item.id!!
                    item.upc?.let { upc = it }
                    productName = item.product_name ?: ""
                    this.titleNames.addAll(titleNames)
                    mediaFormat = item.media_format.toProtoMediaFormat()
                }
            })
            totalCount = undocumented.size
        }
    }

    // ========================================================================
    // Family Members
    // ========================================================================

    override suspend fun listFamilyMembers(request: Empty): FamilyMemberListResponse {
        val members = net.stewart.mediamanager.entity.FamilyMember.findAll().sortedBy { it.name }
        return familyMemberListResponse {
            this.members.addAll(members.map { it.toProto() })
        }
    }

    private fun net.stewart.mediamanager.entity.FamilyMember.toProto(): FamilyMemberResponse {
        val videoCount = net.stewart.mediamanager.entity.TitleFamilyMember.findAll()
            .count { it.family_member_id == this.id!! }
        return familyMemberResponse {
            id = this@toProto.id!!
            name = this@toProto.name
            this@toProto.birth_date?.let { birthDate = it.toString() }
            this@toProto.notes?.let { notes = it }
            this.videoCount = videoCount
        }
    }

    override suspend fun createFamilyMember(request: CreateFamilyMemberRequest): FamilyMemberResponse {
        if (request.name.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Name is required"))
        }
        if (request.name.length > 100) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Name too long (max 100 characters)"))
        }
        val existing = net.stewart.mediamanager.entity.FamilyMember.findAll()
            .any { it.name.equals(request.name, ignoreCase = true) }
        if (existing) {
            throw StatusException(Status.ALREADY_EXISTS.withDescription("A family member with that name already exists"))
        }
        val member = net.stewart.mediamanager.entity.FamilyMember(
            name = request.name.trim(),
            birth_date = if (request.hasBirthDate()) try { java.time.LocalDate.parse(request.birthDate) } catch (_: Exception) { null } else null,
            notes = if (request.hasNotes()) request.notes.takeIf { it.isNotBlank() } else null,
            created_at = LocalDateTime.now()
        )
        member.save()
        return member.toProto()
    }

    override suspend fun updateFamilyMember(request: UpdateFamilyMemberRequest): Empty {
        val member = net.stewart.mediamanager.entity.FamilyMember.findById(request.familyMemberId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Family member not found"))
        if (request.name.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Name is required"))
        }
        val nameConflict = net.stewart.mediamanager.entity.FamilyMember.findAll()
            .any { it.id != member.id && it.name.equals(request.name, ignoreCase = true) }
        if (nameConflict) {
            throw StatusException(Status.ALREADY_EXISTS.withDescription("Name already in use"))
        }
        member.name = request.name.trim()
        member.birth_date = if (request.hasBirthDate()) try { java.time.LocalDate.parse(request.birthDate) } catch (_: Exception) { null } else null
        member.notes = if (request.hasNotes()) request.notes.takeIf { it.isNotBlank() } else null
        member.save()
        return empty {}
    }

    override suspend fun deleteFamilyMember(request: FamilyMemberIdRequest): Empty {
        val member = net.stewart.mediamanager.entity.FamilyMember.findById(request.familyMemberId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Family member not found"))
        // Remove all title associations
        net.stewart.mediamanager.entity.TitleFamilyMember.findAll()
            .filter { it.family_member_id == member.id!! }
            .forEach { it.delete() }
        member.delete()
        return empty {}
    }

    // ========================================================================
    // Live TV Settings
    // ========================================================================

    override suspend fun getLiveTvSettings(request: Empty): LiveTvSettingsResponse {
        val configs = AppConfig.findAll().associateBy { it.config_key }
        val activeStreams = net.stewart.mediamanager.service.LiveTvStreamManager.activeStreamCount()
        val activeTuners = net.stewart.mediamanager.entity.LiveTvTuner.findAll().count { it.enabled }
        return liveTvSettingsResponse {
            configs["live_tv_min_rating"]?.config_val?.let { minContentRating = it }
            maxStreams = configs["live_tv_max_streams"]?.config_val?.toIntOrNull() ?: 4
            idleTimeoutSeconds = configs["live_tv_idle_timeout_seconds"]?.config_val?.toIntOrNull() ?: 60
            activeTunerCount = activeTuners
            activeStreamCount = activeStreams
        }
    }

    override suspend fun updateLiveTvSettings(request: UpdateLiveTvSettingsRequest): Empty {
        if (request.hasMinContentRating()) saveConfig("live_tv_min_rating", request.minContentRating)
        if (request.maxStreams > 0) saveConfig("live_tv_max_streams", request.maxStreams.toString())
        if (request.idleTimeoutSeconds > 0) saveConfig("live_tv_idle_timeout_seconds", request.idleTimeoutSeconds.toString())
        return empty {}
    }

    override suspend fun listTuners(request: Empty): TunerListResponse {
        val tuners = net.stewart.mediamanager.entity.LiveTvTuner.findAll()
        return tunerListResponse {
            this.tuners.addAll(tuners.map { tuner ->
                val channelCount = net.stewart.mediamanager.entity.LiveTvChannel.findAll()
                    .count { it.tuner_id == tuner.id!! }
                tunerResponse {
                    id = tuner.id!!
                    name = tuner.name
                    ipAddress = tuner.ip_address
                    deviceId = tuner.device_id
                    modelNumber = tuner.model_number
                    tunerCount = tuner.tuner_count
                    enabled = tuner.enabled
                    this.channelCount = channelCount
                }
            })
        }
    }

    override suspend fun addTuner(request: AddTunerRequest): TunerResponse {
        val ip = request.ipAddress.trim()
        if (ip.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("IP address required"))
        }
        if (!Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""").matches(ip)) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Invalid IPv4 address"))
        }
        val tuner = net.stewart.mediamanager.entity.LiveTvTuner(
            name = if (request.hasName()) request.name else "HDHomeRun",
            ip_address = request.ipAddress.trim(),
            enabled = true,
            created_at = LocalDateTime.now()
        )
        tuner.save()
        return tunerResponse {
            id = tuner.id!!
            name = tuner.name
            ipAddress = tuner.ip_address
            enabled = true
            channelCount = 0
        }
    }

    override suspend fun updateTuner(request: UpdateTunerRequest): Empty {
        val tuner = net.stewart.mediamanager.entity.LiveTvTuner.findById(request.tunerId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Tuner not found"))
        tuner.name = request.name
        tuner.enabled = request.enabled
        tuner.save()
        return empty {}
    }

    override suspend fun deleteTuner(request: TunerIdRequest): Empty {
        // Delete all channels first
        net.stewart.mediamanager.entity.LiveTvChannel.findAll()
            .filter { it.tuner_id == request.tunerId }
            .forEach { it.delete() }
        net.stewart.mediamanager.entity.LiveTvTuner.findById(request.tunerId)?.delete()
        return empty {}
    }

    override suspend fun refreshTunerChannels(request: TunerIdRequest): RefreshChannelsResponse {
        val tuner = net.stewart.mediamanager.entity.LiveTvTuner.findById(request.tunerId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Tuner not found"))
        val result = net.stewart.mediamanager.service.HdHomeRunService.syncChannels(tuner.id!!, tuner.ip_address)
            ?: throw StatusException(Status.UNAVAILABLE.withDescription("Failed to sync channels from ${tuner.ip_address}"))
        return refreshChannelsResponse {
            channelsFound = result.added + result.updated
            channelsAdded = result.added
        }
    }

    override suspend fun listAdminChannels(request: TunerIdRequest): AdminChannelListResponse {
        val channels = net.stewart.mediamanager.entity.LiveTvChannel.findAll()
            .filter { it.tuner_id == request.tunerId }
            .sortedWith(compareBy({ it.display_order }, { it.guide_number.toDoubleOrNull() ?: 9999.0 }))
        return adminChannelListResponse {
            this.channels.addAll(channels.map { ch ->
                adminChannelResponse {
                    id = ch.id!!
                    guideNumber = ch.guide_number
                    guideName = ch.guide_name
                    ch.network_affiliation?.let { networkAffiliation = it }
                    receptionQuality = ch.reception_quality
                    enabled = ch.enabled
                    displayOrder = ch.display_order
                }
            })
        }
    }

    override suspend fun updateChannel(request: UpdateChannelRequest): Empty {
        val ch = net.stewart.mediamanager.entity.LiveTvChannel.findById(request.channelId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Channel not found"))
        if (request.hasNetworkAffiliation()) ch.network_affiliation = request.networkAffiliation.ifBlank { null }
        ch.reception_quality = request.receptionQuality
        ch.enabled = request.enabled
        ch.save()
        return empty {}
    }

    // ========================================================================
    // Inventory Report
    // ========================================================================

    override suspend fun generateInventoryReport(request: InventoryReportRequest): InventoryReportResponse {
        val items = net.stewart.mediamanager.entity.MediaItem.findAll()
            .sortedBy { it.product_name?.lowercase() }
        var totalPurchase = 0.0
        var totalReplacement = 0.0

        val rows = items.map { item ->
            val joins = MediaItemTitle.findAll().filter { it.media_item_id == item.id!! }
            val titleNames = joins.mapNotNull { join ->
                net.stewart.mediamanager.entity.Title.findById(join.title_id)?.name
            }
            val photoCount = OwnershipPhoto.findAll().count { it.media_item_id == item.id!! }
            item.purchase_price?.toDouble()?.let { totalPurchase += it }
            item.replacement_value?.toDouble()?.let { totalReplacement += it }
            inventoryReportRow {
                this.titleNames = titleNames.joinToString(", ").ifEmpty { item.product_name ?: "" }
                mediaFormat = item.media_format
                item.upc?.let { upc = it }
                item.purchase_date?.let { purchaseDate = it.toString() }
                item.purchase_place?.let { purchasePlace = it }
                item.purchase_price?.let { purchasePrice = it.toDouble() }
                item.replacement_value?.let { replacementValue = it.toDouble() }
                item.replacement_value_updated_at?.let { replacementValueDate = it.toLocalDate().toString() }
                this.photoCount = photoCount
            }
        }

        return inventoryReportResponse {
            this.rows.addAll(rows)
            totalItems = items.size
            totalPurchaseValue = totalPurchase
            totalReplacementValue = totalReplacement
        }
    }

    private fun saveConfig(key: String, value: String) {
        val existing = AppConfig.findAll().firstOrNull { it.config_key == key }
        if (existing != null) {
            existing.config_val = value.ifBlank { null }
            existing.save()
        } else if (value.isNotBlank()) {
            AppConfig(config_key = key, config_val = value).save()
        }
    }

    // ========================================================================
    // Unmatched Books (M4 admin queue)
    // ========================================================================

    private val openLibrary: OpenLibraryService = OpenLibraryHttpService()

    override suspend fun listUnmatchedBooks(request: Empty): UnmatchedBookListResponse {
        val rows = UnmatchedBookEntity.findAll()
            .filter { it.match_status == UnmatchedBookStatusEnum.UNMATCHED.name }
            .sortedByDescending { it.discovered_at }
        return unmatchedBookListResponse {
            items.addAll(rows.map { it.toProtoItem() })
        }
    }

    override suspend fun linkUnmatchedBookByIsbn(
        request: LinkUnmatchedBookByIsbnRequest
    ): LinkUnmatchedBookResponse {
        val row = UnmatchedBookEntity.findById(request.unmatchedBookId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("unmatched_book not found"))
        val isbn = request.isbn.trim().replace("-", "")
        if (isbn.length !in 10..13) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("isbn must be 10 or 13 digits"))
        }

        val lookup = openLibrary.lookupByIsbn(isbn)
        if (lookup !is OpenLibraryResult.Success) {
            val msg = when (lookup) {
                is OpenLibraryResult.NotFound -> "Open Library has no record of that ISBN"
                is OpenLibraryResult.Error -> "Open Library error: ${lookup.message}"
                is OpenLibraryResult.Success -> "unreachable"
            }
            throw StatusException(Status.NOT_FOUND.withDescription(msg))
        }

        val format = runCatching { MediaFormatEnum.valueOf(row.media_format) }.getOrDefault(MediaFormatEnum.EBOOK_EPUB)
        val result = BookIngestionService.ingestDigital(
            filePath = row.file_path,
            fileFormat = format,
            isbn = isbn,
            lookup = lookup.book
        )
        row.match_status = UnmatchedBookStatusEnum.LINKED.name
        row.linked_title_id = result.title.id
        row.linked_at = LocalDateTime.now()
        row.save()

        return linkUnmatchedBookResponse {
            titleId = result.title.id!!
            titleName = result.title.name
            createdNewTitle = !result.titleReused
        }
    }

    override suspend fun linkUnmatchedBookToTitle(
        request: LinkUnmatchedBookToTitleRequest
    ): LinkUnmatchedBookResponse {
        val row = UnmatchedBookEntity.findById(request.unmatchedBookId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("unmatched_book not found"))
        val title = TitleEntity.findById(request.titleId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("title not found"))
        if (title.media_type != MediaTypeEnum.BOOK.name) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("title is not a book"))
        }

        val format = runCatching { MediaFormatEnum.valueOf(row.media_format) }.getOrDefault(MediaFormatEnum.EBOOK_EPUB)
        val result = BookIngestionService.linkDigitalEditionToTitle(
            filePath = row.file_path,
            fileFormat = format,
            title = title
        )
        row.match_status = UnmatchedBookStatusEnum.LINKED.name
        row.linked_title_id = result.title.id
        row.linked_at = LocalDateTime.now()
        row.save()

        return linkUnmatchedBookResponse {
            titleId = result.title.id!!
            titleName = result.title.name
            createdNewTitle = false
        }
    }

    override suspend fun ignoreUnmatchedBook(request: UnmatchedBookIdRequest): Empty {
        val row = UnmatchedBookEntity.findById(request.unmatchedBookId)
            ?: throw StatusException(Status.NOT_FOUND)
        row.match_status = UnmatchedBookStatusEnum.IGNORED.name
        row.save()
        return Empty.getDefaultInstance()
    }

    override suspend fun searchOpenLibrary(request: SearchOpenLibraryRequest): SearchOpenLibraryResponse {
        val q = request.query.trim()
        if (q.length < 2) return searchOpenLibraryResponse {}
        val limit = if (request.limit <= 0) 10 else request.limit.coerceIn(1, 50)
        val hits = openLibrary.searchWorks(q, limit)
        return searchOpenLibraryResponse {
            this.hits.addAll(hits.map { h ->
                openLibraryHit {
                    openlibraryWorkId = h.workId
                    title = h.title
                    h.authors.firstOrNull()?.let { authorName = it }
                    h.firstPublishYear?.let { firstPublishYear = it }
                }
            })
        }
    }

    override suspend fun searchCatalogTitles(request: SearchCatalogTitlesRequest): SearchCatalogTitlesResponse {
        val q = request.query.trim()
        if (q.length < 2) return searchCatalogTitlesResponse {}
        val limit = if (request.limit <= 0) 20 else request.limit.coerceIn(1, 100)
        val mediaTypeFilter = when (request.mediaType) {
            MediaType.MEDIA_TYPE_MOVIE -> MediaTypeEnum.MOVIE.name
            MediaType.MEDIA_TYPE_TV -> MediaTypeEnum.TV.name
            MediaType.MEDIA_TYPE_PERSONAL -> MediaTypeEnum.PERSONAL.name
            MediaType.MEDIA_TYPE_BOOK -> MediaTypeEnum.BOOK.name
            MediaType.MEDIA_TYPE_ALBUM -> MediaTypeEnum.ALBUM.name
            else -> null
        }
        val lower = q.lowercase()
        val matches = TitleEntity.findAll()
            .filter { !it.hidden }
            .filter { mediaTypeFilter == null || it.media_type == mediaTypeFilter }
            .filter {
                it.name.lowercase().contains(lower) ||
                    (it.sort_name?.lowercase()?.contains(lower) == true)
            }
            .sortedBy { it.name.lowercase() }
            .take(limit)
        return searchCatalogTitlesResponse {
            this.matches.addAll(matches.map { it.toProto() })
        }
    }

    // ========================================================================
    // Unmatched Audio (M4 admin queue)
    // ========================================================================

    override suspend fun listUnmatchedAudio(request: Empty): UnmatchedAudioListResponse {
        val rows = UnmatchedAudioEntity.findAll()
            .filter { it.match_status == UnmatchedAudioStatusEnum.UNMATCHED.name }
            .sortedByDescending { it.discovered_at }
        return unmatchedAudioListResponse {
            items.addAll(rows.map { it.toProtoItem() })
        }
    }

    override suspend fun linkUnmatchedAudioToTrack(
        request: LinkUnmatchedAudioToTrackRequest
    ): LinkUnmatchedAudioResponse {
        val row = UnmatchedAudioEntity.findById(request.unmatchedAudioId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("unmatched_audio not found"))
        val track = TrackEntity.findById(request.trackId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("track not found"))
        if (track.file_path != null && track.file_path != row.file_path) {
            throw StatusException(Status.FAILED_PRECONDITION.withDescription("track already linked to a different file"))
        }

        val now = LocalDateTime.now()
        track.file_path = row.file_path
        track.updated_at = now
        track.save()

        row.match_status = UnmatchedAudioStatusEnum.LINKED.name
        row.linked_track_id = track.id
        row.linked_at = now
        row.save()

        val album = TitleEntity.findById(track.title_id)
        return linkUnmatchedAudioResponse {
            trackId = track.id!!
            albumTitleId = track.title_id
            trackName = track.name
            albumName = album?.name ?: ""
        }
    }

    override suspend fun ignoreUnmatchedAudio(request: UnmatchedAudioIdRequest): Empty {
        val row = UnmatchedAudioEntity.findById(request.unmatchedAudioId)
            ?: throw StatusException(Status.NOT_FOUND)
        row.match_status = UnmatchedAudioStatusEnum.IGNORED.name
        row.save()
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // Unmatched-audio group triage (parity with the HTTP admin endpoints)
    // ========================================================================

    private val unmatchedAudioMb: net.stewart.mediamanager.service.MusicBrainzService =
        net.stewart.mediamanager.service.MusicBrainzHttpService()

    private val unmatchedAudioMbidRegex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    override suspend fun listUnmatchedAudioGroups(request: Empty): UnmatchedAudioGroupListResponse {
        val rows = UnmatchedAudioEntity.findAll()
            .filter { it.match_status == UnmatchedAudioStatusEnum.UNMATCHED.name }
        val groups = computeUnmatchedAudioGroups(rows)
        return unmatchedAudioGroupListResponse {
            this.groups.addAll(groups.map { it.toProto() })
            totalGroups = groups.size
            totalFiles = rows.size
        }
    }

    override suspend fun searchMusicBrainzForUnmatchedAudio(
        request: SearchMusicBrainzForUnmatchedAudioRequest
    ): SearchMusicBrainzForUnmatchedAudioResponse {
        val rows = UnmatchedAudioEntity.findAll().filter { it.id in request.unmatchedAudioIdsList.toSet() }
        if (rows.isEmpty()) {
            throw StatusException(Status.NOT_FOUND.withDescription("no rows found"))
        }
        val override = if (request.hasQueryOverride()) request.queryOverride.takeIf { it.isNotBlank() }?.trim() else null

        // Direct MBID paste: skip search tiers, go straight to detail lookup.
        if (override != null && unmatchedAudioMbidRegex.matches(override)) {
            val candidates = when (val r = unmatchedAudioMb.lookupByReleaseMbid(override)) {
                is net.stewart.mediamanager.service.MusicBrainzResult.Success ->
                    listOf(candidateProto(rows, r.release))
                else -> emptyList()
            }
            return searchMusicBrainzForUnmatchedAudioResponse {
                searchArtist = "(direct MBID lookup)"
                searchAlbum = override
                this.candidates.addAll(candidates)
            }
        }

        val mbids = mutableListOf<String>()

        val dominantUpc = dominantString(rows) { it.parsed_upc }
        if (override == null && dominantUpc != null) {
            (unmatchedAudioMb.lookupByBarcode(dominantUpc)
                as? net.stewart.mediamanager.service.MusicBrainzResult.Success)?.let {
                mbids += it.release.musicBrainzReleaseId
            }
        }

        val dominantArtist = dominantString(rows) { it.parsed_album_artist }
        val dominantAlbum = dominantString(rows) { it.parsed_album }
        val (searchArtist, searchAlbum) = if (override != null) {
            val dash = override.indexOf(" - ")
            if (dash > 0) override.substring(0, dash) to override.substring(dash + 3)
            else (dominantArtist ?: "Various Artists") to override
        } else {
            (dominantArtist ?: "Various Artists") to (dominantAlbum ?: "")
        }
        if (searchAlbum.isNotBlank()) {
            mbids += unmatchedAudioMb.searchReleaseByArtistAndAlbum(searchArtist, searchAlbum)
        }

        val ordered = mbids.distinct().take(10)
        val candidates = ordered.mapNotNull { mbid ->
            (unmatchedAudioMb.lookupByReleaseMbid(mbid)
                as? net.stewart.mediamanager.service.MusicBrainzResult.Success)?.release
                ?.let { lookup -> candidateProto(rows, lookup) }
        }

        return searchMusicBrainzForUnmatchedAudioResponse {
            this.searchArtist = searchArtist
            this.searchAlbum = searchAlbum
            this.candidates.addAll(candidates)
        }
    }

    override suspend fun linkUnmatchedAudioAlbumToRelease(
        request: LinkUnmatchedAudioAlbumToReleaseRequest
    ): LinkUnmatchedAudioAlbumResponse {
        val rows = UnmatchedAudioEntity.findAll().filter { it.id in request.unmatchedAudioIdsList.toSet() }
        if (rows.isEmpty()) throw StatusException(Status.NOT_FOUND.withDescription("no rows found"))

        val lookup = (unmatchedAudioMb.lookupByReleaseMbid(request.releaseMbid)
            as? net.stewart.mediamanager.service.MusicBrainzResult.Success)?.release
            ?: throw StatusException(Status.NOT_FOUND.withDescription("MB release not found"))

        val ingest = net.stewart.mediamanager.service.MusicIngestionService.ingest(
            upc = null,
            mediaFormat = net.stewart.mediamanager.entity.MediaFormat.AUDIO_FLAC,
            lookup = lookup
        )
        // See HTTP linkAlbumToRelease — backfill any missing track slots
        // when the reused title was ingested from a different pressing.
        if (ingest.titleReused) {
            net.stewart.mediamanager.service.MusicIngestionService.syncMissingTracks(
                ingest.title.id!!, lookup
            )
        }
        val tracks = net.stewart.mediamanager.entity.Track.findAll()
            .filter { it.title_id == ingest.title.id }
        val (linked, failed) = linkUnmatchedAudioRowsToTracks(rows, tracks)
        return linkUnmatchedAudioAlbumResponse {
            titleId = ingest.title.id!!
            titleName = ingest.title.name
            this.linked = linked.size
            this.failed.addAll(failed)
        }
    }

    override suspend fun linkUnmatchedAudioAlbumManual(
        request: LinkUnmatchedAudioAlbumManualRequest
    ): LinkUnmatchedAudioAlbumResponse {
        val rows = UnmatchedAudioEntity.findAll().filter { it.id in request.unmatchedAudioIdsList.toSet() }
        if (rows.isEmpty()) throw StatusException(Status.NOT_FOUND.withDescription("no rows found"))

        val title = net.stewart.mediamanager.service.MusicIngestionService.ingestManualFromRows(rows)
        val tracks = net.stewart.mediamanager.entity.Track.findAll()
            .filter { it.title_id == title.id }
        val (linked, failed) = linkUnmatchedAudioRowsToTracks(rows, tracks)
        return linkUnmatchedAudioAlbumResponse {
            titleId = title.id!!
            titleName = title.name
            this.linked = linked.size
            this.failed.addAll(failed)
        }
    }

    // -------------------------------------------------------------------
    // Shared helpers (mirror UnmatchedAudioHttpService — keeping the two
    // surfaces deliberately parallel so the iOS admin behaves identically
    // to the web one).
    // -------------------------------------------------------------------

    private data class UnmatchedAudioGroupView(
        val groupId: String,
        val dirs: List<String>,
        val dominantAlbum: String?,
        val dominantAlbumArtist: String?,
        val dominantUpc: String?,
        val dominantMbReleaseId: String?,
        val dominantLabel: String?,
        val dominantCatalogNumber: String?,
        val discNumbers: List<Int>,
        val totalFiles: Int,
        val recordingMbidCount: Int,
        val files: List<UnmatchedAudioEntity>
    )

    private fun computeUnmatchedAudioGroups(
        rows: List<UnmatchedAudioEntity>
    ): List<UnmatchedAudioGroupView> {
        if (rows.isEmpty()) return emptyList()
        val byKey = rows.groupBy { mergeKeyForRow(it) }
        return byKey.map { (_, members) ->
            val sorted = members.sortedWith(
                compareBy({ it.parsed_disc_number ?: 1 }, { it.parsed_track_number ?: 0 })
            )
            UnmatchedAudioGroupView(
                groupId = unmatchedAudioGroupId(sorted.mapNotNull { it.id }),
                dirs = sorted.map { parentDir(it.file_path) }.distinct().sorted(),
                dominantAlbum = dominantString(sorted) { it.parsed_album },
                dominantAlbumArtist = dominantString(sorted) { it.parsed_album_artist },
                dominantUpc = dominantString(sorted) { it.parsed_upc },
                dominantMbReleaseId = dominantString(sorted) { it.parsed_mb_release_id },
                dominantLabel = dominantString(sorted) { it.parsed_label },
                dominantCatalogNumber = dominantString(sorted) { it.parsed_catalog_number },
                discNumbers = sorted.mapNotNull { it.parsed_disc_number }.distinct().sorted(),
                totalFiles = sorted.size,
                recordingMbidCount = sorted.count { !it.parsed_mb_recording_id.isNullOrBlank() },
                files = sorted
            )
        }.sortedWith(
            compareByDescending<UnmatchedAudioGroupView> { it.totalFiles }
                .thenBy { it.dominantAlbum?.lowercase() ?: it.dirs.firstOrNull()?.lowercase() ?: "" }
        )
    }

    private fun UnmatchedAudioGroupView.toProto(): UnmatchedAudioGroup = unmatchedAudioGroup {
        groupId = this@toProto.groupId
        dirs.addAll(this@toProto.dirs)
        this@toProto.dominantAlbum?.let { dominantAlbum = it }
        this@toProto.dominantAlbumArtist?.let { dominantAlbumArtist = it }
        this@toProto.dominantUpc?.let { dominantUpc = it }
        this@toProto.dominantMbReleaseId?.let { dominantMbReleaseId = it }
        this@toProto.dominantLabel?.let { dominantLabel = it }
        this@toProto.dominantCatalogNumber?.let { dominantCatalogNumber = it }
        discNumbers.addAll(this@toProto.discNumbers)
        totalFiles = this@toProto.totalFiles
        recordingMbidCount = this@toProto.recordingMbidCount
        fileIds.addAll(this@toProto.files.mapNotNull { it.id })
        files.addAll(this@toProto.files.map { row ->
            unmatchedAudioGroupFile {
                id = row.id!!
                filePath = row.file_path
                fileName = row.file_name
                row.parsed_title?.takeIf { it.isNotBlank() }?.let { parsedTitle = it }
                row.parsed_track_artist?.takeIf { it.isNotBlank() }?.let { parsedTrackArtist = it }
                row.parsed_track_number?.let { parsedTrackNumber = it }
                row.parsed_disc_number?.let { parsedDiscNumber = it }
                row.parsed_duration_seconds?.let { parsedDuration = it.toDouble().toPlaybackOffset() }
                row.parsed_mb_recording_id?.takeIf { it.isNotBlank() }?.let { parsedMbRecordingId = it }
            }
        })
    }

    private fun candidateProto(
        rows: List<UnmatchedAudioEntity>,
        lookup: net.stewart.mediamanager.service.MusicBrainzReleaseLookup
    ): MusicBrainzReleaseCandidate {
        val positions = lookup.tracks.map { it.discNumber to it.trackNumber }.toSet()
        val recordingIds = lookup.tracks.map { it.musicBrainzRecordingId }.toSet()
        val accommodates = rows.all { row ->
            val tn = row.parsed_track_number ?: return@all true
            val dn = row.parsed_disc_number ?: 1
            (dn to tn) in positions
        }
        val mbidHits = rows.count {
            it.parsed_mb_recording_id in recordingIds && !it.parsed_mb_recording_id.isNullOrBlank()
        }
        return musicBrainzReleaseCandidate {
            releaseMbid = lookup.musicBrainzReleaseId
            releaseGroupMbid = lookup.musicBrainzReleaseGroupId
            title = lookup.title
            artistCredit = lookup.albumArtistCredits.joinToString(", ") { it.name }
            lookup.releaseYear?.let { year = it }
            lookup.label?.takeIf { it.isNotBlank() }?.let { label = it }
            lookup.barcode?.takeIf { it.isNotBlank() }?.let { barcode = it }
            trackCount = lookup.tracks.size
            discCount = lookup.tracks.map { it.discNumber }.distinct().size
            accommodatesFiles = accommodates
            recordingMbidCoverage = mbidHits
        }
    }

    private fun linkUnmatchedAudioRowsToTracks(
        rows: List<UnmatchedAudioEntity>,
        tracks: List<net.stewart.mediamanager.entity.Track>
    ): Pair<List<UnmatchedAudioEntity>, List<LinkUnmatchedAudioAlbumFailure>> {
        val now = LocalDateTime.now()
        val linked = mutableListOf<UnmatchedAudioEntity>()
        val failed = mutableListOf<LinkUnmatchedAudioAlbumFailure>()
        for (row in rows) {
            val target = tracks.firstOrNull {
                !row.parsed_mb_recording_id.isNullOrBlank() &&
                    it.musicbrainz_recording_id == row.parsed_mb_recording_id
            } ?: tracks.firstOrNull {
                row.parsed_track_number != null &&
                    it.track_number == row.parsed_track_number &&
                    it.disc_number == (row.parsed_disc_number ?: 1)
            }
            if (target == null || (target.file_path != null && target.file_path != row.file_path)) {
                failed += linkUnmatchedAudioAlbumFailure {
                    filePath = row.file_path
                    reason = if (target == null) "no track slot match"
                        else "track ${target.disc_number}/${target.track_number} already linked"
                }
                continue
            }
            target.file_path = row.file_path
            target.updated_at = now
            target.save()
            row.match_status = UnmatchedAudioStatusEnum.LINKED.name
            row.linked_track_id = target.id
            row.linked_at = now
            row.save()
            linked += row
        }
        return linked to failed
    }

    private fun mergeKeyForRow(row: UnmatchedAudioEntity): String {
        val album = row.parsed_album?.takeIf { it.isNotBlank() }
        val artist = row.parsed_album_artist?.takeIf { it.isNotBlank() }
        return when {
            album != null -> "album|${album.lowercase()}|${artist?.lowercase().orEmpty()}"
            else -> "dir|${parentDir(row.file_path)}"
        }
    }

    private fun parentDir(path: String): String =
        path.substringBeforeLast('/').ifEmpty { path.substringBeforeLast('\\') }

    private fun dominantString(
        rows: List<UnmatchedAudioEntity>,
        picker: (UnmatchedAudioEntity) -> String?
    ): String? = rows.mapNotNull { picker(it)?.takeIf { s -> s.isNotBlank() } }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun unmatchedAudioGroupId(ids: List<Long>): String {
        val joined = ids.sorted().joinToString(",")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    override suspend fun searchCatalogTracks(request: SearchCatalogTracksRequest): SearchCatalogTracksResponse {
        val q = request.query.trim()
        if (q.length < 2) return searchCatalogTracksResponse {}
        val limit = if (request.limit <= 0) 50 else request.limit.coerceIn(1, 200)
        val lower = q.lowercase()

        val albumTitles = TitleEntity.findAll()
            .filter { it.media_type == MediaTypeEnum.ALBUM.name }
            .associateBy { it.id }
        val albumHitIds = albumTitles.values
            .filter {
                it.name.lowercase().contains(lower) ||
                    (it.sort_name?.lowercase()?.contains(lower) == true)
            }
            .mapNotNull { it.id }
            .toSet()

        val primaryArtistByTitle = TitleArtistEntity.findAll()
            .filter { it.artist_order == 0 }
            .associate { it.title_id to it.artist_id }
        val artistsById = ArtistEntity.findAll().associateBy { it.id }

        val hits = TrackEntity.findAll()
            .asSequence()
            .filter { it.title_id in albumHitIds || it.name.lowercase().contains(lower) }
            .sortedWith(compareBy(
                { albumTitles[it.title_id]?.name?.lowercase() ?: "" },
                { it.disc_number },
                { it.track_number }
            ))
            .take(limit)
            .mapNotNull { t ->
                val album = albumTitles[t.title_id] ?: return@mapNotNull null
                trackSearchMatch {
                    trackId = t.id!!
                    albumTitleId = album.id!!
                    trackName = t.name
                    albumName = album.name
                    primaryArtistByTitle[album.id]?.let { aid -> artistsById[aid]?.name }?.let { albumArtistName = it }
                    discNumber = t.disc_number
                    trackNumber = t.track_number
                }
            }
            .toList()
        return searchCatalogTracksResponse {
            this.matches.addAll(hits)
        }
    }

    // ========================================================================
    // Author admin
    // ========================================================================

    override suspend fun listAdminAuthors(request: ListAdminAuthorsRequest): AdminAuthorListResponse {
        val countsByAuthor = TitleAuthorEntity.findAll().groupingBy { it.author_id }.eachCount()
        val all = AuthorEntity.findAll()
        val filtered = run {
            val needle = if (request.hasQ()) request.q.trim().lowercase() else ""
            all.asSequence()
                .filter { needle.isEmpty() || it.name.lowercase().contains(needle) || it.sort_name.lowercase().contains(needle) }
                .filter { !request.issuesOnly || authorHasIssues(it) }
                .toList()
        }
        val sorted = filtered.sortedBy { it.sort_name.ifBlank { it.name }.lowercase() }
        val (paged, pagination) = paginate(sorted, request.page, request.limit)
        return adminAuthorListResponse {
            authors.addAll(paged.map { a ->
                adminAuthorItem {
                    id = a.id!!
                    name = a.name
                    sortName = a.sort_name
                    hasBiography = !a.biography.isNullOrBlank()
                    hasHeadshot = !a.headshot_path.isNullOrBlank()
                    a.open_library_author_id?.takeIf { it.isNotBlank() }?.let { openlibraryId = it }
                    ownedBookCount = countsByAuthor[a.id] ?: 0
                }
            })
            this.pagination = pagination
        }
    }

    override suspend fun updateAuthor(request: UpdateAuthorRequest): Empty {
        val author = AuthorEntity.findById(request.authorId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("author not found"))
        if (request.hasName()) author.name = request.name
        if (request.hasSortName()) author.sort_name = request.sortName
        if (request.hasBiography()) author.biography = request.biography.ifBlank { null }
        if (request.hasOpenlibraryId()) author.open_library_author_id = request.openlibraryId.ifBlank { null }
        if (request.hasWikidataId()) author.wikidata_id = request.wikidataId.ifBlank { null }
        if (request.hasBirthDate()) author.birth_date = request.birthDate.toLocalDate()
        if (request.hasDeathDate()) author.death_date = request.deathDate.toLocalDate()
        author.updated_at = LocalDateTime.now()
        author.save()
        return Empty.getDefaultInstance()
    }

    override suspend fun deleteAuthor(request: AdminAuthorIdRequest): Empty {
        val author = AuthorEntity.findById(request.authorId)
            ?: throw StatusException(Status.NOT_FOUND)
        TitleAuthorEntity.findAll().filter { it.author_id == author.id }.forEach { it.delete() }
        author.delete()
        return Empty.getDefaultInstance()
    }

    override suspend fun mergeAuthors(request: MergeAuthorsRequest): Empty {
        if (request.keepAuthorId == request.dropAuthorId) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("keep and drop must differ"))
        }
        val keep = AuthorEntity.findById(request.keepAuthorId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("keep author not found"))
        val drop = AuthorEntity.findById(request.dropAuthorId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("drop author not found"))
        val now = LocalDateTime.now()
        // Re-point title_author rows, preserving ordering on the surviving author.
        val keepTitleIds = TitleAuthorEntity.findAll()
            .filter { it.author_id == keep.id }
            .map { it.title_id }
            .toSet()
        TitleAuthorEntity.findAll()
            .filter { it.author_id == drop.id }
            .forEach { link ->
                if (link.title_id in keepTitleIds) {
                    link.delete()   // duplicate link — drop the extra row
                } else {
                    link.author_id = keep.id!!
                    link.save()
                }
            }
        drop.delete()
        keep.updated_at = now
        keep.save()
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // Artist admin
    // ========================================================================

    override suspend fun listAdminArtists(request: ListAdminArtistsRequest): AdminArtistListResponse {
        val countsByArtist = TitleArtistEntity.findAll().groupingBy { it.artist_id }.eachCount()
        val all = ArtistEntity.findAll()
        val filtered = run {
            val needle = if (request.hasQ()) request.q.trim().lowercase() else ""
            all.asSequence()
                .filter { needle.isEmpty() || it.name.lowercase().contains(needle) || it.sort_name.lowercase().contains(needle) }
                .filter { !request.issuesOnly || artistHasIssues(it) }
                .toList()
        }
        val sorted = filtered.sortedBy { it.sort_name.ifBlank { it.name }.lowercase() }
        val (paged, pagination) = paginate(sorted, request.page, request.limit)
        return adminArtistListResponse {
            artists.addAll(paged.map { a ->
                adminArtistItem {
                    id = a.id!!
                    name = a.name
                    sortName = a.sort_name
                    artistType = a.artist_type.toProtoArtistType()
                    hasBiography = !a.biography.isNullOrBlank()
                    hasHeadshot = !a.headshot_path.isNullOrBlank()
                    a.musicbrainz_artist_id?.takeIf { it.isNotBlank() }?.let { musicbrainzArtistId = it }
                    ownedAlbumCount = countsByArtist[a.id] ?: 0
                }
            })
            this.pagination = pagination
        }
    }

    override suspend fun updateArtist(request: UpdateArtistRequest): Empty {
        val artist = ArtistEntity.findById(request.artistId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("artist not found"))
        if (request.hasName()) artist.name = request.name
        if (request.hasSortName()) artist.sort_name = request.sortName
        if (request.artistType != ArtistType.ARTIST_TYPE_UNKNOWN) {
            artist.artist_type = request.artistType.toEntityArtistType().name
        }
        if (request.hasBiography()) artist.biography = request.biography.ifBlank { null }
        if (request.hasMusicbrainzArtistId()) artist.musicbrainz_artist_id = request.musicbrainzArtistId.ifBlank { null }
        if (request.hasWikidataId()) artist.wikidata_id = request.wikidataId.ifBlank { null }
        if (request.hasBeginDate()) artist.begin_date = request.beginDate.toLocalDate()
        if (request.hasEndDate()) artist.end_date = request.endDate.toLocalDate()
        artist.updated_at = LocalDateTime.now()
        artist.save()
        return Empty.getDefaultInstance()
    }

    override suspend fun deleteArtist(request: AdminArtistIdRequest): Empty {
        val artist = ArtistEntity.findById(request.artistId)
            ?: throw StatusException(Status.NOT_FOUND)
        TitleArtistEntity.findAll().filter { it.artist_id == artist.id }.forEach { it.delete() }
        ArtistMembershipEntity.findAll()
            .filter { it.group_artist_id == artist.id || it.member_artist_id == artist.id }
            .forEach { it.delete() }
        artist.delete()
        return Empty.getDefaultInstance()
    }

    override suspend fun mergeArtists(request: MergeArtistsRequest): Empty {
        if (request.keepArtistId == request.dropArtistId) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("keep and drop must differ"))
        }
        val keep = ArtistEntity.findById(request.keepArtistId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("keep artist not found"))
        val drop = ArtistEntity.findById(request.dropArtistId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("drop artist not found"))
        val keepTitleIds = TitleArtistEntity.findAll()
            .filter { it.artist_id == keep.id }
            .map { it.title_id }
            .toSet()
        TitleArtistEntity.findAll()
            .filter { it.artist_id == drop.id }
            .forEach { link ->
                if (link.title_id in keepTitleIds) link.delete()
                else { link.artist_id = keep.id!!; link.save() }
            }
        // Memberships re-pointed too; any membership where both ids were
        // the same (drop↔drop) is dropped outright.
        ArtistMembershipEntity.findAll()
            .filter { it.group_artist_id == drop.id || it.member_artist_id == drop.id }
            .forEach { m ->
                if (m.group_artist_id == drop.id) m.group_artist_id = keep.id!!
                if (m.member_artist_id == drop.id) m.member_artist_id = keep.id!!
                if (m.group_artist_id == m.member_artist_id) m.delete() else m.save()
            }
        drop.delete()
        keep.updated_at = LocalDateTime.now()
        keep.save()
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // External identifier assignment + agent-specific re-enrichment
    // ========================================================================

    override suspend fun assignExternalIdentifier(
        request: AssignExternalIdentifierRequest
    ): AssignExternalIdentifierResponse {
        val title = TitleEntity.findById(request.titleId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("title not found"))
        val value = request.value.trim()
        if (value.isEmpty()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("value required"))
        }
        when (request.kind) {
            ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_TMDB -> {
                val tmdbId = value.toIntOrNull()
                    ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("TMDB value must be integer"))
                title.tmdb_id = tmdbId
                title.media_type = request.mediaType.toEntityMediaType().name
            }
            ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_OPENLIBRARY_WORK -> {
                if (!value.matches(OL_WORK_RE)) {
                    throw StatusException(Status.INVALID_ARGUMENT.withDescription("not an OL work id"))
                }
                title.open_library_work_id = value
                title.media_type = MediaTypeEnum.BOOK.name
            }
            ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_MUSICBRAINZ_RELEASE_GROUP -> {
                if (!value.matches(MBID_RE)) {
                    throw StatusException(Status.INVALID_ARGUMENT.withDescription("not an MBID"))
                }
                title.musicbrainz_release_group_id = value
                title.media_type = MediaTypeEnum.ALBUM.name
            }
            ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_MUSICBRAINZ_RELEASE -> {
                if (!value.matches(MBID_RE)) {
                    throw StatusException(Status.INVALID_ARGUMENT.withDescription("not an MBID"))
                }
                title.musicbrainz_release_id = value
                title.media_type = MediaTypeEnum.ALBUM.name
            }
            else -> throw StatusException(Status.INVALID_ARGUMENT.withDescription("kind required"))
        }
        title.enrichment_status = EnrichmentStatusEnum.PENDING.name
        title.updated_at = LocalDateTime.now()
        title.save()
        return assignExternalIdentifierResponse {
            merged = false
        }
    }

    override suspend fun reEnrichWithAgent(request: ReEnrichWithAgentRequest): Empty {
        val title = TitleEntity.findById(request.titleId)
            ?: throw StatusException(Status.NOT_FOUND)
        // Fire-and-forget — agents log their own failures.
        val agentName = request.agent.name.removePrefix("ENRICHMENT_AGENT_")
        Thread({
            try {
                when (request.agent) {
                    EnrichmentAgent.ENRICHMENT_AGENT_TMDB,
                    EnrichmentAgent.ENRICHMENT_AGENT_OPENLIBRARY,
                    EnrichmentAgent.ENRICHMENT_AGENT_MUSICBRAINZ -> {
                        // Bounce off the existing re-enrichment pipeline by
                        // resetting status; the appropriate agent will pick
                        // it up on its next sweep based on media_type.
                        title.enrichment_status = EnrichmentStatusEnum.PENDING.name
                        title.updated_at = LocalDateTime.now()
                        title.save()
                    }
                    EnrichmentAgent.ENRICHMENT_AGENT_AUTHOR_HEADSHOT -> {
                        // Run one-off enrichment over each author linked to this title.
                        val authorIds = TitleAuthorEntity.findAll()
                            .filter { it.title_id == title.id }
                            .map { it.author_id }
                            .toSet()
                        val agent = AuthorEnrichmentAgent()
                        AuthorEntity.findAll().filter { it.id in authorIds }.forEach {
                            agent.enrichOne(it)
                        }
                    }
                    EnrichmentAgent.ENRICHMENT_AGENT_ARTIST_HEADSHOT,
                    EnrichmentAgent.ENRICHMENT_AGENT_ARTIST_PERSONNEL -> {
                        val artistIds = TitleArtistEntity.findAll()
                            .filter { it.title_id == title.id }
                            .map { it.artist_id }
                            .toSet()
                        val agent = ArtistEnrichmentAgent()
                        ArtistEntity.findAll().filter { it.id in artistIds }.forEach {
                            agent.enrichOne(it)
                        }
                    }
                    else -> log.warn("ReEnrichWithAgent called with UNKNOWN agent")
                }
            } catch (e: Exception) {
                log.warn("ReEnrichWithAgent {} for title {} failed: {}", agentName, title.id, e.message)
            }
        }, "re-enrich-${agentName.lowercase()}-${title.id}").apply {
            isDaemon = true
            start()
        }
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // Book series admin
    // ========================================================================

    override suspend fun listBookSeries(request: ListBookSeriesRequest): BookSeriesListResponse {
        val volumeCounts = TitleEntity.findAll()
            .filter { it.media_type == MediaTypeEnum.BOOK.name && it.book_series_id != null }
            .groupingBy { it.book_series_id!! }
            .eachCount()
        val needle = if (request.hasQ()) request.q.trim().lowercase() else ""
        val filtered = BookSeriesEntity.findAll()
            .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            .sortedBy { it.name.lowercase() }
        val (paged, pagination) = paginate(filtered, request.page, request.limit)
        return bookSeriesListResponse {
            series.addAll(paged.map { s ->
                bookSeriesItem {
                    id = s.id!!
                    name = s.name
                    volumeCount = volumeCounts[s.id] ?: 0
                    s.author_id?.let { primaryAuthorId = it }
                }
            })
            this.pagination = pagination
        }
    }

    override suspend fun updateBookSeries(request: UpdateBookSeriesRequest): Empty {
        val series = BookSeriesEntity.findById(request.seriesId)
            ?: throw StatusException(Status.NOT_FOUND)
        if (request.hasName()) series.name = request.name
        if (request.hasPrimaryAuthorId()) series.author_id = request.primaryAuthorId
        series.save()
        return Empty.getDefaultInstance()
    }

    override suspend fun reassignTitleToSeries(request: ReassignTitleToSeriesRequest): Empty {
        val title = TitleEntity.findById(request.titleId)
            ?: throw StatusException(Status.NOT_FOUND)
        if (title.media_type != MediaTypeEnum.BOOK.name) {
            throw StatusException(Status.FAILED_PRECONDITION.withDescription("title is not a book"))
        }
        title.book_series_id = if (request.hasSeriesId()) request.seriesId else null
        title.series_number = if (request.hasSeriesNumber()) {
            request.seriesNumber.takeIf { it.isNotBlank() }?.let {
                try { BigDecimal(it) } catch (_: Exception) {
                    throw StatusException(Status.INVALID_ARGUMENT.withDescription("series_number must be decimal"))
                }
            }
        } else null
        title.updated_at = LocalDateTime.now()
        title.save()
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // Track metadata edits
    // ========================================================================

    override suspend fun updateTrack(request: UpdateTrackRequest): Empty {
        val track = TrackEntity.findById(request.trackId)
            ?: throw StatusException(Status.NOT_FOUND)
        if (request.hasName()) track.name = request.name
        if (request.hasTrackNumber()) track.track_number = request.trackNumber
        if (request.hasDiscNumber()) track.disc_number = request.discNumber
        if (request.hasMusicbrainzRecordingId()) {
            track.musicbrainz_recording_id = request.musicbrainzRecordingId.ifBlank { null }
        }
        track.updated_at = LocalDateTime.now()
        track.save()
        return Empty.getDefaultInstance()
    }

    override suspend fun reorderTracks(request: ReorderTracksRequest): Empty {
        val tracksByAlbum = TrackEntity.findAll()
            .filter { it.title_id == request.albumTitleId }
            .associateBy { it.id }
        if (tracksByAlbum.isEmpty()) {
            throw StatusException(Status.NOT_FOUND.withDescription("album has no tracks"))
        }
        // Verify every submitted id actually belongs to this album before
        // we touch anything — reject the whole call on a mismatch so the
        // server never persists a half-applied reorder.
        for (entry in request.orderList) {
            if (entry.trackId !in tracksByAlbum) {
                throw StatusException(Status.INVALID_ARGUMENT.withDescription(
                    "track ${entry.trackId} not on album ${request.albumTitleId}"
                ))
            }
        }
        val now = LocalDateTime.now()
        for (entry in request.orderList) {
            val t = tracksByAlbum[entry.trackId] ?: continue
            t.disc_number = entry.discNumber
            t.track_number = entry.trackNumber
            t.updated_at = now
            t.save()
        }
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // Artist membership (M6 lineup editing)
    // ========================================================================

    override suspend fun listArtistMemberships(request: AdminArtistIdRequest): ArtistMembershipListResponse {
        val id = request.artistId
        val memberships = ArtistMembershipEntity.findAll()
            .filter { it.group_artist_id == id || it.member_artist_id == id }
            .sortedByDescending { it.begin_date }
        val otherIds = memberships.flatMap { listOf(it.group_artist_id, it.member_artist_id) }.toSet()
        val artistsById = ArtistEntity.findAll().filter { it.id in otherIds }.associateBy { it.id }
        return artistMembershipListResponse {
            this.memberships.addAll(memberships.map { m ->
                artistMembershipRow {
                    this.id = m.id!!
                    groupArtistId = m.group_artist_id
                    memberArtistId = m.member_artist_id
                    groupName = artistsById[m.group_artist_id]?.name ?: ""
                    memberName = artistsById[m.member_artist_id]?.name ?: ""
                    m.begin_date?.let { beginDate = it.toProtoCalendarDate() }
                    m.end_date?.let { endDate = it.toProtoCalendarDate() }
                    m.primary_instruments?.takeIf { it.isNotBlank() }
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.let { primaryInstruments.addAll(it) }
                }
            })
        }
    }

    override suspend fun upsertArtistMembership(
        request: UpsertArtistMembershipRequest
    ): ArtistMembershipResponse {
        if (request.groupArtistId == request.memberArtistId) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("group and member must differ"))
        }
        ArtistEntity.findById(request.groupArtistId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("group artist not found"))
        ArtistEntity.findById(request.memberArtistId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("member artist not found"))

        val instruments = request.primaryInstrumentsList.joinToString(",").ifBlank { null }
        val beginDate = if (request.hasBeginDate()) request.beginDate.toLocalDate() else null
        val endDate = if (request.hasEndDate()) request.endDate.toLocalDate() else null

        val row = if (request.hasId()) {
            ArtistMembershipEntity.findById(request.id)
                ?: throw StatusException(Status.NOT_FOUND.withDescription("membership not found"))
        } else {
            ArtistMembershipEntity()
        }
        row.group_artist_id = request.groupArtistId
        row.member_artist_id = request.memberArtistId
        row.begin_date = beginDate
        row.end_date = endDate
        row.primary_instruments = instruments
        row.save()
        return artistMembershipResponse { id = row.id!! }
    }

    override suspend fun deleteArtistMembership(request: MembershipIdRequest): Empty {
        val row = ArtistMembershipEntity.findById(request.membershipId)
            ?: throw StatusException(Status.NOT_FOUND)
        row.delete()
        return Empty.getDefaultInstance()
    }

    override suspend fun triggerArtistEnrichment(request: AdminArtistIdRequest): Empty {
        val artist = ArtistEntity.findById(request.artistId)
            ?: throw StatusException(Status.NOT_FOUND)
        val agent = ArtistEnrichmentAgent()
        Thread({
            try { agent.enrichOne(artist) }
            catch (e: Exception) { log.warn("triggerArtistEnrichment {} failed: {}", artist.id, e.message) }
        }, "artist-enrich-manual-${artist.id}").apply { isDaemon = true; start() }
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // Audio transcode cache admin
    // ========================================================================

    override suspend fun getAudioTranscodeCacheStatus(request: Empty): AudioTranscodeCacheStatus {
        val status = AudioTranscodeCache.status()
        return audioTranscodeCacheStatus {
            totalSizeBytes = status.totalBytes
            entryCount = status.entryCount
            status.oldestMtimeEpochMs?.let {
                oldestEntryAt = timestamp { secondsSinceEpoch = it / 1000 }
            }
        }
    }

    override suspend fun clearAudioTranscodeCache(request: ClearAudioTranscodeCacheRequest): Empty {
        if (request.hasTrackId()) {
            AudioTranscodeCache.clearForTrack(request.trackId)
        } else {
            AudioTranscodeCache.clearAll()
        }
        return Empty.getDefaultInstance()
    }

    // ========================================================================
    // Image cache migration verifier (phase 4a)
    // ========================================================================

    override suspend fun verifyFirstPartyImageMigration(
        request: Empty
    ): VerifyFirstPartyImageMigrationResponse {
        val report = FirstPartyImageMigrationVerifier.run()
        return verifyFirstPartyImageMigrationResponse {
            ownershipPhotos = report.ownership.toProto()
            localImages = report.localImages.toProto()
            safeToDeleteOldLayout = report.safeToDeleteOldLayout
            auditedAt = LocalDateTime.now().toProtoTimestamp()
        }
    }

    private fun FirstPartyImageMigrationVerifier.CategoryAudit.toProto(): FirstPartyCategoryAudit =
        firstPartyCategoryAudit {
            totalRows = this@toProto.totalRows
            legacyMissing = this@toProto.legacyMissing
            verified = this@toProto.verified
            missingNewCopy = this@toProto.missingNewCopy
            mismatchedBytes = this@toProto.mismatchedBytes
            missingSidecar = this@toProto.missingSidecar
            invalidSidecar = this@toProto.invalidSidecar
            sampleMissingNewCopy.addAll(this@toProto.sampleMissingNewCopy)
            sampleMismatchedBytes.addAll(this@toProto.sampleMismatchedBytes)
            sampleMissingSidecar.addAll(this@toProto.sampleMissingSidecar)
            sampleInvalidSidecar.addAll(this@toProto.sampleInvalidSidecar)
        }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun authorHasIssues(a: AuthorEntity): Boolean =
        a.biography.isNullOrBlank() ||
            a.headshot_path.isNullOrBlank() ||
            a.open_library_author_id.isNullOrBlank()

    private fun artistHasIssues(a: ArtistEntity): Boolean =
        a.biography.isNullOrBlank() ||
            a.headshot_path.isNullOrBlank() ||
            a.musicbrainz_artist_id.isNullOrBlank()

    private fun <T> paginate(items: List<T>, pageReq: Int, limitReq: Int): Pair<List<T>, PaginationInfo> {
        val limit = if (limitReq <= 0) 50 else limitReq.coerceIn(1, 200)
        val page = if (pageReq <= 0) 1 else pageReq
        val total = items.size
        val totalPages = if (total == 0) 1 else ((total + limit - 1) / limit)
        val from = ((page - 1) * limit).coerceAtMost(total)
        val to = (from + limit).coerceAtMost(total)
        val info = paginationInfo {
            this.total = total
            this.page = page
            this.limit = limit
            this.totalPages = totalPages
        }
        return items.subList(from, to) to info
    }

    private fun CalendarDate.toLocalDate(): LocalDate? {
        if (year == 0) return null
        val m = if (month == Month.MONTH_UNKNOWN) 1 else month.number
        val d = if (day == 0) 1 else day
        return try { LocalDate.of(year, m, d) } catch (_: Exception) { null }
    }

    private fun ArtistType.toEntityArtistType(): ArtistTypeEnum = when (this) {
        ArtistType.ARTIST_TYPE_PERSON -> ArtistTypeEnum.PERSON
        ArtistType.ARTIST_TYPE_GROUP -> ArtistTypeEnum.GROUP
        ArtistType.ARTIST_TYPE_ORCHESTRA -> ArtistTypeEnum.ORCHESTRA
        ArtistType.ARTIST_TYPE_CHOIR -> ArtistTypeEnum.CHOIR
        ArtistType.ARTIST_TYPE_OTHER -> ArtistTypeEnum.OTHER
        else -> ArtistTypeEnum.OTHER
    }

    private fun UnmatchedBookEntity.toProtoItem(): UnmatchedBookItem = unmatchedBookItem {
        id = this@toProtoItem.id!!
        filePath = this@toProtoItem.file_path
        fileName = this@toProtoItem.file_name
        this@toProtoItem.file_size_bytes?.let { fileSizeBytes = it }
        editionFormat = when (this@toProtoItem.media_format) {
            MediaFormatEnum.EBOOK_EPUB.name -> BookEditionFormat.BOOK_EDITION_FORMAT_EBOOK_EPUB
            MediaFormatEnum.EBOOK_PDF.name -> BookEditionFormat.BOOK_EDITION_FORMAT_EBOOK_PDF
            MediaFormatEnum.AUDIOBOOK_DIGITAL.name -> BookEditionFormat.BOOK_EDITION_FORMAT_AUDIOBOOK_DIGITAL
            else -> BookEditionFormat.BOOK_EDITION_FORMAT_UNKNOWN
        }
        this@toProtoItem.parsed_title?.takeIf { it.isNotBlank() }?.let { parsedTitle = it }
        this@toProtoItem.parsed_author?.takeIf { it.isNotBlank() }?.let { parsedAuthor = it }
        this@toProtoItem.parsed_isbn?.takeIf { it.isNotBlank() }?.let { parsedIsbn = it }
        status = this@toProtoItem.match_status.toUnmatchedStatus()
        this@toProtoItem.linked_title_id?.let { linkedTitleId = it }
        this@toProtoItem.discovered_at?.let { discoveredAt = it.toProtoTimestamp() }
        this@toProtoItem.linked_at?.let { linkedAt = it.toProtoTimestamp() }
    }

    private fun UnmatchedAudioEntity.toProtoItem(): UnmatchedAudioItem = unmatchedAudioItem {
        id = this@toProtoItem.id!!
        filePath = this@toProtoItem.file_path
        fileName = this@toProtoItem.file_name
        this@toProtoItem.file_size_bytes?.let { fileSizeBytes = it }
        this@toProtoItem.parsed_title?.takeIf { it.isNotBlank() }?.let { parsedTitle = it }
        this@toProtoItem.parsed_album?.takeIf { it.isNotBlank() }?.let { parsedAlbum = it }
        this@toProtoItem.parsed_album_artist?.takeIf { it.isNotBlank() }?.let { parsedAlbumArtist = it }
        this@toProtoItem.parsed_track_artist?.takeIf { it.isNotBlank() }?.let { parsedTrackArtist = it }
        this@toProtoItem.parsed_track_number?.let { parsedTrackNumber = it }
        this@toProtoItem.parsed_disc_number?.let { parsedDiscNumber = it }
        this@toProtoItem.parsed_duration_seconds?.let { parsedDuration = it.toDouble().toPlaybackOffset() }
        this@toProtoItem.parsed_mb_release_id?.takeIf { it.isNotBlank() }?.let { parsedMusicbrainzReleaseId = it }
        this@toProtoItem.parsed_mb_release_group_id?.takeIf { it.isNotBlank() }?.let { parsedMusicbrainzReleaseGroupId = it }
        this@toProtoItem.parsed_mb_recording_id?.takeIf { it.isNotBlank() }?.let { parsedMusicbrainzRecordingId = it }
        this@toProtoItem.parsed_upc?.takeIf { it.isNotBlank() }?.let { parsedUpc = it }
        this@toProtoItem.parsed_isrc?.takeIf { it.isNotBlank() }?.let { parsedIsrc = it }
        this@toProtoItem.parsed_catalog_number?.takeIf { it.isNotBlank() }?.let { parsedCatalogNumber = it }
        this@toProtoItem.parsed_label?.takeIf { it.isNotBlank() }?.let { parsedLabel = it }
        status = this@toProtoItem.match_status.toUnmatchedStatus()
        this@toProtoItem.linked_track_id?.let { linkedTrackId = it }
        this@toProtoItem.discovered_at?.let { discoveredAt = it.toProtoTimestamp() }
        this@toProtoItem.linked_at?.let { linkedAt = it.toProtoTimestamp() }
    }

    private fun String.toUnmatchedStatus(): UnmatchedStatus = when (this) {
        UnmatchedBookStatusEnum.UNMATCHED.name,
        UnmatchedAudioStatusEnum.UNMATCHED.name -> UnmatchedStatus.UNMATCHED_STATUS_UNMATCHED
        UnmatchedBookStatusEnum.LINKED.name,
        UnmatchedAudioStatusEnum.LINKED.name -> UnmatchedStatus.UNMATCHED_STATUS_LINKED
        UnmatchedBookStatusEnum.IGNORED.name,
        UnmatchedAudioStatusEnum.IGNORED.name -> UnmatchedStatus.UNMATCHED_STATUS_IGNORED
        else -> UnmatchedStatus.UNMATCHED_STATUS_UNKNOWN
    }

}
