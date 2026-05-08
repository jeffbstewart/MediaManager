import Foundation
import AVFoundation
import Observation

private let offlineLogger = MMLogger(category: "OfflineDataModel")

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

    // MARK: - Offline music helpers

    /// Walk the audio cache once and return parsed (downloaded,
    /// detail, album) triples for every downloaded album where the
    /// cached MMTitleDetail proto deserialises and carries an album
    /// sub-message. Used by the offline music browse implementations
    /// below — homeFeed / artists / artistDetail / search.
    private func cachedAudioAlbums() -> [(DownloadedAlbum, ApiTitleDetail, ApiAlbum)] {
        guard let audioCache = AppServices.shared.audioCache else { return [] }
        var out: [(DownloadedAlbum, ApiTitleDetail, ApiAlbum)] = []
        for album in audioCache.downloads {
            guard let detail = audioCache.cachedAlbumDetail(titleId: album.titleId),
                  let apiAlbum = detail.album else { continue }
            out.append((album, detail, apiAlbum))
        }
        return out
    }

    /// Same as cachedAudioAlbums but for playlists.
    private func cachedAudioPlaylists() -> [(DownloadedPlaylist, ApiPlaylistDetail)] {
        guard let audioCache = AppServices.shared.audioCache else { return [] }
        var out: [(DownloadedPlaylist, ApiPlaylistDetail)] = []
        for playlist in audioCache.playlistDownloads {
            guard let detail = audioCache.cachedPlaylistDetail(playlistId: playlist.playlistId)
            else { continue }
            out.append((playlist, detail))
        }
        return out
    }

    func homeFeed() async throws -> ApiHomeFeed {
        // Synthesize a minimal home feed for offline browsing.
        // Music landing page reads `recentlyAddedAlbums`; everything
        // else (movie carousels, resume reading, missing seasons)
        // requires the server. We populate the audio carousel from
        // downloaded albums sorted by downloadedAt descending; the
        // sections that have no offline content render empty.
        var proto = MMHomeFeedResponse()
        let albums = cachedAudioAlbums()
            .sorted { $0.0.downloadedAt > $1.0.downloadedAt }
        proto.recentlyAddedAlbums = albums.map { $0.1.proto.title }
        return ApiHomeFeed(proto: proto)
    }

    func titles(type: MediaType, page: Int, sort: String?) async throws -> ApiTitlePage {
        throw DataModelError.offline
    }

    func titleDetail(id: TitleID) async throws -> ApiTitleDetail {
        guard let detail = downloads.loadCachedTitleDetail(for: id) else {
            throw DataModelError.offline
        }
        return detail
    }

    func seasons(titleId: TitleID) async throws -> [ApiSeason] {
        // Derive seasons from downloaded episodes
        let entries = downloads.entries.filter {
            $0.titleID == titleId.protoValue && $0.state == .completed && $0.seasonNumber > 0
        }
        let seasonNumbers = Set(entries.map { Int($0.seasonNumber) }).sorted()
        if seasonNumbers.isEmpty { throw DataModelError.offline }
        return seasonNumbers.map { num in
            var proto = MMSeason()
            proto.seasonNumber = Int32(num)
            proto.name = "Season \(num)"
            proto.episodeCount = Int32(entries.filter { Int($0.seasonNumber) == num }.count)
            return ApiSeason(proto: proto)
        }
    }

    func episodes(titleId: TitleID, season: Int) async throws -> [ApiEpisode] {
        // Build episodes from downloaded entries for this season
        let entries = downloads.entries.filter {
            $0.titleID == titleId.protoValue && $0.state == .completed && Int($0.seasonNumber) == season
        }.sorted { $0.episodeNumber < $1.episodeNumber }
        if entries.isEmpty { throw DataModelError.offline }
        return entries.map { entry in
            var proto = MMEpisode()
            proto.episodeID = entry.transcodeID  // use transcodeId as stand-in for episodeId
            proto.transcodeID = entry.transcodeID
            proto.seasonNumber = entry.seasonNumber
            proto.episodeNumber = entry.episodeNumber
            if !entry.episodeTitle.isEmpty { proto.name = entry.episodeTitle }
            proto.playable = true
            switch entry.quality {
            case .fhd: proto.quality = .fhd
            case .uhd: proto.quality = .uhd
            case .sd: proto.quality = .sd
            default: proto.quality = .unknown
            }
            return ApiEpisode(proto: proto)
        }
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

    // Books (offline: nothing yet — book download cache lands in a later phase)
    func authors(page: Int, sort: AuthorSort, query: String?, hiddenOnly: Bool) async throws -> ApiAuthorListResponse {
        throw DataModelError.offline
    }
    func authorDetail(id: AuthorID) async throws -> ApiAuthorDetail {
        throw DataModelError.offline
    }
    func bookSeriesDetail(id: BookSeriesID) async throws -> ApiBookSeriesDetail {
        throw DataModelError.offline
    }
    // Audio (offline: nothing yet — audio download cache lands later).
    func artists(page: Int, sort: ArtistSort, query: String?) async throws -> ApiArtistListResponse {
        // Aggregate downloaded albums by their lead album-artist.
        // Sort + query happen client-side since the offline set is
        // small; pagination is ignored (return everything in one
        // page — drivers don't need server-style paging when the
        // catalog is sub-1000 items).
        var byArtistId: [Int64: (proto: MMArtist, count: Int, fallbackTitle: Int64)] = [:]
        let cached = cachedAudioAlbums()
        var withArtists = 0
        var withoutArtists = 0
        for (album, _, apiAlbum) in cached {
            guard let lead = apiAlbum.albumArtists.first else {
                withoutArtists += 1
                continue
            }
            withArtists += 1
            let artistId = lead.id.protoValue
            if var existing = byArtistId[artistId] {
                existing.count += 1
                byArtistId[artistId] = existing
            } else {
                byArtistId[artistId] = (lead.proto, 1, album.titleId)
            }
        }
        // Build MMArtistListItem entries from the aggregation.
        var items: [MMArtistListItem] = byArtistId.values.map { entry in
            var item = MMArtistListItem()
            item.id = entry.proto.id
            item.name = entry.proto.name
            item.artistType = entry.proto.artistType
            item.ownedAlbumCount = Int32(entry.count)
            item.hasHeadshot_p = entry.proto.hasHeadshot_p
            item.fallbackAlbumTitleID = entry.fallbackTitle
            return item
        }
        // Optional query filter — case-insensitive substring on name.
        if let q = query?.trimmingCharacters(in: .whitespaces), !q.isEmpty {
            let needle = q.lowercased()
            items = items.filter { $0.name.lowercased().contains(needle) }
        }
        // Sort. ArtistSort cases vary; default to name.
        items.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

        offlineLogger.info("offline artists: cached=\(cached.count) withArtists=\(withArtists) withoutArtists=\(withoutArtists) → \(items.count) items")

        var proto = MMArtistListResponse()
        proto.artists = items
        return ApiArtistListResponse(proto: proto)
    }

    func artistDetail(id: ArtistID) async throws -> ApiArtistDetail {
        let artistId = id.protoValue
        var proto = MMArtistDetail()
        var owned: [MMTitle] = []
        for (_, detail, apiAlbum) in cachedAudioAlbums()
            where apiAlbum.albumArtists.contains(where: { $0.id.protoValue == artistId })
        {
            owned.append(detail.proto.title)
            // Pick up the artist proto from the first matching album
            // so the returned ApiArtistDetail.artist has the right
            // name / mbid / type. We overwrite each pass so the last
            // one wins; values should be identical across albums for
            // the same artist.
            if let lead = apiAlbum.albumArtists.first(where: { $0.id.protoValue == artistId }) {
                proto.artist = lead.proto
            }
        }
        if owned.isEmpty {
            // Artist has no downloaded albums — surface offline error
            // rather than an empty detail page.
            throw DataModelError.offline
        }
        proto.ownedAlbums = owned
        // otherWorks (unowned discography) requires the server; leave
        // empty so ArtistDetailView renders just the owned section.
        return ApiArtistDetail(proto: proto)
    }
    func libraryShuffle(limit: Int) async throws -> [ApiTrack] {
        // Pull every track from every downloaded album, shuffle, take
        // the first `limit`. AppServices.shared.audioCache holds the
        // index; we walk its `downloads` list and read each album's
        // cached MMTitleDetail proto for the track list. Tracks that
        // didn't make it onto disk during a partial download (album
        // entry exists but the track id isn't in trackIds) are
        // skipped — playing them offline would just stall.
        //
        // Each track's MMTrack proto is enriched with title_name +
        // title_artist_name so the mini-player and Now Playing
        // surfaces show correct album / artist context. The server
        // populates those fields when surfacing tracks standalone
        // (library shuffle online); we mirror that for the offline
        // path so the user-visible chrome is identical either way.
        guard let audioCache = AppServices.shared.audioCache else {
            throw DataModelError.offline
        }
        var pool: [ApiTrack] = []
        for album in audioCache.downloads {
            guard let detail = audioCache.cachedAlbumDetail(titleId: album.titleId),
                  let apiAlbum = detail.album else { continue }
            let onDisk = Set(album.trackIds)
            let albumName = detail.name
            let albumArtist = apiAlbum.albumArtists.first?.name ?? ""
            for track in apiAlbum.tracks where onDisk.contains(track.id) {
                var enrichedProto = track.proto
                enrichedProto.titleName = albumName
                if !albumArtist.isEmpty {
                    enrichedProto.titleArtistName = albumArtist
                }
                pool.append(ApiTrack(proto: enrichedProto))
            }
        }
        offlineLogger.info("offline libraryShuffle: pool=\(pool.count) audioCache.downloads=\(AppServices.shared.audioCache?.downloads.count ?? -1)")
        if pool.isEmpty { throw DataModelError.offline }
        return Array(pool.shuffled().prefix(limit))
    }
    func smartPlaylists() async throws -> [ApiSmartPlaylistSummary] {
        // Server-curated; no offline equivalent. Returning an empty
        // list lets the Music landing page hide its Smart Playlists
        // section gracefully (per the existing "if !empty" guard)
        // instead of throwing and breaking the whole load.
        return []
    }
    func smartPlaylist(key: String) async throws -> ApiSmartPlaylistDetail {
        throw DataModelError.offline
    }
    func dismissHomeCarouselItem(titleId: TitleID, carousel: HomeCarousel) async throws {
        throw DataModelError.offline
    }
    func startRadio(seedTrackId: Int64? = nil, seedAlbumId: Int64? = nil) async throws -> ApiStartRadioResponse {
        throw DataModelError.offline
    }
    func nextRadioBatch(sessionId: String, history: [MMRadioTrackHistory]) async throws -> [ApiTrack] {
        throw DataModelError.offline
    }
    func stopRadio(sessionId: String) async throws {
        throw DataModelError.offline
    }
    func recommendedArtists(limit: Int = 30) async throws -> [ApiRecommendedArtist] {
        throw DataModelError.offline
    }
    func searchMusicOnly(query: String) async throws -> ApiSearchResponse {
        throw DataModelError.offline
    }
    func dismissRecommendation(mbid: String) async throws {
        throw DataModelError.offline
    }
    func advancedSearchPresets() async throws -> [ApiAdvancedSearchPreset] {
        throw DataModelError.offline
    }
    func searchTracks(filters: AdvancedTrackSearchFilters) async throws -> [ApiTrackSearchHit] {
        throw DataModelError.offline
    }
    func playlists(scope: PlaylistScope) async throws -> [ApiPlaylistSummary] {
        // Return only downloaded playlists; the scope filter (mine
        // vs all) is moot offline — every cached playlist is one
        // the user explicitly chose to download.
        return cachedAudioPlaylists().map { $0.1.summary }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
    func playlist(id: Int64) async throws -> ApiPlaylistDetail {
        guard let audioCache = AppServices.shared.audioCache,
              let detail = audioCache.cachedPlaylistDetail(playlistId: id) else {
            throw DataModelError.offline
        }
        return detail
    }
    func createPlaylist(name: String, description: String?) async throws -> ApiPlaylistSummary {
        throw DataModelError.offline
    }
    func renamePlaylist(id: Int64, name: String, description: String?) async throws {
        throw DataModelError.offline
    }
    func deletePlaylist(id: Int64) async throws { throw DataModelError.offline }
    func addTracksToPlaylist(id: Int64, trackIds: [Int64]) async throws {
        throw DataModelError.offline
    }
    func removeTrackFromPlaylist(id: Int64, playlistTrackId: Int64) async throws {
        throw DataModelError.offline
    }
    func reorderPlaylist(id: Int64, playlistTrackIdsInOrder: [Int64]) async throws {
        throw DataModelError.offline
    }
    func setPlaylistHero(id: Int64, trackId: Int64?) async throws {
        throw DataModelError.offline
    }
    func setPlaylistPrivacy(id: Int64, isPrivate: Bool) async throws {
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

    // Reading progress (offline: the reader doesn't run offline yet —
    // book download cache lands in a later phase, until then both
    // calls are no-ops). Returning nil mirrors the no-progress case
    // so the reader still opens at the start of the book.
    func readingProgress(mediaItemId: Int64) async -> ApiReadingProgress? { nil }
    func reportReadingProgress(mediaItemId: Int64, locator: String, fraction: Double?) async { }

    // MARK: - WishListDataModel

    func wishList() async throws -> ApiWishListResponse { throw DataModelError.offline }
    func transcodeWishList() async throws -> ApiTranscodeWishListResponse { throw DataModelError.offline }
    func addWish(tmdbId: TmdbID, mediaType: MediaType, title: String, year: Int?,
                 seasonNumber: Int?) async throws { throw DataModelError.offline }
    func addBookWish(olWorkId: String, title: String, author: String?) async throws {
        throw DataModelError.offline
    }
    func removeBookWish(olWorkId: String) async throws { throw DataModelError.offline }
    func addAlbumWish(releaseGroupId: String, title: String, primaryArtist: String?) async throws {
        throw DataModelError.offline
    }
    func removeAlbumWish(releaseGroupId: String) async throws { throw DataModelError.offline }
    func wishlistSeriesGaps(seriesId: BookSeriesID) async throws -> (added: Int, alreadyWished: Int) {
        throw DataModelError.offline
    }
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
    func searchMusicBrainz(query: String, barcode: String?) async throws -> MMSearchMusicBrainzResponse { throw DataModelError.offline }
    func assignMusicBrainzRelease(titleId: Int64, releaseMbid: String) async throws { throw DataModelError.offline }
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
    func setAuthorHidden(id: AuthorID, hidden: Bool) async throws { throw DataModelError.offline }
}
