import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2
import GRPCProtobuf
import os.log

private let logger = MMLogger(category: "GrpcClient")

/// gRPC client that manages the connection to the MediaManager server.
/// Analogous to APIClient but speaks gRPC over HTTP/2 cleartext (h2c).
///
/// Thread-safe via actor isolation. Holds the channel, service stubs,
/// and auth token. All RPC methods inject Bearer auth metadata automatically.
actor GrpcClient {
    private var grpcClient: GRPCClient<HTTP2ClientTransport.Posix>?
    private var accessToken: Data?
    private var configuredHost: String?
    private var configuredPort: Int?

    // MARK: - Configuration

    /// Create the gRPC channel to the given server.
    func configure(host: String, port: Int, useTLS: Bool = false) throws {
        logger.info("configure: host=\(host) port=\(port) tls=\(useTLS)")

        // Shut down previous client if reconfiguring
        if let old = grpcClient {
            logger.info("configure: shutting down previous client")
            old.beginGracefulShutdown()
        }

        // Use DNS resolution (not .ipv4 which requires a numeric IP address).
        // TLS for HTTPS reverse proxies (port 443); plaintext h2c for direct LAN.
        let security: HTTP2ClientTransport.Posix.TransportSecurity = useTLS ? .tls : .plaintext
        let transport = try HTTP2ClientTransport.Posix(
            target: .dns(host: host, port: port),
            transportSecurity: security
        )
        let client = GRPCClient(transport: transport)

        // Start the client's connection management background task
        Task { try await client.runConnections() }

        self.grpcClient = client
        self.configuredHost = host
        self.configuredPort = port
        logger.info("configure: client created and running for \(host):\(port)")
    }

    func setAccessToken(_ token: Data?) {
        let hasToken = token != nil
        logger.info("setAccessToken: hasToken=\(hasToken)")
        self.accessToken = token
    }

    func close() {
        logger.info("close: shutting down")
        grpcClient?.beginGracefulShutdown()
    }

    // MARK: - Auth Metadata

    /// Build metadata with Bearer auth if a token is set.
    private func authMetadata() -> Metadata {
        guard let token = accessToken, let tokenString = String(data: token, encoding: .utf8) else {
            return [:]
        }
        return ["authorization": "Bearer \(tokenString)"]
    }

    // MARK: - Service Clients

    private func requireClient() throws -> GRPCClient<HTTP2ClientTransport.Posix> {
        guard let client = grpcClient else {
            logger.error("requireClient: FAILED — grpcClient is nil (host=\(self.configuredHost ?? "never configured"), port=\(self.configuredPort ?? -1))")
            throw GrpcClientError.notConfigured
        }
        return client
    }

    var infoService: MMInfoService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMInfoService.Client(wrapping: try requireClient()) }
    }

    var authService: MMAuthService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMAuthService.Client(wrapping: try requireClient()) }
    }

    var catalogService: MMCatalogService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMCatalogService.Client(wrapping: try requireClient()) }
    }

    var playbackService: MMPlaybackService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMPlaybackService.Client(wrapping: try requireClient()) }
    }

    var downloadService: MMDownloadService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMDownloadService.Client(wrapping: try requireClient()) }
    }

    var wishListService: MMWishListService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMWishListService.Client(wrapping: try requireClient()) }
    }

    var profileService: MMProfileService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMProfileService.Client(wrapping: try requireClient()) }
    }

    var liveService: MMLiveService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMLiveService.Client(wrapping: try requireClient()) }
    }

    var adminService: MMAdminService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMAdminService.Client(wrapping: try requireClient()) }
    }

    var imageService: MMImageService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMImageService.Client(wrapping: try requireClient()) }
    }

    var observabilityService: MMObservabilityService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMObservabilityService.Client(wrapping: try requireClient()) }
    }

    var artistService: MMArtistService.Client<HTTP2ClientTransport.Posix> {
        get throws { MMArtistService.Client(wrapping: try requireClient()) }
    }

    /// Open a long-lived StreamLogs client-streaming RPC. The producer
    /// closure is handed an `RPCWriter<MMLogRecord>` and runs until it
    /// returns; the server then returns a `StreamLogsAck` summarising
    /// forwarded/rejected counts. Auth metadata is attached automatically.
    func streamLogs(
        producer: @Sendable @escaping (RPCWriter<MMLogRecord>) async throws -> Void
    ) async throws -> MMStreamLogsAck {
        try await observabilityService.streamLogs(
            metadata: authMetadata(),
            requestProducer: producer
        )
    }

    /// Auth metadata for long-lived streams (ImageStreamClient needs this).
    func authMetadataForImageStream() -> Metadata {
        return authMetadata()
    }

    // MARK: - Auth RPCs (unauthenticated)

    func discover() async throws -> MMDiscoverResponse {
        logger.info("discover: calling InfoService.Discover")
        var request = MMDiscoverRequest()
        request.platform = .ios
        let result = try await infoService.discover(request)
        logger.info("discover: success, fingerprint=\(result.serverFingerprint)")
        return result
    }

    func getInfo() async throws -> MMInfoResponse {
        logger.info("getInfo: calling InfoService.GetInfo")
        return try await infoService.getInfo(MMEmpty(), metadata: authMetadata())
    }

    func login(username: String, password: String, deviceName: String) async throws -> MMTokenResponse {
        logger.info("login: calling AuthService.Login for device=\(deviceName)")
        var request = MMLoginRequest()
        request.username = username
        request.password = password
        request.deviceName = deviceName
        request.platform = .ios
        return try await authService.login(request)
    }

    // MARK: - Legal RPCs (authenticated, gate-exempt)

    func getLegalStatus() async throws -> MMLegalStatusResponse {
        logger.info("getLegalStatus: calling AuthService.GetLegalStatus")
        var request = MMGetLegalStatusRequest()
        request.platform = .ios
        return try await authService.getLegalStatus(request, metadata: authMetadata())
    }

    func agreeToTerms(privacyPolicyVersion: Int32, termsOfUseVersion: Int32) async throws -> MMAgreeToTermsResponse {
        logger.info("agreeToTerms: calling AuthService.AgreeToTerms")
        var request = MMAgreeToTermsRequest()
        request.platform = .ios
        request.privacyPolicyVersion = privacyPolicyVersion
        request.termsOfUseVersion = termsOfUseVersion
        return try await authService.agreeToTerms(request, metadata: authMetadata())
    }

    func refresh(token: Data) async throws -> MMTokenResponse {
        var request = MMRefreshRequest()
        request.refreshToken = token
        return try await authService.refresh(request)
    }

    func revoke(token: Data) async throws {
        var request = MMRevokeRequest()
        request.refreshToken = token
        _ = try await authService.revoke(request)
    }

    func changePassword(current: String, new: String) async throws -> MMTokenResponse {
        var request = MMChangePasswordRequest()
        request.currentPassword = current
        request.newPassword = new
        return try await authService.changePassword(request, metadata: authMetadata())
    }

    func createFirstUser(username: String, password: String, displayName: String, deviceName: String) async throws -> MMTokenResponse {
        logger.info("createFirstUser: calling AuthService.CreateFirstUser")
        var request = MMCreateFirstUserRequest()
        request.username = username
        request.password = password
        request.displayName = displayName
        request.deviceName = deviceName
        return try await authService.createFirstUser(request)
    }

    // MARK: - Catalog RPCs

    func homeFeed() async throws -> MMHomeFeedResponse {
        try await catalogService.homeFeed(MMEmpty(), metadata: authMetadata())
    }

    func listTitles(type: MMMediaType, page: Int32, limit: Int32, sort: String?, query: String?, playableOnly: Bool) async throws -> MMTitlePageResponse {
        var request = MMListTitlesRequest()
        request.type = type
        request.page = page
        request.limit = limit
        if let sort { request.sort = sort }
        if let query { request.q = query }
        request.playableOnly = playableOnly
        return try await catalogService.listTitles(request, metadata: authMetadata())
    }

    func getTitleDetail(id: Int64) async throws -> MMTitleDetail {
        var request = MMTitleIdRequest()
        request.titleID = id
        return try await catalogService.getTitleDetail(request, metadata: authMetadata())
    }

    func search(query: String) async throws -> MMSearchResponse {
        var request = MMSearchRequest()
        request.query = query
        return try await catalogService.search(request, metadata: authMetadata())
    }

    func listSeasons(titleId: Int64) async throws -> MMSeasonsResponse {
        var request = MMTitleIdRequest()
        request.titleID = titleId
        return try await catalogService.listSeasons(request, metadata: authMetadata())
    }

    func listEpisodes(titleId: Int64, season: Int32) async throws -> MMEpisodesResponse {
        var request = MMListEpisodesRequest()
        request.titleID = titleId
        request.seasonNumber = season
        return try await catalogService.listEpisodes(request, metadata: authMetadata())
    }

    func setFavorite(titleId: Int64, value: Bool) async throws {
        var request = MMSetFlagRequest()
        request.titleID = titleId
        request.value = value
        _ = try await catalogService.setFavorite(request, metadata: authMetadata())
    }

    func setHidden(titleId: Int64, value: Bool) async throws {
        var request = MMSetFlagRequest()
        request.titleID = titleId
        request.value = value
        _ = try await catalogService.setHidden(request, metadata: authMetadata())
    }

    func requestRetranscode(titleId: Int64) async throws {
        var request = MMTitleIdRequest()
        request.titleID = titleId
        _ = try await catalogService.requestRetranscode(request, metadata: authMetadata())
    }

    func requestLowStorageTranscode(titleId: Int64) async throws {
        var request = MMTitleIdRequest()
        request.titleID = titleId
        _ = try await catalogService.requestLowStorageTranscode(request, metadata: authMetadata())
    }

    func dismissContinueWatching(titleId: Int64) async throws {
        var request = MMTitleIdRequest()
        request.titleID = titleId
        _ = try await catalogService.dismissContinueWatching(request, metadata: authMetadata())
    }

    func dismissMissingSeason(titleId: Int64, seasonNumber: Int32?) async throws {
        var request = MMDismissMissingSeasonRequest()
        request.titleID = titleId
        if let sn = seasonNumber { request.seasonNumber = sn }
        _ = try await catalogService.dismissMissingSeason(request, metadata: authMetadata())
    }

    // MARK: - Browse RPCs

    func getActorDetail(personId: Int32) async throws -> MMActorDetail {
        var request = MMActorIdRequest()
        request.tmdbPersonID = personId
        return try await catalogService.getActorDetail(request, metadata: authMetadata())
    }

    func listCollections() async throws -> MMCollectionListResponse {
        try await catalogService.listCollections(MMEmpty(), metadata: authMetadata())
    }

    func getCollectionDetail(id: Int32) async throws -> MMCollectionDetail {
        var request = MMCollectionIdRequest()
        request.tmdbCollectionID = id
        return try await catalogService.getCollectionDetail(request, metadata: authMetadata())
    }

    func listTags() async throws -> MMTagListResponse {
        try await catalogService.listTags(MMEmpty(), metadata: authMetadata())
    }

    func getTagDetail(id: Int64) async throws -> MMTagDetail {
        var request = MMTagIdRequest()
        request.tagID = id
        return try await catalogService.getTagDetail(request, metadata: authMetadata())
    }

    func getGenreDetail(id: Int64) async throws -> MMGenreDetail {
        var request = MMGenreIdRequest()
        request.genreID = id
        return try await catalogService.getGenreDetail(request, metadata: authMetadata())
    }

    // MARK: - Playback RPCs

    func getProgress(transcodeId: Int64) async throws -> MMPlaybackProgress {
        var request = MMTranscodeIdRequest()
        request.transcodeID = transcodeId
        return try await playbackService.getProgress(request, metadata: authMetadata())
    }

    func getChapters(transcodeId: Int64) async throws -> MMChaptersResponse {
        var request = MMTranscodeIdRequest()
        request.transcodeID = transcodeId
        return try await playbackService.getChapters(request, metadata: authMetadata())
    }

    // MARK: - Reading-progress RPCs (ebook reader)

    func getReadingProgress(mediaItemId: Int64) async throws -> MMReadingProgress {
        var request = MMReadingProgressRequest()
        request.mediaItemID = mediaItemId
        return try await playbackService.getReadingProgress(request, metadata: authMetadata())
    }

    func reportReadingProgress(mediaItemId: Int64, locator: String, fraction: Double?) async throws {
        var request = MMReportReadingProgressRequest()
        request.mediaItemID = mediaItemId
        request.locator = locator
        if let fraction { request.fraction = fraction }
        _ = try await playbackService.reportReadingProgress(request, metadata: authMetadata())
    }

    func reportProgress(transcodeId: Int64, position: Double, duration: Double?) async throws {
        var request = MMReportProgressRequest()
        request.transcodeID = transcodeId
        request.position = MMPlaybackOffset.with { $0.seconds = position }
        if let dur = duration {
            request.duration = MMPlaybackOffset.with { $0.seconds = dur }
        }
        _ = try await playbackService.reportProgress(request, metadata: authMetadata())
    }

    // MARK: - Profile RPCs

    func getProfile() async throws -> MMProfileResponse {
        try await profileService.getProfile(MMEmpty(), metadata: authMetadata())
    }

    func updateTvQuality(_ quality: MMQuality) async throws {
        var request = MMUpdateTvQualityRequest()
        request.minQuality = quality
        _ = try await profileService.updateTvQuality(request, metadata: authMetadata())
    }

    func listSessions() async throws -> MMSessionListResponse {
        try await profileService.listSessions(MMEmpty(), metadata: authMetadata())
    }

    func deleteSession(id: Int64, type: MMSessionType) async throws {
        var request = MMDeleteSessionRequest()
        request.sessionID = id
        request.type = type
        _ = try await profileService.deleteSession(request, metadata: authMetadata())
    }

    func deleteOtherSessions() async throws {
        _ = try await profileService.deleteOtherSessions(MMEmpty(), metadata: authMetadata())
    }

    // MARK: - Live RPCs

    func listCameras() async throws -> MMCameraListResponse {
        try await liveService.listCameras(MMEmpty(), metadata: authMetadata())
    }

    func listTvChannels() async throws -> MMTvChannelListResponse {
        try await liveService.listTvChannels(MMEmpty(), metadata: authMetadata())
    }

    func warmUpStream(path: String) async throws {
        var request = MMWarmUpStreamRequest()
        request.path = path
        _ = try await liveService.warmUpStream(request, metadata: authMetadata())
    }

    // MARK: - Download RPCs (metadata only; file downloads stay HTTP)

    func listDownloadsAvailable() async throws -> MMDownloadAvailableResponse {
        try await downloadService.listAvailable(MMEmpty(), metadata: authMetadata())
    }

    func getManifest(transcodeId: Int64) async throws -> MMDownloadManifest {
        var request = MMManifestRequest()
        request.transcodeID = transcodeId
        return try await downloadService.getManifest(request, metadata: authMetadata())
    }

    func batchManifest(transcodeIds: [Int64]) async throws -> MMBatchManifestResponse {
        var request = MMBatchManifestRequest()
        request.transcodeIds = transcodeIds
        return try await downloadService.batchManifest(request, metadata: authMetadata())
    }

    // MARK: - Book downloads (ebook / PDF / digital audiobook)

    func getBookManifest(mediaItemId: Int64) async throws -> MMBookManifest {
        var request = MMBookManifestRequest()
        request.mediaItemID = mediaItemId
        return try await downloadService.getBookManifest(request, metadata: authMetadata())
    }

    /// Server-streaming download of a book file (EPUB / PDF / audiobook).
    /// Mirrors `downloadFile`: invokes `onChunk` for each ~1MB chunk so
    /// the caller can stream straight to disk without buffering the
    /// whole file in memory.
    func downloadBookFile(
        mediaItemId: Int64,
        offset: Int64 = 0,
        onChunk: @Sendable @escaping (MMDownloadChunk) async -> Void
    ) async throws {
        var request = MMDownloadBookFileRequest()
        request.mediaItemID = mediaItemId
        request.offset = offset
        try await downloadService.downloadBookFile(
            request,
            metadata: authMetadata()
        ) { response in
            for try await chunk in response.messages {
                await onChunk(chunk)
            }
        }
    }

    /// Server-streaming file download. Calls onChunk for each ~1MB chunk received.
    func downloadFile(
        transcodeId: Int64,
        offset: Int64 = 0,
        onChunk: @Sendable @escaping (MMDownloadChunk) async -> Void
    ) async throws {
        var request = MMDownloadFileRequest()
        request.transcodeID = transcodeId
        request.offset = offset
        try await downloadService.downloadFile(
            request,
            metadata: authMetadata()
        ) { response in
            for try await chunk in response.messages {
                await onChunk(chunk)
            }
        }
    }

    // MARK: - WishList RPCs

    func wishList() async throws -> MMWishListResponse {
        try await wishListService.listWishes(MMEmpty(), metadata: authMetadata())
    }

    /// Image artwork is intentionally not a parameter — server populates
    /// poster_path on the wish row from TMDB metadata using `tmdbId` +
    /// `mediaType`. Clients no longer traffic in image URLs / paths.
    func addWish(tmdbId: Int32, mediaType: MMMediaType, title: String, releaseYear: Int32?, seasonNumber: Int32?) async throws -> Int64 {
        var request = MMAddWishRequest()
        request.tmdbID = tmdbId
        request.mediaType = mediaType
        request.title = title
        if let ry = releaseYear { request.releaseYear = ry }
        if let sn = seasonNumber { request.seasonNumber = sn }
        let response = try await wishListService.addWish(request, metadata: authMetadata())
        return response.id
    }

    func deleteWish(id: Int64) async throws {
        var request = MMWishIdRequest()
        request.wishID = id
        _ = try await wishListService.cancelWish(request, metadata: authMetadata())
    }

    func voteOnWish(id: Int64, vote: Bool) async throws {
        var request = MMVoteRequest()
        request.wishID = id
        request.vote = vote
        _ = try await wishListService.voteOnWish(request, metadata: authMetadata())
    }

    func dismissWish(id: Int64) async throws {
        var request = MMWishIdRequest()
        request.wishID = id
        _ = try await wishListService.dismissWish(request, metadata: authMetadata())
    }

    func transcodeWishList() async throws -> MMTranscodeWishListResponse {
        try await wishListService.listTranscodeWishes(MMEmpty(), metadata: authMetadata())
    }

    func deleteTranscodeWish(titleId: Int64) async throws {
        var request = MMTitleIdRequest()
        request.titleID = titleId
        _ = try await wishListService.removeTranscodeWish(request, metadata: authMetadata())
    }

    func searchTmdb(query: String, type: MMMediaType) async throws -> MMTmdbSearchResponse {
        var request = MMTmdbSearchRequest()
        request.query = query
        request.type = type
        return try await wishListService.searchTmdb(request, metadata: authMetadata())
    }

    // MARK: - Author / Book RPCs

    func listAuthors(page: Int32, limit: Int32, sort: MMAuthorSort, query: String?, playableOnly: Bool = true, hiddenOnly: Bool = false) async throws -> MMAuthorListResponse {
        var request = MMListAuthorsRequest()
        request.page = page
        request.limit = limit
        request.sort = sort
        if let query, !query.isEmpty { request.q = query }
        request.playableOnly = playableOnly
        request.hiddenOnly = hiddenOnly
        return try await artistService.listAuthors(request, metadata: authMetadata())
    }

    func adminSetAuthorHidden(authorId: Int64, hidden: Bool) async throws {
        var request = MMSetAuthorHiddenRequest()
        request.authorID = authorId
        request.hidden = hidden
        _ = try await adminService.setAuthorHidden(request, metadata: authMetadata())
    }

    func getAuthorDetail(id: Int64, playableOnly: Bool = true) async throws -> MMAuthorDetail {
        var request = MMAuthorIdRequest()
        request.authorID = id
        request.playableOnly = playableOnly
        return try await artistService.getAuthorDetail(request, metadata: authMetadata())
    }

    func getBookSeriesDetail(id: Int64, playableOnly: Bool = true) async throws -> MMBookSeriesDetail {
        var request = MMBookSeriesIdRequest()
        request.seriesID = id
        request.playableOnly = playableOnly
        return try await catalogService.getBookSeriesDetail(request, metadata: authMetadata())
    }
    // MARK: - Admin RPCs

    func adminTranscodeStatus() async throws -> MMTranscodeStatusResponse {
        try await adminService.getTranscodeStatus(MMEmpty(), metadata: authMetadata())
    }

    /// Server-streaming: initial snapshot + live delta updates.
    /// The closure receives each `MMTranscodeStatusUpdate` as it arrives.
    func adminMonitorTranscodeStatus(
        onUpdate: @Sendable @escaping (MMTranscodeStatusUpdate) async -> Void
    ) async throws {
        let metadata = authMetadata()
        try await adminService.monitorTranscodeStatus(
            MMEmpty(),
            metadata: metadata
        ) { response in
            for try await message in response.messages {
                await onUpdate(message)
            }
        }
    }

    func adminBuddyStatus() async throws -> MMBuddyStatusResponse {
        try await adminService.getBuddyStatus(MMEmpty(), metadata: authMetadata())
    }

    // MARK: - Barcode Scanning RPCs

    func adminSubmitBarcode(upc: String) async throws -> MMSubmitBarcodeResponse {
        var request = MMSubmitBarcodeRequest()
        request.upc = upc
        return try await adminService.submitBarcode(request, metadata: authMetadata())
    }

    func adminListRecentScans() async throws -> MMRecentScansResponse {
        try await adminService.listRecentScans(MMEmpty(), metadata: authMetadata())
    }

    func adminMonitorScanProgress(
        onUpdate: @Sendable @escaping (MMScanProgressUpdate) async -> Void
    ) async throws {
        let metadata = authMetadata()
        try await adminService.monitorScanProgress(
            MMEmpty(),
            metadata: metadata
        ) { response in
            for try await message in response.messages {
                await onUpdate(message)
            }
        }
    }

    // MARK: - Scan Detail RPCs

    func adminGetScanDetail(scanId: Int64) async throws -> MMScanDetailResponse {
        var request = MMScanIdRequest()
        request.scanID = scanId
        return try await adminService.getScanDetail(request, metadata: authMetadata())
    }

    func adminAssignTmdb(titleId: Int64, tmdbId: Int32, mediaType: MMMediaType) async throws -> MMAssignTmdbResponse {
        var request = MMAssignTmdbRequest()
        request.titleID = titleId
        request.tmdbID = tmdbId
        request.mediaType = mediaType
        return try await adminService.assignTmdb(request, metadata: authMetadata())
    }

    func adminUpdatePurchaseInfo(scanId: Int64, place: String?, date: MMCalendarDate?, price: Double?) async throws {
        var request = MMUpdatePurchaseInfoRequest()
        request.scanID = scanId
        if let place { request.purchasePlace = place }
        if let date { request.purchaseDate = date }
        if let price { request.purchasePrice = price }
        _ = try await adminService.updatePurchaseInfo(request, metadata: authMetadata())
    }

    func adminUploadOwnershipPhoto(scanId: Int64, photoData: Data, contentType: String) async throws -> MMUploadOwnershipPhotoResponse {
        var request = MMUploadOwnershipPhotoRequest()
        request.scanID = scanId
        request.photoData = photoData
        request.contentType = contentType
        return try await adminService.uploadOwnershipPhoto(request, metadata: authMetadata())
    }

    func adminDeleteOwnershipPhoto(photoId: String) async throws {
        var request = MMDeleteOwnershipPhotoRequest()
        request.photoID = photoId
        _ = try await adminService.deleteOwnershipPhoto(request, metadata: authMetadata())
    }

    func adminAddTitle(tmdbId: Int32, mediaType: MMMediaType, mediaFormat: MMMediaFormat, seasons: String? = nil) async throws -> MMAddTitleResponse {
        var request = MMAddTitleRequest()
        request.tmdbID = tmdbId
        request.mediaType = mediaType
        request.mediaFormat = mediaFormat
        if let s = seasons { request.seasons = s }
        return try await adminService.addTitle(request, metadata: authMetadata())
    }

    // MARK: - Camera Admin RPCs

    func adminListCameras() async throws -> MMAdminCameraListResponse {
        try await adminService.listAdminCameras(MMEmpty(), metadata: authMetadata())
    }

    func adminCreateCamera(name: String, rtspUrl: String, snapshotUrl: String, streamName: String?, enabled: Bool) async throws -> MMAdminCamera {
        var request = MMCreateCameraRequest()
        request.name = name
        request.rtspURL = rtspUrl
        request.snapshotURL = snapshotUrl
        if let streamName { request.streamName = streamName }
        request.enabled = enabled
        return try await adminService.createCamera(request, metadata: authMetadata())
    }

    func adminUpdateCamera(id: Int64, name: String, rtspUrl: String, snapshotUrl: String, streamName: String, enabled: Bool) async throws -> MMAdminCamera {
        var request = MMUpdateCameraRequest()
        request.cameraID = id
        request.name = name
        request.rtspURL = rtspUrl
        request.snapshotURL = snapshotUrl
        request.streamName = streamName
        request.enabled = enabled
        return try await adminService.updateCamera(request, metadata: authMetadata())
    }

    func adminDeleteCamera(id: Int64) async throws {
        var request = MMCameraIdRequest()
        request.cameraID = id
        _ = try await adminService.deleteCamera(request, metadata: authMetadata())
    }

    func adminReorderCameras(ids: [Int64]) async throws {
        var request = MMReorderCamerasRequest()
        request.cameraIds = ids
        _ = try await adminService.reorderCameras(request, metadata: authMetadata())
    }

    func adminScanNas() async throws {
        _ = try await adminService.scanNas(MMEmpty(), metadata: authMetadata())
    }

    func adminClearFailures() async throws {
        _ = try await adminService.clearFailures(MMEmpty(), metadata: authMetadata())
    }

    func adminGetSettings() async throws -> MMSettingsResponse {
        try await adminService.getSettings(MMEmpty(), metadata: authMetadata())
    }

    func adminUpdateSetting(key: String, value: String) async throws {
        var request = MMUpdateSettingRequest()
        // Map string config key to proto SettingKey enum
        request.key = MMSettingKey.fromConfigKey(key)
        request.value = value
        _ = try await adminService.updateSetting(request, metadata: authMetadata())
    }

    func adminListLinkedTranscodes(page: Int32) async throws -> MMLinkedTranscodeResponse {
        var request = MMPaginationRequest()
        request.page = page
        request.limit = 50
        return try await adminService.listLinkedTranscodes(request, metadata: authMetadata())
    }

    func adminUnlinkTranscode(id: Int64) async throws {
        var request = MMTranscodeIdRequest()
        request.transcodeID = id
        _ = try await adminService.unlinkTranscode(request, metadata: authMetadata())
    }

    func adminListTags() async throws -> MMAdminTagListResponse {
        try await adminService.listTags(MMEmpty(), metadata: authMetadata())
    }

    func adminCreateTag(name: String, color: String) async throws {
        var request = MMCreateTagRequest()
        request.name = name
        request.color = MMColor.with { $0.hex = color }
        _ = try await adminService.createTag(request, metadata: authMetadata())
    }

    func adminUpdateTag(id: Int64, name: String, color: String) async throws {
        var request = MMUpdateTagRequest()
        request.tagID = id
        request.name = name
        request.color = MMColor.with { $0.hex = color }
        _ = try await adminService.updateTag(request, metadata: authMetadata())
    }

    func adminDeleteTag(id: Int64) async throws {
        var request = MMTagIdRequest()
        request.tagID = id
        _ = try await adminService.deleteTag(request, metadata: authMetadata())
    }

    func adminAddTagToTitle(tagId: Int64, titleId: Int64) async throws {
        var request = MMTagTitleRequest()
        request.tagID = tagId
        request.titleID = titleId
        _ = try await adminService.addTagToTitle(request, metadata: authMetadata())
    }

    func adminRemoveTagFromTitle(tagId: Int64, titleId: Int64) async throws {
        var request = MMTagTitleRequest()
        request.tagID = tagId
        request.titleID = titleId
        _ = try await adminService.removeTagFromTitle(request, metadata: authMetadata())
    }

    func adminListDataQuality(page: Int32) async throws -> MMDataQualityResponse {
        var request = MMDataQualityRequest()
        request.page = page
        request.limit = 50
        return try await adminService.listDataQuality(request, metadata: authMetadata())
    }

    func adminReEnrich(titleId: Int64) async throws {
        var request = MMTitleIdRequest()
        request.titleID = titleId
        _ = try await adminService.reEnrich(request, metadata: authMetadata())
    }

    func adminDeleteTitle(id: Int64) async throws {
        var request = MMTitleIdRequest()
        request.titleID = id
        _ = try await adminService.deleteTitle(request, metadata: authMetadata())
    }

    func adminListPurchaseWishes() async throws -> MMPurchaseWishListResponse {
        try await adminService.listPurchaseWishes(MMEmpty(), metadata: authMetadata())
    }

    func adminUpdatePurchaseWishStatus(tmdbId: Int32, mediaType: MediaType, seasonNumber: Int32?, status: AcquisitionStatus) async throws {
        var request = MMUpdatePurchaseWishStatusRequest()
        request.tmdbID = tmdbId
        request.mediaType = switch mediaType {
        case .movie: .movie
        case .tv: .tv
        case .personal: .personal
        }
        if let seasonNumber { request.seasonNumber = seasonNumber }
        request.status = status.protoValue
        _ = try await adminService.updatePurchaseWishStatus(request, metadata: authMetadata())
    }

    func adminListUsers() async throws -> MMUserListResponse {
        try await adminService.listUsers(MMEmpty(), metadata: authMetadata())
    }

    func adminCreateUser(username: String, password: String, displayName: String?) async throws {
        var request = MMCreateUserRequest()
        request.username = username
        request.password = password
        if let dn = displayName { request.displayName = dn }
        request.forceChange = true
        _ = try await adminService.createUser(request, metadata: authMetadata())
    }

    func adminUpdateUserRole(id: Int64, accessLevel: Int32) async throws {
        var request = MMUpdateUserRoleRequest()
        request.userID = id
        request.accessLevel = accessLevel == 2 ? .admin : .viewer
        _ = try await adminService.updateUserRole(request, metadata: authMetadata())
    }

    func adminUpdateUserRatingCeiling(id: Int64, ceiling: Int32?) async throws {
        var request = MMUpdateUserRatingCeilingRequest()
        request.userID = id
        if let c = ceiling {
            request.ceiling = MMRatingLevel(rawValue: Int(c)) ?? .unknown
        }
        _ = try await adminService.updateUserRatingCeiling(request, metadata: authMetadata())
    }

    func adminUnlockUser(id: Int64) async throws {
        var request = MMUserIdRequest()
        request.userID = id
        _ = try await adminService.unlockUser(request, metadata: authMetadata())
    }

    func adminForcePasswordChange(id: Int64) async throws {
        var request = MMUserIdRequest()
        request.userID = id
        _ = try await adminService.forcePasswordChange(request, metadata: authMetadata())
    }

    func adminResetPassword(id: Int64, newPassword: String) async throws {
        var request = MMResetPasswordRequest()
        request.userID = id
        request.newPassword = newPassword
        request.forceChange = true
        _ = try await adminService.resetPassword(request, metadata: authMetadata())
    }

    func adminDeleteUser(id: Int64) async throws {
        var request = MMUserIdRequest()
        request.userID = id
        _ = try await adminService.deleteUser(request, metadata: authMetadata())
    }

    func adminListUnmatchedFiles() async throws -> MMUnmatchedResponse {
        try await adminService.listUnmatchedFiles(MMEmpty(), metadata: authMetadata())
    }

    func adminAcceptUnmatched(id: Int64) async throws {
        var request = MMUnmatchedIdRequest()
        request.unmatchedID = id
        _ = try await adminService.acceptUnmatched(request, metadata: authMetadata())
    }

    func adminIgnoreUnmatched(id: Int64) async throws {
        var request = MMUnmatchedIdRequest()
        request.unmatchedID = id
        _ = try await adminService.ignoreUnmatched(request, metadata: authMetadata())
    }

    func adminLinkUnmatched(id: Int64, titleId: Int64) async throws {
        var request = MMLinkUnmatchedRequest()
        request.unmatchedID = id
        request.titleID = titleId
        _ = try await adminService.linkUnmatched(request, metadata: authMetadata())
    }

    // MARK: - Amazon Import RPCs

    func adminImportAmazonOrders(csvData: Data, filename: String) async throws -> MMImportAmazonOrdersResponse {
        var request = MMImportAmazonOrdersRequest()
        request.csvData = csvData
        request.filename = filename
        return try await adminService.importAmazonOrders(request, metadata: authMetadata())
    }

    func adminSearchAmazonOrders(query: String = "", mediaOnly: Bool = true, unlinkedOnly: Bool = true, hideCancelled: Bool = true, limit: Int32 = 200) async throws -> MMSearchAmazonOrdersResponse {
        var request = MMSearchAmazonOrdersRequest()
        request.query = query
        request.mediaOnly = mediaOnly
        request.unlinkedOnly = unlinkedOnly
        request.hideCancelled = hideCancelled
        request.limit = limit
        return try await adminService.searchAmazonOrders(request, metadata: authMetadata())
    }

    func adminLinkAmazonOrder(amazonOrderId: Int64, mediaItemId: Int64) async throws {
        var request = MMLinkAmazonOrderRequest()
        request.amazonOrderID = amazonOrderId
        request.mediaItemID = mediaItemId
        _ = try await adminService.linkAmazonOrder(request, metadata: authMetadata())
    }

    func adminUnlinkAmazonOrder(amazonOrderId: Int64) async throws {
        var request = MMAmazonOrderIdRequest()
        request.amazonOrderID = amazonOrderId
        _ = try await adminService.unlinkAmazonOrder(request, metadata: authMetadata())
    }

    func adminGetAmazonOrderSummary() async throws -> MMAmazonOrderSummaryResponse {
        try await adminService.getAmazonOrderSummary(MMEmpty(), metadata: authMetadata())
    }

    // MARK: - Expand Multi-Pack RPCs

    func adminListPendingExpansions() async throws -> MMPendingExpansionsResponse {
        try await adminService.listPendingExpansions(MMEmpty(), metadata: authMetadata())
    }

    func adminGetExpansionDetail(mediaItemId: Int64) async throws -> MMExpansionDetailResponse {
        var request = MMMediaItemIdRequest()
        request.mediaItemID = mediaItemId
        return try await adminService.getExpansionDetail(request, metadata: authMetadata())
    }

    func adminAddTitleToExpansion(mediaItemId: Int64, tmdbId: Int32, mediaType: MMMediaType) async throws -> MMAddTitleToExpansionResponse {
        var request = MMAddTitleToExpansionRequest()
        request.mediaItemID = mediaItemId
        request.tmdbID = tmdbId
        request.mediaType = mediaType
        return try await adminService.addTitleToExpansion(request, metadata: authMetadata())
    }

    func adminRemoveTitleFromExpansion(mediaItemId: Int64, titleId: Int64) async throws {
        var request = MMRemoveTitleFromExpansionRequest()
        request.mediaItemID = mediaItemId
        request.titleID = titleId
        _ = try await adminService.removeTitleFromExpansion(request, metadata: authMetadata())
    }

    func adminMarkExpanded(mediaItemId: Int64) async throws {
        var request = MMMediaItemIdRequest()
        request.mediaItemID = mediaItemId
        _ = try await adminService.markExpanded(request, metadata: authMetadata())
    }

    func adminMarkNotMultiPack(mediaItemId: Int64) async throws {
        var request = MMMediaItemIdRequest()
        request.mediaItemID = mediaItemId
        _ = try await adminService.markNotMultiPack(request, metadata: authMetadata())
    }

    // MARK: - Valuation RPCs

    func adminListValuations(page: Int32 = 0, pageSize: Int32 = 50, query: String = "", unpricedOnly: Bool = false) async throws -> MMValuationResponse {
        var request = MMValuationRequest()
        request.page = page
        request.pageSize = pageSize
        if !query.isEmpty { request.query = query }
        request.unpricedOnly = unpricedOnly
        return try await adminService.listValuations(request, metadata: authMetadata())
    }

    func adminUpdateMediaItem(id: Int64, place: String?, date: String?, price: Double?, replacementValue: Double?, asin: String?) async throws {
        var request = MMUpdateMediaItemRequest()
        request.mediaItemID = id
        if let p = place { request.purchasePlace = p }
        if let d = date { request.purchaseDate = d }
        if let p = price { request.purchasePrice = p }
        if let r = replacementValue { request.replacementValue = r }
        if let a = asin { request.overrideAsin = a }
        _ = try await adminService.updateMediaItem(request, metadata: authMetadata())
    }

    func adminListUndocumentedItems(page: Int32 = 1, limit: Int32 = 50) async throws -> MMUndocumentedItemsResponse {
        var request = MMPaginationRequest()
        request.page = page
        request.limit = limit
        return try await adminService.listUndocumentedItems(request, metadata: authMetadata())
    }

    // MARK: - Family Member RPCs

    func adminListFamilyMembers() async throws -> MMFamilyMemberListResponse {
        try await adminService.listFamilyMembers(MMEmpty(), metadata: authMetadata())
    }

    func adminCreateFamilyMember(name: String, birthDate: String?, notes: String?) async throws -> MMFamilyMemberResponse {
        var request = MMCreateFamilyMemberRequest()
        request.name = name
        if let b = birthDate { request.birthDate = b }
        if let n = notes { request.notes = n }
        return try await adminService.createFamilyMember(request, metadata: authMetadata())
    }

    func adminUpdateFamilyMember(id: Int64, name: String, birthDate: String?, notes: String?) async throws {
        var request = MMUpdateFamilyMemberRequest()
        request.familyMemberID = id
        request.name = name
        if let b = birthDate { request.birthDate = b }
        if let n = notes { request.notes = n }
        _ = try await adminService.updateFamilyMember(request, metadata: authMetadata())
    }

    func adminDeleteFamilyMember(id: Int64) async throws {
        var request = MMFamilyMemberIdRequest()
        request.familyMemberID = id
        _ = try await adminService.deleteFamilyMember(request, metadata: authMetadata())
    }

    // MARK: - Live TV Settings RPCs

    func adminGetLiveTvSettings() async throws -> MMLiveTvSettingsResponse {
        try await adminService.getLiveTvSettings(MMEmpty(), metadata: authMetadata())
    }

    func adminUpdateLiveTvSettings(minRating: String?, maxStreams: Int32, idleTimeout: Int32) async throws {
        var request = MMUpdateLiveTvSettingsRequest()
        if let r = minRating { request.minContentRating = r }
        request.maxStreams = maxStreams
        request.idleTimeoutSeconds = idleTimeout
        _ = try await adminService.updateLiveTvSettings(request, metadata: authMetadata())
    }

    func adminListTuners() async throws -> MMTunerListResponse {
        try await adminService.listTuners(MMEmpty(), metadata: authMetadata())
    }

    func adminAddTuner(ipAddress: String, name: String?) async throws -> MMTunerResponse {
        var request = MMAddTunerRequest()
        request.ipAddress = ipAddress
        if let n = name { request.name = n }
        return try await adminService.addTuner(request, metadata: authMetadata())
    }

    func adminUpdateTuner(id: Int64, name: String, enabled: Bool) async throws {
        var request = MMUpdateTunerRequest()
        request.tunerID = id
        request.name = name
        request.enabled = enabled
        _ = try await adminService.updateTuner(request, metadata: authMetadata())
    }

    func adminDeleteTuner(id: Int64) async throws {
        var request = MMTunerIdRequest()
        request.tunerID = id
        _ = try await adminService.deleteTuner(request, metadata: authMetadata())
    }

    func adminRefreshTunerChannels(tunerId: Int64) async throws -> MMRefreshChannelsResponse {
        var request = MMTunerIdRequest()
        request.tunerID = tunerId
        return try await adminService.refreshTunerChannels(request, metadata: authMetadata())
    }

    func adminListAdminChannels(tunerId: Int64) async throws -> MMAdminChannelListResponse {
        var request = MMTunerIdRequest()
        request.tunerID = tunerId
        return try await adminService.listAdminChannels(request, metadata: authMetadata())
    }

    func adminUpdateChannel(id: Int64, networkAffiliation: String?, quality: Int32, enabled: Bool) async throws {
        var request = MMUpdateChannelRequest()
        request.channelID = id
        if let n = networkAffiliation { request.networkAffiliation = n }
        request.receptionQuality = quality
        request.enabled = enabled
        _ = try await adminService.updateChannel(request, metadata: authMetadata())
    }

    // MARK: - Inventory Report RPCs

    func adminGenerateInventoryReport(includePhotos: Bool = false) async throws -> MMInventoryReportResponse {
        var request = MMInventoryReportRequest()
        request.includePhotos = includePhotos
        return try await adminService.generateInventoryReport(request, metadata: authMetadata())
    }
}

// MARK: - Error type

enum GrpcClientError: LocalizedError {
    case notConfigured
    case serverError(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: "gRPC client not configured — no server connection"
        case .serverError(let msg): msg
        }
    }
}
