import Foundation
import AVFoundation
import Observation

@Observable
@MainActor
final class OnlineDataModel: DataModel {
    let apiClient: APIClient
    private let authManager: AuthManager
    let downloads: DownloadManager
    // Initialized after self is fully available (can't reference self in init)
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

    // MARK: - Image Data

    func imageData(path: String) async -> Data? {
        try? await apiClient.getRaw(path)
    }

    // MARK: - CatalogDataModel

    func homeFeed() async throws -> ApiHomeFeed {
        if !isOnline { return try await offlineDelegate.homeFeed() }
        return try await apiClient.get("catalog/home")
    }

    func titles(type: MediaType, page: Int, sort: String?) async throws -> ApiTitlePage {
        if !isOnline { return try await offlineDelegate.titles(type: type, page: page, sort: sort) }
        var query = "catalog/titles?page=\(page)&limit=25"
        if let sort { query += "&sort=\(sort)" }
        query += "&type=\(type.rawValue)"
        return try await apiClient.get(query)
    }

    func titleDetail(id: TitleID) async throws -> ApiTitleDetail {
        if !isOnline { return try await offlineDelegate.titleDetail(id: id) }
        return try await apiClient.get("catalog/titles/\(id.rawValue)")
    }

    func seasons(titleId: TitleID) async throws -> [ApiSeason] {
        if !isOnline { return try await offlineDelegate.seasons(titleId: titleId) }
        return try await apiClient.get("catalog/titles/\(titleId.rawValue)/seasons")
    }

    func episodes(titleId: TitleID, season: Int) async throws -> [ApiEpisode] {
        if !isOnline { return try await offlineDelegate.episodes(titleId: titleId, season: season) }
        return try await apiClient.get("catalog/titles/\(titleId.rawValue)/seasons/\(season)/episodes")
    }

    func search(query: String) async throws -> ApiSearchResponse {
        if !isOnline { throw DataModelError.offline }
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        return try await apiClient.get("catalog/search?q=\(encoded)")
    }

    func actorDetail(id: TmdbPersonID) async throws -> ApiActorDetail {
        if !isOnline { throw DataModelError.offline }
        return try await apiClient.get("catalog/actors/\(id.rawValue)")
    }

    func collections() async throws -> ApiCollectionListResponse {
        if !isOnline { throw DataModelError.offline }
        return try await apiClient.get("catalog/collections")
    }

    func collectionDetail(id: TmdbCollectionID) async throws -> ApiCollectionDetail {
        if !isOnline { throw DataModelError.offline }
        return try await apiClient.get("catalog/collections/\(id.rawValue)")
    }

    func tags() async throws -> ApiTagListResponse {
        if !isOnline { throw DataModelError.offline }
        return try await apiClient.get("catalog/tags")
    }

    func tagDetail(id: TagID) async throws -> ApiTagDetail {
        if !isOnline { throw DataModelError.offline }
        return try await apiClient.get("catalog/tags/\(id.rawValue)")
    }

    func genreDetail(id: GenreID) async throws -> ApiGenreDetail {
        if !isOnline { throw DataModelError.offline }
        return try await apiClient.get("catalog/genres/\(id.rawValue)")
    }

    func setFavorite(titleId: TitleID, favorite: Bool) async throws {
        if !isOnline { throw DataModelError.offline }
        if favorite {
            try await apiClient.put("catalog/titles/\(titleId.rawValue)/favorite")
        } else {
            try await apiClient.delete("catalog/titles/\(titleId.rawValue)/favorite")
        }
    }

    func setHidden(titleId: TitleID, hidden: Bool) async throws {
        if !isOnline { throw DataModelError.offline }
        if hidden {
            try await apiClient.put("catalog/titles/\(titleId.rawValue)/hidden")
        } else {
            try await apiClient.delete("catalog/titles/\(titleId.rawValue)/hidden")
        }
    }

    func requestRetranscode(titleId: TitleID) async throws {
        if !isOnline { throw DataModelError.offline }
        try await apiClient.post("catalog/titles/\(titleId.rawValue)/request-retranscode", body: [:])
    }

    func requestMobileTranscode(titleId: TitleID) async throws {
        if !isOnline { throw DataModelError.offline }
        try await apiClient.post("catalog/titles/\(titleId.rawValue)/request-mobile-transcode", body: [:])
    }

    func dismissContinueWatching(titleId: TitleID) async throws {
        if !isOnline { throw DataModelError.offline }
        try await apiClient.delete("catalog/home/continue-watching/\(titleId.rawValue)")
    }

