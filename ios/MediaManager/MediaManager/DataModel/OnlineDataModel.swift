import Foundation
import AVFoundation
import Observation

@Observable
@MainActor
final class OnlineDataModel: DataModel {
    let apiClient: APIClient
    private let authManager: AuthManager
    let downloads: DownloadManager
    var grpcClient: GrpcClient { authManager.grpcClient }
    private var _offlineDelegate: OfflineDataModel?
    private var offlineDelegate: OfflineDataModel {
        if let d = _offlineDelegate { return d }
        let d = OfflineDataModel(onlineModel: self)
        _offlineDelegate = d
        return d
    }

    var isOnline: Bool { !downloads.isEffectivelyOffline }

    var capabilities: [String] {
        authManager.serverInfo?.capabilities ?? authManager.cachedCapabilities
    }

    var userInfo: ServerUserInfo? {
        authManager.serverInfo?.user
    }

    init(authManager: AuthManager, downloadManager: DownloadManager) {
        self.authManager = authManager
        self.apiClient = authManager.apiClient
        self.downloads = downloadManager
    }

    // MARK: - Image Data (stays HTTP)

    func imageData(path: String) async -> Data? {
        try? await apiClient.getRaw(path)
    }

    // MARK: - CatalogDataModel

    func homeFeed() async throws -> ApiHomeFeed {
        if !isOnline { return try await offlineDelegate.homeFeed() }
        let response = try await grpcClient.homeFeed()
        return ApiHomeFeed(proto: response)
    }

    func titles(type: MediaType, page: Int, sort: String?) async throws -> ApiTitlePage {
        if !isOnline { return try await offlineDelegate.titles(type: type, page: page, sort: sort) }
        let protoType: MMMediaType = switch type {
        case .movie: .movie
        case .tv: .tv
        case .personal: .personal
        }
        let response = try await grpcClient.listTitles(
            type: protoType, page: Int32(page), limit: 25, sort: sort, query: nil, playableOnly: false)
        return ApiTitlePage(proto: response)
    }

    func titleDetail(id: TitleID) async throws -> ApiTitleDetail {
        if !isOnline { return try await offlineDelegate.titleDetail(id: id) }
        let response = try await grpcClient.getTitleDetail(id: id.protoValue)
        return ApiTitleDetail(proto: response)
    }

    func seasons(titleId: TitleID) async throws -> [ApiSeason] {
        if !isOnline { return try await offlineDelegate.seasons(titleId: titleId) }
        let response = try await grpcClient.listSeasons(titleId: titleId.protoValue)
        return response.seasons.map { ApiSeason(proto: $0) }
    }

    func episodes(titleId: TitleID, season: Int) async throws -> [ApiEpisode] {
        if !isOnline { return try await offlineDelegate.episodes(titleId: titleId, season: season) }
        let response = try await grpcClient.listEpisodes(titleId: titleId.protoValue, season: Int32(season))
        return response.episodes.map { ApiEpisode(proto: $0) }
    }

    func search(query: String) async throws -> ApiSearchResponse {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.search(query: query)
        return ApiSearchResponse(proto: response)
    }

    func actorDetail(id: TmdbPersonID) async throws -> ApiActorDetail {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.getActorDetail(personId: id.protoValue)
        return ApiActorDetail(proto: response)
    }

    func collections() async throws -> ApiCollectionListResponse {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.listCollections()
        return ApiCollectionListResponse(proto: response)
    }

    func collectionDetail(id: TmdbCollectionID) async throws -> ApiCollectionDetail {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.getCollectionDetail(id: id.protoValue)
        return ApiCollectionDetail(proto: response)
    }

    func tags() async throws -> ApiTagListResponse {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.listTags()
        return ApiTagListResponse(proto: response)
    }

    func tagDetail(id: TagID) async throws -> ApiTagDetail {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.getTagDetail(id: id.protoValue)
        return ApiTagDetail(proto: response)
    }

    func genreDetail(id: GenreID) async throws -> ApiGenreDetail {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.getGenreDetail(id: id.protoValue)
        return ApiGenreDetail(proto: response)
    }

