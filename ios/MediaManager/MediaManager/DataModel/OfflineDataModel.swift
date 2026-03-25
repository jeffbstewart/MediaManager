import Foundation
import AVFoundation
import Observation

/// Stub data model for offline mode. Serves downloaded/cached content only.
/// All online-only operations throw DataModelError.offline.
@Observable
@MainActor
final class OfflineDataModel: DataModel {
    let downloads: DownloadManager
    private let onlineModel: OnlineDataModel

    var isOnline: Bool { false }

    var capabilities: [String] {
        // Expose cached capabilities so UI can still show Downloads tab
        onlineModel.capabilities
    }

    var userInfo: ServerUserInfo? {
        onlineModel.userInfo
    }

    /// Expose apiClient for image loading in cached views (poster cards, etc.)
    /// When offline, these will fail gracefully — AuthenticatedImage shows placeholders.
    var apiClient: APIClient { onlineModel.apiClient }

    init(onlineModel: OnlineDataModel) {
        self.onlineModel = onlineModel
        self.downloads = onlineModel.downloads
    }

    // MARK: - Image Data (offline: serve from cache or fail silently)

    func imageData(path: String) async -> Data? {
        nil
    }

    // MARK: - CatalogDataModel (limited offline support)

    func homeFeed() async throws -> ApiHomeFeed {
        throw DataModelError.offline
    }

    func titles(type: MediaType, page: Int, sort: String?) async throws -> ApiTitlePage {
        throw DataModelError.offline
    }

    // TODO: Offline title detail from cached protobuf — needs separate implementation
    func titleDetail(id: TitleID) async throws -> ApiTitleDetail {
        throw DataModelError.offline
    }

    func seasons(titleId: TitleID) async throws -> [ApiSeason] {
        throw DataModelError.offline
    }

    func episodes(titleId: TitleID, season: Int) async throws -> [ApiEpisode] {
        throw DataModelError.offline
    }

    func search(query: String) async throws -> ApiSearchResponse {
        throw DataModelError.offline
    }

    func actorDetail(id: TmdbPersonID) async throws -> ApiActorDetail {
        throw DataModelError.offline
    }

    func collections() async throws -> ApiCollectionListResponse {
        throw DataModelError.offline
    }

    func collectionDetail(id: TmdbCollectionID) async throws -> ApiCollectionDetail {
        throw DataModelError.offline
    }

    func tags() async throws -> ApiTagListResponse {
        throw DataModelError.offline
    }

    func tagDetail(id: TagID) async throws -> ApiTagDetail {
        throw DataModelError.offline
    }

    func genreDetail(id: GenreID) async throws -> ApiGenreDetail {
        throw DataModelError.offline
    }

    func setFavorite(titleId: TitleID, favorite: Bool) async throws {
        throw DataModelError.offline
    }

    func setHidden(titleId: TitleID, hidden: Bool) async throws {
        throw DataModelError.offline
    }

    func requestRetranscode(titleId: TitleID) async throws {
        throw DataModelError.offline
    }

    func requestMobileTranscode(titleId: TitleID) async throws {
        throw DataModelError.offline
    }

    func dismissContinueWatching(titleId: TitleID) async throws {
        throw DataModelError.offline
    }

    func dismissMissingSeason(titleId: TitleID, tmdbId: TmdbID, mediaType: MediaType, seasonNumber: Int) async throws {
        throw DataModelError.offline
    }

    // MARK: - PlaybackDataModel (offline: local files + queued progress)

    func streamAsset(transcodeId: TranscodeID) async -> AVURLAsset? {
        guard let localURL = downloads.localVideoURL(for: transcodeId.protoValue) else { return nil }
        return AVURLAsset(url: localURL)
    }

    func playbackProgress(transcodeId: TranscodeID) async -> ApiPlaybackProgress? {
        // No server to ask — return nil, playback starts from beginning
        nil
    }

    func reportProgress(transcodeId: TranscodeID, position: Double, duration: Double?) async {
        // Queue for later sync
        downloads.queueProgressUpdate(transcodeId: transcodeId.protoValue, position: position, duration: duration)
    }

    // MARK: - WishListDataModel

    func wishList() async throws -> ApiWishListResponse { throw DataModelError.offline }
    func transcodeWishList() async throws -> ApiTranscodeWishListResponse { throw DataModelError.offline }
    func addWish(tmdbId: TmdbID, mediaType: MediaType, title: String, year: Int?,
                 posterPath: String?, seasonNumber: Int?) async throws { throw DataModelError.offline }
    func deleteWish(id: WishID) async throws { throw DataModelError.offline }
    func voteOnWish(id: WishID, vote: Bool) async throws { throw DataModelError.offline }
    func dismissWish(id: WishID) async throws { throw DataModelError.offline }
    func deleteTranscodeWish(titleId: TitleID) async throws { throw DataModelError.offline }
    func searchTmdb(query: String) async throws -> TmdbSearchResponse { throw DataModelError.offline }
    func searchTmdb(query: String, type: MMMediaType) async throws -> MMTmdbSearchResponse { throw DataModelError.offline }

    // MARK: - ProfileDataModel