    func dismissMissingSeason(titleId: TitleID, tmdbId: TmdbID, mediaType: MediaType, seasonNumber: Int) async throws {
        if !isOnline { throw DataModelError.offline }
        try await apiClient.post("catalog/home/dismiss-missing-season", body: [
            "title_id": titleId.rawValue,
            "season_number": seasonNumber
        ])
    }

    // MARK: - PlaybackDataModel

    func streamAsset(transcodeId: TranscodeID) async -> AVURLAsset? {
        // Prefer local file for offline or downloaded content
        if let localURL = downloads.localFileURL(for: transcodeId) {
            return AVURLAsset(url: localURL)
        }
        if !isOnline { return nil }
        guard let (url, headers) = await apiClient.streamURL(for: transcodeId.rawValue) else { return nil }
        return AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
    }

    func playbackProgress(transcodeId: TranscodeID) async -> ApiPlaybackProgress? {
        if !isOnline { return nil }
        return try? await apiClient.get("playback/progress/\(transcodeId.rawValue)")
    }

    func reportProgress(transcodeId: TranscodeID, position: Double, duration: Double?) async {
        if !isOnline {
            downloads.queueProgressUpdate(transcodeId: transcodeId, position: position, duration: duration)
            return
        }
        var body: [String: Any] = ["position": position]
        if let duration { body["duration"] = duration }
        try? await apiClient.post("playback/progress/\(transcodeId.rawValue)", body: body)
    }

    // MARK: - WishListDataModel

    func wishList() async throws -> ApiWishListResponse {
        try await apiClient.get("wishlist")
    }

    func transcodeWishList() async throws -> ApiTranscodeWishListResponse {
        try await apiClient.get("wishlist/transcodes")
    }

    func addWish(tmdbId: TmdbID, mediaType: MediaType, title: String, year: Int?,
                 posterPath: String?, seasonNumber: Int?) async throws {
        var body: [String: Any] = [
            "tmdb_id": tmdbId.rawValue,
            "media_type": mediaType.rawValue,
            "title": title
        ]
        if let year { body["release_year"] = year }
        if let posterPath { body["poster_path"] = posterPath }
        if let seasonNumber { body["season_number"] = seasonNumber }
        try await apiClient.post("wishlist", body: body)
    }

    func deleteWish(id: WishID) async throws {
        try await apiClient.delete("wishlist/\(id.rawValue)")
    }

    func voteOnWish(id: WishID, vote: Bool) async throws {
        if vote {
            try await apiClient.post("wishlist/\(id.rawValue)/vote", body: [:])
        } else {
            try await apiClient.delete("wishlist/\(id.rawValue)/vote")
        }
    }

    func dismissWish(id: WishID) async throws {
        try await apiClient.post("wishlist/\(id.rawValue)/dismiss", body: [:])
    }

    func deleteTranscodeWish(titleId: TitleID) async throws {
        try await apiClient.delete("wishlist/transcodes/\(titleId.rawValue)")
    }

    func searchTmdb(query: String) async throws -> TmdbSearchResponse {
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        return try await apiClient.get("tmdb/search?q=\(encoded)")
    }

    // MARK: - ProfileDataModel

    func profile() async throws -> ProfileResponse {
        try await apiClient.get("profile")
    }

    func updateTvQuality(_ quality: Int) async throws {
        try await apiClient.put("profile/tv-quality", body: ["min_quality": quality])
    }

    func changePassword(current: String, new: String) async throws {
        try await apiClient.post("auth/change-password", body: [
            "current_password": current,
            "new_password": new
        ])
    }

    func sessions() async throws -> ApiSessionListResponse {
        try await apiClient.get("sessions")
    }

    func deleteSession(id: SessionID, type: SessionType) async throws {
        try await apiClient.delete("sessions/\(id.rawValue)?type=\(type.rawValue)")
    }

    func deleteOtherSessions() async throws {
        try await apiClient.delete("sessions?scope=others")
    }

    // MARK: - LiveDataModel

    func cameras() async throws -> ApiCameraListResponse {
        try await apiClient.get("live/cameras")
    }

    func tvChannels() async throws -> ApiTvChannelListResponse {
        try await apiClient.get("live/tv/channels")
    }

    func warmUpStream(path: String) async throws {
        try await apiClient.warmUpStream(path)
    }

    // MARK: - AdminDataModel

    func transcodeStatus() async throws -> TranscodeStatusResponse {
        try await apiClient.get("admin/transcode-status")
    }

    func buddyStatus() async throws -> BuddyStatusResponse {
        try await apiClient.get("admin/buddy-status")
    }

