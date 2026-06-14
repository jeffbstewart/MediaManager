import Foundation
import AVFoundation
import Observation

@Observable
@MainActor
public final class OnlineDataModel: DataModel {
    public let apiClient: APIClient
    private let authManager: AuthManager
    public let downloads: DownloadManager
    public var grpcClient: GrpcClient { authManager.grpcClient }
    private var _offlineDelegate: OfflineDataModel?
    private var offlineDelegate: OfflineDataModel {
        if let d = _offlineDelegate { return d }
        let d = OfflineDataModel(onlineModel: self)
        _offlineDelegate = d
        return d
    }

    public var isOnline: Bool { !downloads.isEffectivelyOffline }

    public var capabilities: [String] {
        authManager.serverInfo?.capabilities ?? authManager.cachedCapabilities
    }

    public var userInfo: ServerUserInfo? {
        authManager.serverInfo?.user
    }

    public init(authManager: AuthManager, downloadManager: DownloadManager) {
        self.authManager = authManager
        self.apiClient = authManager.apiClient
        self.downloads = downloadManager
    }

    // MARK: - Image Data (stays HTTP)

    public func imageData(path: String) async -> Data? {
        try? await apiClient.getRaw(path)
    }

    // MARK: - CatalogDataModel

    public func homeFeed() async throws -> ApiHomeFeed {
        if !isOnline { return try await offlineDelegate.homeFeed() }
        let response = try await grpcClient.homeFeed()
        return ApiHomeFeed(proto: response)
    }

    public func titles(type: MediaType, page: Int, sort: String?, query: String?) async throws -> ApiTitlePage {
        if !isOnline { return try await offlineDelegate.titles(type: type, page: page, sort: sort, query: query) }
        let protoType: MMMediaType = switch type {
        case .movie: .movie
        case .tv: .tv
        case .personal: .personal
        }
        let response = try await grpcClient.listTitles(
            type: protoType, page: Int32(page), limit: 25, sort: sort,
            query: (query?.isEmpty ?? true) ? nil : query, playableOnly: false)
        return ApiTitlePage(proto: response)
    }

    public func titleDetail(id: TitleID) async throws -> ApiTitleDetail {
        if !isOnline { return try await offlineDelegate.titleDetail(id: id) }
        let response = try await grpcClient.getTitleDetail(id: id.protoValue)
        return ApiTitleDetail(proto: response)
    }

    public func seasons(titleId: TitleID) async throws -> [ApiSeason] {
        if !isOnline { return try await offlineDelegate.seasons(titleId: titleId) }
        let response = try await grpcClient.listSeasons(titleId: titleId.protoValue)
        return response.seasons.map { ApiSeason(proto: $0) }
    }

    public func episodes(titleId: TitleID, season: Int) async throws -> [ApiEpisode] {
        if !isOnline { return try await offlineDelegate.episodes(titleId: titleId, season: season) }
        let response = try await grpcClient.listEpisodes(titleId: titleId.protoValue, season: Int32(season))
        return response.episodes.map { ApiEpisode(proto: $0) }
    }

    public func searchMusicOnly(query: String) async throws -> ApiSearchResponse {
        if !isOnline { throw DataModelError.offline }
        return ApiSearchResponse(proto: try await grpcClient.searchMusicOnly(query: query))
    }

    public func search(query: String) async throws -> ApiSearchResponse {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.search(query: query)
        return ApiSearchResponse(proto: response)
    }

    public func actorDetail(id: TmdbPersonID) async throws -> ApiActorDetail {
        if !isOnline { return try await offlineDelegate.actorDetail(id: id) }
        let response = try await grpcClient.getActorDetail(personId: id.protoValue)
        return ApiActorDetail(proto: response)
    }

    public func collections() async throws -> ApiCollectionListResponse {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.listCollections()
        return ApiCollectionListResponse(proto: response)
    }

    public func collectionDetail(id: TmdbCollectionID) async throws -> ApiCollectionDetail {
        if !isOnline { return try await offlineDelegate.collectionDetail(id: id) }
        let response = try await grpcClient.getCollectionDetail(id: id.protoValue)
        return ApiCollectionDetail(proto: response)
    }

    public func tags() async throws -> ApiTagListResponse {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.listTags()
        return ApiTagListResponse(proto: response)
    }