    func setFavorite(titleId: TitleID, favorite: Bool) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.setFavorite(titleId: titleId.protoValue, value: favorite)
    }

    func setHidden(titleId: TitleID, hidden: Bool) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.setHidden(titleId: titleId.protoValue, value: hidden)
    }

    func requestRetranscode(titleId: TitleID) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.requestRetranscode(titleId: titleId.protoValue)
    }

    func requestMobileTranscode(titleId: TitleID) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.requestLowStorageTranscode(titleId: titleId.protoValue)
    }

    func dismissContinueWatching(titleId: TitleID) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.dismissContinueWatching(titleId: titleId.protoValue)
    }

    func dismissMissingSeason(titleId: TitleID, tmdbId: TmdbID, mediaType: MediaType, seasonNumber: Int) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.dismissMissingSeason(titleId: titleId.protoValue, seasonNumber: Int32(seasonNumber))
    }

    // MARK: - PlaybackDataModel

    func streamAsset(transcodeId: TranscodeID) async -> AVURLAsset? {
        // Prefer local file for offline or downloaded content
        if let localURL = downloads.localFileURL(for: transcodeId) {
            return AVURLAsset(url: localURL)
        }
        if !isOnline { return nil }
        // Video streaming stays HTTP (Range requests, HLS)
        guard let (url, headers) = await apiClient.streamURL(for: transcodeId.rawValue) else { return nil }
        return AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
    }

    func playbackProgress(transcodeId: TranscodeID) async -> ApiPlaybackProgress? {
        if !isOnline { return nil }
        guard let response = try? await grpcClient.getProgress(transcodeId: transcodeId.protoValue) else {
            return nil
        }
        return ApiPlaybackProgress(proto: response)
    }

    func reportProgress(transcodeId: TranscodeID, position: Double, duration: Double?) async {
        if !isOnline {
            downloads.queueProgressUpdate(transcodeId: transcodeId, position: position, duration: duration)
            return
        }
        try? await grpcClient.reportProgress(transcodeId: transcodeId.protoValue, position: position, duration: duration)
    }

    // MARK: - WishListDataModel

    func wishList() async throws -> ApiWishListResponse {
        let response = try await grpcClient.wishList()
        return ApiWishListResponse(proto: response)
    }

    func transcodeWishList() async throws -> ApiTranscodeWishListResponse {
        let response = try await grpcClient.transcodeWishList()
        return ApiTranscodeWishListResponse(proto: response)
    }

    func addWish(tmdbId: TmdbID, mediaType: MediaType, title: String, year: Int?,
                 posterPath: String?, seasonNumber: Int?) async throws {
        let protoType: MMMediaType = switch mediaType {
        case .movie: .movie
        case .tv: .tv
        case .personal: .personal
        }
        _ = try await grpcClient.addWish(
            tmdbId: tmdbId.protoValue,
            mediaType: protoType,
            title: title,
            posterPath: posterPath,
            releaseYear: year.map { Int32($0) },
            seasonNumber: seasonNumber.map { Int32($0) })
    }

    func deleteWish(id: WishID) async throws {
        try await grpcClient.deleteWish(id: id.protoValue)
    }

    func voteOnWish(id: WishID, vote: Bool) async throws {
        try await grpcClient.voteOnWish(id: id.protoValue, vote: vote)
    }

    func dismissWish(id: WishID) async throws {
        try await grpcClient.dismissWish(id: id.protoValue)
    }

    func deleteTranscodeWish(titleId: TitleID) async throws {
        try await grpcClient.deleteTranscodeWish(titleId: titleId.protoValue)
    }

    func searchTmdb(query: String) async throws -> TmdbSearchResponse {
        let response = try await grpcClient.searchTmdb(query: query, type: .unknown)
        return TmdbSearchResponse(proto: response)
    }

    func searchTmdb(query: String, type: MMMediaType) async throws -> MMTmdbSearchResponse {
        try await grpcClient.searchTmdb(query: query, type: type)
    }

    // MARK: - ProfileDataModel

    func profile() async throws -> ProfileResponse {
        let response = try await grpcClient.getProfile()
        return ProfileResponse(proto: response)
    }

    func updateTvQuality(_ quality: Int) async throws {
        let protoQuality: MMQuality = switch quality {
        case 1: .sd
        case 2: .fhd
        case 3: .uhd
        default: .unknown
        }
        try await grpcClient.updateTvQuality(protoQuality)
    }

    func changePassword(current: String, new: String) async throws {
        _ = try await grpcClient.changePassword(current: current, new: new)
    }

    func sessions() async throws -> ApiSessionListResponse {
        let response = try await grpcClient.listSessions()
        return ApiSessionListResponse(proto: response)
    }

    func deleteSession(id: SessionID, type: SessionType) async throws {
        let protoType: MMSessionType = switch type {
        case .access: .browser
        case .refresh: .app
        }
        try await grpcClient.deleteSession(id: id.protoValue, type: protoType)
    }

    func deleteOtherSessions() async throws {
        try await grpcClient.deleteOtherSessions()
    }

    // MARK: - LiveDataModel

    func cameras() async throws -> ApiCameraListResponse {
        let response = try await grpcClient.listCameras()
        return ApiCameraListResponse(proto: response)
    }

    func tvChannels() async throws -> ApiTvChannelListResponse {
        let response = try await grpcClient.listTvChannels()
        return ApiTvChannelListResponse(proto: response)
    }

    func warmUpStream(path: String) async throws {
        // Camera and live TV warmup hit HTTP HLS endpoints that block until
        // segments are ready. Use HTTP for these since gRPC WarmUpStream is
        // a metadata trigger, not an HLS blocking call.
        try await apiClient.warmUpStream(path)
    }

    // MARK: - AdminDataModel

    func transcodeStatus() async throws -> TranscodeStatusResponse {
        let response = try await grpcClient.adminTranscodeStatus()
        return TranscodeStatusResponse(proto: response)
    }

    func monitorTranscodeStatus(onUpdate: @Sendable @escaping (MMTranscodeStatusUpdate) async -> Void) async throws {
        try await grpcClient.adminMonitorTranscodeStatus(onUpdate: onUpdate)
    }

    func buddyStatus() async throws -> BuddyStatusResponse {
        let response = try await grpcClient.adminBuddyStatus()
        return BuddyStatusResponse(proto: response)
    }

    func submitBarcode(upc: String) async throws -> MMSubmitBarcodeResponse {
        try await grpcClient.adminSubmitBarcode(upc: upc)
    }

    func monitorScanProgress(onUpdate: @Sendable @escaping (MMScanProgressUpdate) async -> Void) async throws {
        try await grpcClient.adminMonitorScanProgress(onUpdate: onUpdate)
    }

    func getScanDetail(scanId: Int64) async throws -> MMScanDetailResponse {
        try await grpcClient.adminGetScanDetail(scanId: scanId)
    }

    func assignTmdb(titleId: Int64, tmdbId: Int32, mediaType: MMMediaType) async throws -> MMAssignTmdbResponse {
        try await grpcClient.adminAssignTmdb(titleId: titleId, tmdbId: tmdbId, mediaType: mediaType)
    }

    func updatePurchaseInfo(scanId: Int64, place: String?, date: MMCalendarDate?, price: Double?) async throws {
        try await grpcClient.adminUpdatePurchaseInfo(scanId: scanId, place: place, date: date, price: price)
    }

    func uploadOwnershipPhoto(scanId: Int64, photoData: Data, contentType: String) async throws -> MMUploadOwnershipPhotoResponse {
        try await grpcClient.adminUploadOwnershipPhoto(scanId: scanId, photoData: photoData, contentType: contentType)
    }

    func deleteOwnershipPhoto(photoId: String) async throws {
        try await grpcClient.adminDeleteOwnershipPhoto(photoId: photoId)
    }

    func scanNas() async throws {
        try await grpcClient.adminScanNas()
    }

    func clearFailures() async throws {
        try await grpcClient.adminClearFailures()
    }

    func adminSettings() async throws -> AdminSettingsResponse {
        let response = try await grpcClient.adminGetSettings()
        return AdminSettingsResponse(proto: response)
    }

    func updateSetting(key: String, value: String?) async throws {
        try await grpcClient.adminUpdateSetting(key: key, value: value ?? "")
    }

    func linkedTranscodes(page: Int) async throws -> AdminLinkedTranscodeResponse {
        let response = try await grpcClient.adminListLinkedTranscodes(page: Int32(page))
        return AdminLinkedTranscodeResponse(proto: response)
    }

    func unlinkTranscode(id: TranscodeID) async throws {
        try await grpcClient.adminUnlinkTranscode(id: id.protoValue)
    }

    func adminTags() async throws -> ApiTagListResponse {
        let response = try await grpcClient.adminListTags()
        // Admin tag list uses MMAdminTagListResponse; convert to ApiTagListResponse
        let items = response.tags.map { tag in
            ApiTagListItem(adminProto: tag)
        }
        return ApiTagListResponse(tags: items)
    }

    func createTag(name: String, color: String) async throws {
        try await grpcClient.adminCreateTag(name: name, color: color)
    }

    func updateTag(id: TagID, name: String, color: String) async throws {
        try await grpcClient.adminUpdateTag(id: id.protoValue, name: name, color: color)
    }

    func deleteTag(id: TagID) async throws {
        try await grpcClient.adminDeleteTag(id: id.protoValue)
    }

    func dataQuality(page: Int) async throws -> AdminDataQualityResponse {
        let response = try await grpcClient.adminListDataQuality(page: Int32(page))
        return AdminDataQualityResponse(proto: response)
    }

    func reEnrich(titleId: TitleID) async throws {
        try await grpcClient.adminReEnrich(titleId: titleId.protoValue)
    }

    func deleteTitle(id: TitleID) async throws {
        try await grpcClient.adminDeleteTitle(id: id.protoValue)
    }

    func purchaseWishes() async throws -> AdminPurchaseWishListResponse {
        let response = try await grpcClient.adminListPurchaseWishes()
        return AdminPurchaseWishListResponse(proto: response)
    }

    func updatePurchaseWishStatus(tmdbId: TmdbID, mediaType: MediaType, seasonNumber: Int?, status: AcquisitionStatus) async throws {
        try await grpcClient.adminUpdatePurchaseWishStatus(
            tmdbId: tmdbId.protoValue,
            mediaType: mediaType,
            seasonNumber: seasonNumber.map { Int32($0) },
            status: status
        )
    }

    func adminUsers() async throws -> AdminUserListResponse {
        let response = try await grpcClient.adminListUsers()
        return AdminUserListResponse(proto: response)
    }

    func createUser(username: String, password: String, displayName: String?, accessLevel: Int) async throws {
        try await grpcClient.adminCreateUser(username: username, password: password, displayName: displayName)
    }

    func updateUserRole(id: UserID, accessLevel: Int) async throws {
        try await grpcClient.adminUpdateUserRole(id: id.protoValue, accessLevel: Int32(accessLevel))
    }

    func updateUserRatingCeiling(id: UserID, ceiling: Int?) async throws {
        try await grpcClient.adminUpdateUserRatingCeiling(id: id.protoValue, ceiling: ceiling.map { Int32($0) })
    }

    func unlockUser(id: UserID) async throws {
        try await grpcClient.adminUnlockUser(id: id.protoValue)
    }

    func forcePasswordChange(id: UserID) async throws {
        try await grpcClient.adminForcePasswordChange(id: id.protoValue)
    }

    func resetPassword(id: UserID, newPassword: String) async throws {
        try await grpcClient.adminResetPassword(id: id.protoValue, newPassword: newPassword)
    }

    func deleteUser(id: UserID) async throws {
        try await grpcClient.adminDeleteUser(id: id.protoValue)
    }

    func unmatchedFiles() async throws -> AdminUnmatchedResponse {
        let response = try await grpcClient.adminListUnmatchedFiles()
        return AdminUnmatchedResponse(proto: response)
    }

    func acceptUnmatched(id: UnmatchedFileID) async throws {
        try await grpcClient.adminAcceptUnmatched(id: id.protoValue)
    }

    func ignoreUnmatched(id: UnmatchedFileID) async throws {
        try await grpcClient.adminIgnoreUnmatched(id: id.protoValue)
    }

    func linkUnmatched(id: UnmatchedFileID, titleId: TitleID) async throws {
        try await grpcClient.adminLinkUnmatched(id: id.protoValue, titleId: titleId.protoValue)
    }
}