    func scanNas() async throws {
        try await apiClient.post("admin/scan-nas", body: [:])
    }

    func clearFailures() async throws {
        try await apiClient.post("admin/clear-failures", body: [:])
    }

    func adminSettings() async throws -> AdminSettingsResponse {
        try await apiClient.get("admin/settings")
    }

    func updateSetting(key: String, value: String?) async throws {
        try await apiClient.put("admin/settings", body: [key: value ?? ""])
    }

    func linkedTranscodes(page: Int) async throws -> AdminLinkedTranscodeResponse {
        try await apiClient.get("admin/transcodes/linked?page=\(page)&limit=50")
    }

    func unlinkTranscode(id: TranscodeID) async throws {
        try await apiClient.post("admin/transcodes/\(id.rawValue)/unlink", body: [:])
    }

    func adminTags() async throws -> ApiTagListResponse {
        try await apiClient.get("admin/tags")
    }

    func createTag(name: String, color: String) async throws {
        try await apiClient.post("admin/tags", body: ["name": name, "color": color])
    }

    func updateTag(id: TagID, name: String, color: String) async throws {
        try await apiClient.put("admin/tags/\(id.rawValue)", body: ["name": name, "color": color])
    }

    func deleteTag(id: TagID) async throws {
        try await apiClient.delete("admin/tags/\(id.rawValue)")
    }

    func dataQuality(page: Int) async throws -> AdminDataQualityResponse {
        try await apiClient.get("admin/data-quality?page=\(page)&limit=50")
    }

    func reEnrich(titleId: TitleID) async throws {
        try await apiClient.post("admin/titles/\(titleId.rawValue)/re-enrich", body: [:])
    }

    func deleteTitle(id: TitleID) async throws {
        try await apiClient.delete("admin/titles/\(id.rawValue)")
    }

    func purchaseWishes() async throws -> AdminPurchaseWishListResponse {
        try await apiClient.get("admin/purchase-wishes")
    }

    func updatePurchaseWishStatus(tmdbId: TmdbID, status: AcquisitionStatus) async throws {
        try await apiClient.put("admin/purchase-wishes/\(tmdbId.rawValue)/status", body: ["status": status.rawValue])
    }

    func adminUsers() async throws -> AdminUserListResponse {
        try await apiClient.get("admin/users")
    }

    func createUser(username: String, password: String, displayName: String?, accessLevel: Int) async throws {
        var body: [String: Any] = [
            "username": username,
            "password": password,
            "force_change": true
        ]
        if let displayName { body["display_name"] = displayName }
        try await apiClient.post("admin/users", body: body)
    }

    func updateUserRole(id: UserID, accessLevel: Int) async throws {
        try await apiClient.put("admin/users/\(id.rawValue)/role", body: ["access_level": accessLevel])
    }

    func updateUserRatingCeiling(id: UserID, ceiling: Int?) async throws {
        let body: [String: Any] = ceiling != nil ? ["ceiling": ceiling!] : ["ceiling": NSNull()]
        try await apiClient.put("admin/users/\(id.rawValue)/rating-ceiling", body: body)
    }

    func unlockUser(id: UserID) async throws {
        try await apiClient.post("admin/users/\(id.rawValue)/unlock", body: [:])
    }

    func forcePasswordChange(id: UserID) async throws {
        try await apiClient.post("admin/users/\(id.rawValue)/force-password-change", body: [:])
    }

    func resetPassword(id: UserID, newPassword: String) async throws {
        try await apiClient.post("admin/users/\(id.rawValue)/reset-password", body: [
            "new_password": newPassword,
            "force_change": true
        ])
    }

    func deleteUser(id: UserID) async throws {
        try await apiClient.delete("admin/users/\(id.rawValue)")
    }

    func unmatchedFiles() async throws -> AdminUnmatchedResponse {
        try await apiClient.get("admin/transcodes/unmatched")
    }

    func acceptUnmatched(id: UnmatchedFileID) async throws {
        try await apiClient.post("admin/transcodes/unmatched/\(id.rawValue)/accept", body: [:])
    }

    func ignoreUnmatched(id: UnmatchedFileID) async throws {
        try await apiClient.post("admin/transcodes/unmatched/\(id.rawValue)/ignore", body: [:])
    }

    func linkUnmatched(id: UnmatchedFileID, titleId: TitleID) async throws {
        try await apiClient.post("admin/transcodes/unmatched/\(id.rawValue)/link", body: ["title_id": titleId.rawValue])
    }
}