    public func tagDetail(id: TagID) async throws -> ApiTagDetail {
        if !isOnline { return try await offlineDelegate.tagDetail(id: id) }
        let response = try await grpcClient.getTagDetail(id: id.protoValue)
        return ApiTagDetail(proto: response)
    }

    public func genreDetail(id: GenreID) async throws -> ApiGenreDetail {
        if !isOnline { return try await offlineDelegate.genreDetail(id: id) }
        let response = try await grpcClient.getGenreDetail(id: id.protoValue)
        return ApiGenreDetail(proto: response)
    }

    public func setFavorite(titleId: TitleID, favorite: Bool) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.setFavorite(titleId: titleId.protoValue, value: favorite)
    }

    public func setHidden(titleId: TitleID, hidden: Bool) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.setHidden(titleId: titleId.protoValue, value: hidden)
    }

    public func requestRetranscode(titleId: TitleID) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.requestRetranscode(titleId: titleId.protoValue)
    }

    public func requestMobileTranscode(titleId: TitleID) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.requestLowStorageTranscode(titleId: titleId.protoValue)
    }

    public func dismissContinueWatching(titleId: TitleID) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.dismissContinueWatching(titleId: titleId.protoValue)
    }

    public func dismissMissingSeason(titleId: TitleID, tmdbId: TmdbID, mediaType: MediaType, seasonNumber: Int) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.dismissMissingSeason(titleId: titleId.protoValue, seasonNumber: Int32(seasonNumber))
    }

    // MARK: - Books

    public func authors(page: Int, sort: AuthorSort, query: String?, hiddenOnly: Bool) async throws -> ApiAuthorListResponse {
        if !isOnline {
            return try await offlineDelegate.authors(
                page: page, sort: sort, query: query, hiddenOnly: hiddenOnly)
        }
        let response = try await grpcClient.listAuthors(
            page: Int32(page), limit: 60, sort: sort.protoValue,
            query: query, hiddenOnly: hiddenOnly)
        return ApiAuthorListResponse(proto: response)
    }

    public func authorDetail(id: AuthorID) async throws -> ApiAuthorDetail {
        if !isOnline { return try await offlineDelegate.authorDetail(id: id) }
        let response = try await grpcClient.getAuthorDetail(id: id.protoValue)
        return ApiAuthorDetail(proto: response)
    }

    public func bookSeriesDetail(id: BookSeriesID) async throws -> ApiBookSeriesDetail {
        if !isOnline { return try await offlineDelegate.bookSeriesDetail(id: id) }
        let response = try await grpcClient.getBookSeriesDetail(id: id.protoValue)
        return ApiBookSeriesDetail(proto: response)
    }

    public func artists(page: Int, sort: ArtistSort, query: String?) async throws -> ApiArtistListResponse {
        if !isOnline { return try await offlineDelegate.artists(page: page, sort: sort, query: query) }
        let response = try await grpcClient.listArtists(
            page: Int32(page), limit: 60, sort: sort.rawValue,
            query: query, playableOnly: true)
        return ApiArtistListResponse(proto: response)
    }

    public func artistDetail(id: ArtistID) async throws -> ApiArtistDetail {
        if !isOnline { return try await offlineDelegate.artistDetail(id: id) }
        let response = try await grpcClient.getArtistDetail(id: id.protoValue)
        return ApiArtistDetail(proto: response)
    }

    public func libraryShuffle(limit: Int) async throws -> [ApiTrack] {
        if !isOnline { return try await offlineDelegate.libraryShuffle(limit: limit) }
        let response = try await grpcClient.libraryShuffle(limit: Int32(limit))
        return response.tracks.map { ApiTrack(proto: $0) }
    }

    public func smartPlaylists() async throws -> [ApiSmartPlaylistSummary] {
        if !isOnline { return try await offlineDelegate.smartPlaylists() }
        let response = try await grpcClient.listSmartPlaylists()
        return response.playlists.map { ApiSmartPlaylistSummary(proto: $0) }
    }

    public func smartPlaylist(key: String) async throws -> ApiSmartPlaylistDetail {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.getSmartPlaylist(key: key)
        return ApiSmartPlaylistDetail(proto: response)
    }

    public func dismissHomeCarouselItem(titleId: TitleID, carousel: HomeCarousel) async throws {
        if !isOnline { throw DataModelError.offline }
        let proto: MMHomeCarousel = switch carousel {
        case .recentlyAddedAlbums: .recentlyAddedAlbums
        case .recentlyAddedBooks: .recentlyAddedBooks
        case .recentlyAddedMovies: .recentlyAddedMovies
        }
        try await grpcClient.dismissHomeCarouselItem(titleId: titleId.protoValue, carousel: proto)
    }

    // MARK: - Radio

    public func startRadio(seedTrackId: Int64? = nil, seedAlbumId: Int64? = nil) async throws -> ApiStartRadioResponse {
        if !isOnline { throw DataModelError.offline }
        return try await grpcClient.startRadio(seedTrackId: seedTrackId, seedAlbumId: seedAlbumId)
    }

    public func nextRadioBatch(sessionId: String, history: [MMRadioTrackHistory]) async throws -> [ApiTrack] {
        if !isOnline { throw DataModelError.offline }
        return try await grpcClient.nextRadioBatch(sessionId: sessionId, history: history)
    }

    public func stopRadio(sessionId: String) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.stopRadio(sessionId: sessionId)
    }

    // MARK: - Recommendations

    public func recommendedArtists(limit: Int = 30) async throws -> [ApiRecommendedArtist] {
        if !isOnline { throw DataModelError.offline }
        return try await grpcClient.listRecommendedArtists(limit: Int32(limit))
    }

    public func dismissRecommendation(mbid: String) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.dismissRecommendation(mbid: mbid)
    }

    // MARK: - Advanced (dance) search

    public func advancedSearchPresets() async throws -> [ApiAdvancedSearchPreset] {
        if !isOnline { throw DataModelError.offline }
        return try await grpcClient.listAdvancedSearchPresets()
    }

    public func searchTracks(filters: AdvancedTrackSearchFilters) async throws -> [ApiTrackSearchHit] {
        if !isOnline { throw DataModelError.offline }
        return try await grpcClient.searchTracks(filters: filters)
    }

    // MARK: - User playlists

    public func playlists(scope: PlaylistScope) async throws -> [ApiPlaylistSummary] {
        if !isOnline { return try await offlineDelegate.playlists(scope: scope) }
        let protoScope: MMPlaylistScope = switch scope {
        case .mine: .mine
        case .all: .all
        }
        let response = try await grpcClient.listPlaylists(scope: protoScope)
        return response.playlists.map { ApiPlaylistSummary(proto: $0) }
    }

    public func playlist(id: Int64) async throws -> ApiPlaylistDetail {
        if !isOnline { return try await offlineDelegate.playlist(id: id) }
        let response = try await grpcClient.getPlaylist(id: id)
        return ApiPlaylistDetail(proto: response)
    }

    public func createPlaylist(name: String, description: String?) async throws -> ApiPlaylistSummary {
        if !isOnline { throw DataModelError.offline }
        let response = try await grpcClient.createPlaylist(name: name, description: description)
        return ApiPlaylistSummary(proto: response)
    }

    public func renamePlaylist(id: Int64, name: String, description: String?) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.renamePlaylist(id: id, name: name, description: description)
    }

    public func deletePlaylist(id: Int64) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.deletePlaylist(id: id)
    }

    public func addTracksToPlaylist(id: Int64, trackIds: [Int64]) async throws {
        if !isOnline { throw DataModelError.offline }
        _ = try await grpcClient.addTracksToPlaylist(id: id, trackIds: trackIds)
    }

    public func removeTrackFromPlaylist(id: Int64, playlistTrackId: Int64) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.removeTrackFromPlaylist(id: id, playlistTrackId: playlistTrackId)
    }

    public func reorderPlaylist(id: Int64, playlistTrackIdsInOrder: [Int64]) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.reorderPlaylist(id: id, playlistTrackIdsInOrder: playlistTrackIdsInOrder)
    }

    public func setPlaylistHero(id: Int64, trackId: Int64?) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.setPlaylistHero(id: id, trackId: trackId)
    }

    public func setPlaylistPrivacy(id: Int64, isPrivate: Bool) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.setPlaylistPrivacy(id: id, isPrivate: isPrivate)
    }

    // MARK: - PlaybackDataModel

    public func streamAsset(transcodeId: TranscodeID) async -> AVURLAsset? {
        // Prefer local file for offline or downloaded content
        if let localURL = downloads.localVideoURL(for: transcodeId.protoValue) {
            return AVURLAsset(url: localURL)
        }
        if !isOnline { return nil }
        // Video streaming stays HTTP (Range requests, HLS)
        guard let (url, headers) = await apiClient.streamURL(for: transcodeId.rawValue) else { return nil }
        return AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
    }

    public func playbackProgress(transcodeId: TranscodeID) async -> ApiPlaybackProgress? {
        // Offline: short-circuit to the local shadow. Online: prefer
        // the server (canonical, cross-device), fall back to local
        // when the server has no row yet — common when a download was
        // played offline and is now being opened online for the first
        // time before the queued update flushed.
        if !isOnline {
            return await LocalProgressStore.shared.apiPlaybackProgress(transcodeId: transcodeId.protoValue)
        }
        if let response = try? await grpcClient.getProgress(transcodeId: transcodeId.protoValue),
           response.hasPosition {
            return ApiPlaybackProgress(proto: response)
        }
        return await LocalProgressStore.shared.apiPlaybackProgress(transcodeId: transcodeId.protoValue)
    }

    public func reportProgress(transcodeId: TranscodeID, position: Double, duration: Double?) async {
        // Always write to the local shadow first — that's what makes
        // the resume marker survive a switch to offline mode for any
        // title the user has ever watched on this device, regardless
        // of whether it's downloaded.
        await LocalProgressStore.shared.recordPlayback(
            transcodeId: transcodeId.protoValue, position: position, duration: duration)
        if !isOnline {
            // Still funnel through DownloadManager's queue so the
            // server gets a deferred update when connectivity comes
            // back (covers the cross-device case).
            downloads.queueProgressUpdate(transcodeId: transcodeId.protoValue, position: position, duration: duration)
            return
        }
        try? await grpcClient.reportProgress(transcodeId: transcodeId.protoValue, position: position, duration: duration)
    }

    public func readingProgress(mediaItemId: Int64) async -> ApiReadingProgress? {
        if !isOnline {
            return await LocalProgressStore.shared.apiReadingProgress(mediaItemId: mediaItemId)
        }
        if let response = try? await grpcClient.getReadingProgress(mediaItemId: mediaItemId),
           !response.locator.isEmpty {
            return ApiReadingProgress(proto: response)
        }
        return await LocalProgressStore.shared.apiReadingProgress(mediaItemId: mediaItemId)
    }

    public func reportReadingProgress(mediaItemId: Int64, locator: String, fraction: Double?) async {
        await LocalProgressStore.shared.recordReading(
            mediaItemId: mediaItemId, locator: locator, fraction: fraction)
        if !isOnline { return }
        try? await grpcClient.reportReadingProgress(
            mediaItemId: mediaItemId, locator: locator, fraction: fraction)
    }

    // MARK: - WishListDataModel

    public func wishList() async throws -> ApiWishListResponse {
        let response = try await grpcClient.wishList()
        return ApiWishListResponse(proto: response)
    }

    public func transcodeWishList() async throws -> ApiTranscodeWishListResponse {
        let response = try await grpcClient.transcodeWishList()
        return ApiTranscodeWishListResponse(proto: response)
    }

    public func addWish(tmdbId: TmdbID, mediaType: MediaType, title: String, year: Int?,
                 seasonNumber: Int?) async throws {
        _ = try await grpcClient.addWish(
            tmdbId: tmdbId.protoValue,
            mediaType: mediaType.protoMediaType,
            title: title,
            releaseYear: year.map { Int32($0) },
            seasonNumber: seasonNumber.map { Int32($0) })
    }

    public func addBookWish(olWorkId: String, title: String, author: String?) async throws {
        if !isOnline { return try await offlineDelegate.addBookWish(
            olWorkId: olWorkId, title: title, author: author) }
        _ = try await grpcClient.addBookWish(
            olWorkId: olWorkId, title: title, author: author)
    }

    public func removeBookWish(olWorkId: String) async throws {
        if !isOnline { return try await offlineDelegate.removeBookWish(olWorkId: olWorkId) }
        try await grpcClient.removeBookWish(olWorkId: olWorkId)
    }

    public func addAlbumWish(releaseGroupId: String, title: String, primaryArtist: String?) async throws {
        if !isOnline { return try await offlineDelegate.addAlbumWish(
            releaseGroupId: releaseGroupId, title: title, primaryArtist: primaryArtist) }
        _ = try await grpcClient.addAlbumWish(
            releaseGroupId: releaseGroupId, title: title, primaryArtist: primaryArtist)
    }

    public func removeAlbumWish(releaseGroupId: String) async throws {
        if !isOnline { return try await offlineDelegate.removeAlbumWish(releaseGroupId: releaseGroupId) }
        try await grpcClient.removeAlbumWish(releaseGroupId: releaseGroupId)
    }

    public func wishlistSeriesGaps(seriesId: BookSeriesID) async throws -> (added: Int, alreadyWished: Int) {
        if !isOnline { return try await offlineDelegate.wishlistSeriesGaps(seriesId: seriesId) }
        let response = try await grpcClient.wishlistSeriesGaps(seriesId: seriesId.protoValue)
        if response.hasError, !response.error.isEmpty {
            throw NSError(domain: "WishlistSeriesGaps", code: 1, userInfo: [
                NSLocalizedDescriptionKey: response.error,
            ])
        }
        return (added: Int(response.added), alreadyWished: Int(response.alreadyWished))
    }

    public func deleteWish(id: WishID) async throws {
        try await grpcClient.deleteWish(id: id.protoValue)
    }

    public func voteOnWish(id: WishID, vote: Bool) async throws {
        try await grpcClient.voteOnWish(id: id.protoValue, vote: vote)
    }

    public func dismissWish(id: WishID) async throws {
        try await grpcClient.dismissWish(id: id.protoValue)
    }

    public func deleteTranscodeWish(titleId: TitleID) async throws {
        try await grpcClient.deleteTranscodeWish(titleId: titleId.protoValue)
    }

    public func searchTmdb(query: String) async throws -> TmdbSearchResponse {
        let response = try await grpcClient.searchTmdb(query: query, type: .unknown)
        return TmdbSearchResponse(proto: response)
    }

    public func searchTmdb(query: String, type: MMMediaType) async throws -> MMTmdbSearchResponse {
        try await grpcClient.searchTmdb(query: query, type: type)
    }

    // MARK: - ProfileDataModel

    public func profile() async throws -> ProfileResponse {
        let response = try await grpcClient.getProfile()
        return ProfileResponse(proto: response)
    }

    public func updateTvQuality(_ quality: Int) async throws {
        let protoQuality: MMQuality = switch quality {
        case 1: .sd
        case 2: .fhd
        case 3: .uhd
        default: .unknown
        }
        try await grpcClient.updateTvQuality(protoQuality)
    }

    public func changePassword(current: String, new: String) async throws {
        _ = try await grpcClient.changePassword(current: current, new: new)
    }

    public func sessions() async throws -> ApiSessionListResponse {
        let response = try await grpcClient.listSessions()
        return ApiSessionListResponse(proto: response)
    }

    public func deleteSession(id: SessionID, type: SessionType) async throws {
        let protoType: MMSessionType = switch type {
        case .access: .browser
        case .refresh: .app
        }
        try await grpcClient.deleteSession(id: id.protoValue, type: protoType)
    }

    public func deleteOtherSessions() async throws {
        try await grpcClient.deleteOtherSessions()
    }

    // MARK: - LiveDataModel

    public func cameras() async throws -> ApiCameraListResponse {
        let response = try await grpcClient.listCameras()
        return ApiCameraListResponse(proto: response)
    }

    public func tvChannels() async throws -> ApiTvChannelListResponse {
        let response = try await grpcClient.listTvChannels()
        return ApiTvChannelListResponse(proto: response)
    }

    public func warmUpStream(path: String) async throws {
        // Camera and live TV warmup hit HTTP HLS endpoints that block until
        // segments are ready. Use HTTP for these since gRPC WarmUpStream is
        // a metadata trigger, not an HLS blocking call.
        try await apiClient.warmUpStream(path)
    }

    // MARK: - AdminDataModel

    public func transcodeStatus() async throws -> TranscodeStatusResponse {
        let response = try await grpcClient.adminTranscodeStatus()
        return TranscodeStatusResponse(proto: response)
    }

    public func monitorTranscodeStatus(onUpdate: @Sendable @escaping (MMTranscodeStatusUpdate) async -> Void) async throws {
        try await grpcClient.adminMonitorTranscodeStatus(onUpdate: onUpdate)
    }

    public func buddyStatus() async throws -> BuddyStatusResponse {
        let response = try await grpcClient.adminBuddyStatus()
        return BuddyStatusResponse(proto: response)
    }

    public func submitBarcode(upc: String) async throws -> MMSubmitBarcodeResponse {
        try await grpcClient.adminSubmitBarcode(upc: upc)
    }

    public func monitorScanProgress(onUpdate: @Sendable @escaping (MMScanProgressUpdate) async -> Void) async throws {
        try await grpcClient.adminMonitorScanProgress(onUpdate: onUpdate)
    }

    public func getScanDetail(scanId: Int64) async throws -> MMScanDetailResponse {
        try await grpcClient.adminGetScanDetail(scanId: scanId)
    }

    public func assignTmdb(titleId: Int64, tmdbId: Int32, mediaType: MMMediaType) async throws -> MMAssignTmdbResponse {
        try await grpcClient.adminAssignTmdb(titleId: titleId, tmdbId: tmdbId, mediaType: mediaType)
    }

    public func searchMusicBrainz(query: String, barcode: String?) async throws -> MMSearchMusicBrainzResponse {
        if !isOnline { throw DataModelError.offline }
        return try await grpcClient.searchMusicBrainz(query: query, barcode: barcode)
    }

    public func assignMusicBrainzRelease(titleId: Int64, releaseMbid: String) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.assignMusicBrainzRelease(titleId: titleId, releaseMbid: releaseMbid)
    }

    public func updatePurchaseInfo(scanId: Int64, place: String?, date: MMCalendarDate?, price: Double?) async throws {
        try await grpcClient.adminUpdatePurchaseInfo(scanId: scanId, place: place, date: date, price: price)
    }

    public func uploadOwnershipPhoto(scanId: Int64, photoData: Data, contentType: String) async throws -> MMUploadOwnershipPhotoResponse {
        try await grpcClient.adminUploadOwnershipPhoto(scanId: scanId, photoData: photoData, contentType: contentType)
    }

    public func deleteOwnershipPhoto(photoId: String) async throws {
        try await grpcClient.adminDeleteOwnershipPhoto(photoId: photoId)
    }

    public func adminListCameras() async throws -> MMAdminCameraListResponse {
        try await grpcClient.adminListCameras()
    }

    public func adminCreateCamera(name: String, rtspUrl: String, snapshotUrl: String, streamName: String?, enabled: Bool) async throws -> MMAdminCamera {
        try await grpcClient.adminCreateCamera(name: name, rtspUrl: rtspUrl, snapshotUrl: snapshotUrl, streamName: streamName, enabled: enabled)
    }

    public func adminUpdateCamera(id: Int64, name: String, rtspUrl: String, snapshotUrl: String, streamName: String, enabled: Bool) async throws -> MMAdminCamera {
        try await grpcClient.adminUpdateCamera(id: id, name: name, rtspUrl: rtspUrl, snapshotUrl: snapshotUrl, streamName: streamName, enabled: enabled)
    }

    public func adminDeleteCamera(id: Int64) async throws {
        try await grpcClient.adminDeleteCamera(id: id)
    }

    public func adminReorderCameras(ids: [Int64]) async throws {
        try await grpcClient.adminReorderCameras(ids: ids)
    }

    public func scanNas() async throws {
        try await grpcClient.adminScanNas()
    }

    public func clearFailures() async throws {
        try await grpcClient.adminClearFailures()
    }

    public func adminSettings() async throws -> AdminSettingsResponse {
        let response = try await grpcClient.adminGetSettings()
        return AdminSettingsResponse(proto: response)
    }

    public func updateSetting(key: String, value: String?) async throws {
        try await grpcClient.adminUpdateSetting(key: key, value: value ?? "")
    }

    public func linkedTranscodes(page: Int) async throws -> AdminLinkedTranscodeResponse {
        let response = try await grpcClient.adminListLinkedTranscodes(page: Int32(page))
        return AdminLinkedTranscodeResponse(proto: response)
    }

    public func unlinkTranscode(id: TranscodeID) async throws {
        try await grpcClient.adminUnlinkTranscode(id: id.protoValue)
    }

    public func adminTags() async throws -> ApiTagListResponse {
        let response = try await grpcClient.adminListTags()
        // Admin tag list uses MMAdminTagListResponse; convert to ApiTagListResponse
        let items = response.tags.map { tag in
            ApiTagListItem(adminProto: tag)
        }
        return ApiTagListResponse(tags: items)
    }

    public func createTag(name: String, color: String) async throws {
        try await grpcClient.adminCreateTag(name: name, color: color)
    }

    public func updateTag(id: TagID, name: String, color: String) async throws {
        try await grpcClient.adminUpdateTag(id: id.protoValue, name: name, color: color)
    }

    public func deleteTag(id: TagID) async throws {
        try await grpcClient.adminDeleteTag(id: id.protoValue)
    }

    public func dataQuality(page: Int) async throws -> AdminDataQualityResponse {
        let response = try await grpcClient.adminListDataQuality(page: Int32(page))
        return AdminDataQualityResponse(proto: response)
    }

    public func reEnrich(titleId: TitleID) async throws {
        try await grpcClient.adminReEnrich(titleId: titleId.protoValue)
    }

    public func deleteTitle(id: TitleID) async throws {
        try await grpcClient.adminDeleteTitle(id: id.protoValue)
    }

    public func purchaseWishes() async throws -> AdminPurchaseWishListResponse {
        let response = try await grpcClient.adminListPurchaseWishes()
        return AdminPurchaseWishListResponse(proto: response)
    }

    public func updatePurchaseWishStatus(tmdbId: TmdbID, mediaType: MediaType, seasonNumber: Int?, status: AcquisitionStatus) async throws {
        try await grpcClient.adminUpdatePurchaseWishStatus(
            tmdbId: tmdbId.protoValue,
            mediaType: mediaType,
            seasonNumber: seasonNumber.map { Int32($0) },
            status: status
        )
    }

    public func adminUsers() async throws -> AdminUserListResponse {
        let response = try await grpcClient.adminListUsers()
        return AdminUserListResponse(proto: response)
    }

    public func createUser(username: String, password: String, displayName: String?, accessLevel: Int) async throws {
        try await grpcClient.adminCreateUser(username: username, password: password, displayName: displayName)
    }

    public func updateUserRole(id: UserID, accessLevel: Int) async throws {
        try await grpcClient.adminUpdateUserRole(id: id.protoValue, accessLevel: Int32(accessLevel))
    }

    public func updateUserRatingCeiling(id: UserID, ceiling: Int?) async throws {
        try await grpcClient.adminUpdateUserRatingCeiling(id: id.protoValue, ceiling: ceiling.map { Int32($0) })
    }

    public func unlockUser(id: UserID) async throws {
        try await grpcClient.adminUnlockUser(id: id.protoValue)
    }

    public func forcePasswordChange(id: UserID) async throws {
        try await grpcClient.adminForcePasswordChange(id: id.protoValue)
    }

    public func resetPassword(id: UserID, newPassword: String) async throws {
        try await grpcClient.adminResetPassword(id: id.protoValue, newPassword: newPassword)
    }

    public func deleteUser(id: UserID) async throws {
        try await grpcClient.adminDeleteUser(id: id.protoValue)
    }

    public func unmatchedFiles() async throws -> AdminUnmatchedResponse {
        let response = try await grpcClient.adminListUnmatchedFiles()
        return AdminUnmatchedResponse(proto: response)
    }

    public func acceptUnmatched(id: UnmatchedFileID) async throws {
        try await grpcClient.adminAcceptUnmatched(id: id.protoValue)
    }

    public func ignoreUnmatched(id: UnmatchedFileID) async throws {
        try await grpcClient.adminIgnoreUnmatched(id: id.protoValue)
    }

    public func linkUnmatched(id: UnmatchedFileID, titleId: TitleID) async throws {
        try await grpcClient.adminLinkUnmatched(id: id.protoValue, titleId: titleId.protoValue)
    }

    public func setAuthorHidden(id: AuthorID, hidden: Bool) async throws {
        if !isOnline { throw DataModelError.offline }
        try await grpcClient.adminSetAuthorHidden(authorId: id.protoValue, hidden: hidden)
    }
}