    func profile() async throws -> ProfileResponse { throw DataModelError.offline }
    func updateTvQuality(_ quality: Int) async throws { throw DataModelError.offline }
    func changePassword(current: String, new: String) async throws { throw DataModelError.offline }
    func sessions() async throws -> ApiSessionListResponse { throw DataModelError.offline }
    func deleteSession(id: SessionID, type: SessionType) async throws { throw DataModelError.offline }
    func deleteOtherSessions() async throws { throw DataModelError.offline }

    // MARK: - LiveDataModel

    func cameras() async throws -> ApiCameraListResponse { throw DataModelError.offline }
    func tvChannels() async throws -> ApiTvChannelListResponse { throw DataModelError.offline }
    func warmUpStream(path: String) async throws { throw DataModelError.offline }

    // MARK: - AdminDataModel

    func transcodeStatus() async throws -> TranscodeStatusResponse { throw DataModelError.offline }
    func buddyStatus() async throws -> BuddyStatusResponse { throw DataModelError.offline }
    func monitorTranscodeStatus(onUpdate: @Sendable @escaping (MMTranscodeStatusUpdate) async -> Void) async throws { throw DataModelError.offline }
    func submitBarcode(upc: String) async throws -> MMSubmitBarcodeResponse { throw DataModelError.offline }
    func monitorScanProgress(onUpdate: @Sendable @escaping (MMScanProgressUpdate) async -> Void) async throws { throw DataModelError.offline }
    func getScanDetail(scanId: Int64) async throws -> MMScanDetailResponse { throw DataModelError.offline }
    func assignTmdb(titleId: Int64, tmdbId: Int32, mediaType: MMMediaType) async throws -> MMAssignTmdbResponse { throw DataModelError.offline }
    func updatePurchaseInfo(scanId: Int64, place: String?, date: MMCalendarDate?, price: Double?) async throws { throw DataModelError.offline }
    func uploadOwnershipPhoto(scanId: Int64, photoData: Data, contentType: String) async throws -> MMUploadOwnershipPhotoResponse { throw DataModelError.offline }
    func deleteOwnershipPhoto(photoId: String) async throws { throw DataModelError.offline }
    func adminListCameras() async throws -> MMAdminCameraListResponse { throw DataModelError.offline }
    func adminCreateCamera(name: String, rtspUrl: String, snapshotUrl: String, streamName: String?, enabled: Bool) async throws -> MMAdminCamera { throw DataModelError.offline }
    func adminUpdateCamera(id: Int64, name: String, rtspUrl: String, snapshotUrl: String, streamName: String, enabled: Bool) async throws -> MMAdminCamera { throw DataModelError.offline }
    func adminDeleteCamera(id: Int64) async throws { throw DataModelError.offline }
    func adminReorderCameras(ids: [Int64]) async throws { throw DataModelError.offline }
    func scanNas() async throws { throw DataModelError.offline }
    func clearFailures() async throws { throw DataModelError.offline }
    func adminSettings() async throws -> AdminSettingsResponse { throw DataModelError.offline }
    func updateSetting(key: String, value: String?) async throws { throw DataModelError.offline }
    func linkedTranscodes(page: Int) async throws -> AdminLinkedTranscodeResponse { throw DataModelError.offline }
    func unlinkTranscode(id: TranscodeID) async throws { throw DataModelError.offline }
    func adminTags() async throws -> ApiTagListResponse { throw DataModelError.offline }
    func createTag(name: String, color: String) async throws { throw DataModelError.offline }
    func updateTag(id: TagID, name: String, color: String) async throws { throw DataModelError.offline }
    func deleteTag(id: TagID) async throws { throw DataModelError.offline }
    func dataQuality(page: Int) async throws -> AdminDataQualityResponse { throw DataModelError.offline }
    func reEnrich(titleId: TitleID) async throws { throw DataModelError.offline }
    func deleteTitle(id: TitleID) async throws { throw DataModelError.offline }
    func purchaseWishes() async throws -> AdminPurchaseWishListResponse { throw DataModelError.offline }
    func updatePurchaseWishStatus(tmdbId: TmdbID, mediaType: MediaType, seasonNumber: Int?, status: AcquisitionStatus) async throws { throw DataModelError.offline }
    func adminUsers() async throws -> AdminUserListResponse { throw DataModelError.offline }
    func createUser(username: String, password: String, displayName: String?, accessLevel: Int) async throws { throw DataModelError.offline }
    func updateUserRole(id: UserID, accessLevel: Int) async throws { throw DataModelError.offline }
    func updateUserRatingCeiling(id: UserID, ceiling: Int?) async throws { throw DataModelError.offline }
    func unlockUser(id: UserID) async throws { throw DataModelError.offline }
    func forcePasswordChange(id: UserID) async throws { throw DataModelError.offline }
    func resetPassword(id: UserID, newPassword: String) async throws { throw DataModelError.offline }
    func deleteUser(id: UserID) async throws { throw DataModelError.offline }
    func unmatchedFiles() async throws -> AdminUnmatchedResponse { throw DataModelError.offline }
    func acceptUnmatched(id: UnmatchedFileID) async throws { throw DataModelError.offline }
    func ignoreUnmatched(id: UnmatchedFileID) async throws { throw DataModelError.offline }
    func linkUnmatched(id: UnmatchedFileID, titleId: TitleID) async throws { throw DataModelError.offline }
}
