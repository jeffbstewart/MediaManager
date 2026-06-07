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
        // Mirror the online home feed against cached content.
        // Three "Recently Downloaded" carousels (video / books /
        // albums) plus a resume-watching list reconstructed from
        // the video cache. Missing-seasons / resume-reading /
        // resume-listening are server-side state we don't replicate
        // offline — those sections render empty.
        var proto = MMHomeFeedResponse()

        // Audio carousel — sort by the existing downloadedAt
        // timestamp on DownloadedAlbum.
        let albums = cachedAudioAlbums()
            .sorted { $0.0.downloadedAt > $1.0.downloadedAt }
        proto.recentlyAddedAlbums = albums.map { $0.1.proto.title }

        // Video carousel — use DownloadManager.entries' sequence
        // (monotonic per download) as a recency proxy, then dedup
        // by titleId so a 20-episode TV show shows up once at the
        // most-recent episode's slot, not 20 times.
        var seenVideoTitles = Set<Int64>()
        var orderedVideoTitles: [Int64] = []
        for entry in downloads.entries.sorted(by: { $0.sequence > $1.sequence }) {
            guard entry.state == .completed else { continue }
            if seenVideoTitles.insert(entry.titleID).inserted {
                orderedVideoTitles.append(entry.titleID)
            }
        }
        // Map back to MMTitle protos via the cached MMTitleDetail.
        let videoByTitleId = Dictionary(uniqueKeysWithValues:
            cachedVideoTitles().map { ($0.id.protoValue, $0.proto.title) })
        proto.recentlyAdded = orderedVideoTitles
            .compactMap { videoByTitleId[$0] }

        // Books carousel — sort by DownloadedBook.downloadedAt.
        if let bookCache = AppServices.shared.bookCache {
            let books = bookCache.downloads.sorted { $0.downloadedAt > $1.downloadedAt }
            var seenBooks = Set<Int64>()
            proto.recentlyAddedBooks = books.compactMap { book -> MMTitle? in
                guard seenBooks.insert(book.titleId).inserted else { return nil }
                return bookCache.loadCachedTitleDetail(titleId: book.titleId)?.proto.title
            }

            // Continue Reading — LRU of downloaded books the user has
            // actually opened. Sourced from LocalProgressStore (per
            // mediaItem reader position); intersected with the
            // downloaded-books index so a stale reading entry for a
            // book that was since deleted doesn't appear. Recently
            // Downloaded sits beside this for fresh downloads that
            // haven't been opened yet — the two are complementary,
            // not duplicates. (#71)
            let downloadedByMediaItem = Dictionary(
                uniqueKeysWithValues: bookCache.downloads.map { ($0.mediaItemId, $0) })
            let readingByRecency = await LocalProgressStore.shared.readingEntriesByRecency()
            proto.resumeReading = readingByRecency.compactMap { entry -> MMResumeReading? in
                guard let book = downloadedByMediaItem[entry.mediaItemId] else { return nil }
                let fraction = entry.fraction ?? 0
                guard fraction > 0 || !entry.locator.isEmpty else { return nil }
                var r = MMResumeReading()
                r.mediaItemID = entry.mediaItemId
                r.titleID = book.titleId
                r.titleName = book.titleName
                r.percent = fraction
                r.updatedAt = MMTimestamp.with {
                    $0.secondsSinceEpoch = Int64(entry.updatedAt.timeIntervalSince1970)
                }
                return r
            }
        }

        // HomeView renders from `feed.carousels` (the generic
        // heterogeneous list). The online server populates BOTH
        // typed fields above and a parallel carousels list — the
        // offline path used to only fill the typed fields, so
        // HomeView's `!feed.carousels.isEmpty` gate failed and the
        // empty state painted even with content available. Build
        // matching MMCarousel entries here so the view's existing
        // rendering branch fires.
        var carousels: [MMCarousel] = []
        func addCarousel(_ name: String, items: [MMTitle]) {
            guard !items.isEmpty else { return }
            var c = MMCarousel()
            c.name = name
            c.items = items
            carousels.append(c)
        }
        addCarousel("Recently Downloaded", items: proto.recentlyAdded)
        addCarousel("Recently Downloaded Books", items: proto.recentlyAddedBooks)
        addCarousel("Recently Downloaded Music", items: proto.recentlyAddedAlbums)
        proto.carousels = carousels

        return ApiHomeFeed(proto: proto)
    }

    // MARK: - Offline catalog helpers
    //
    // Walk each cache once and return parsed ApiTitleDetail per
    // downloaded video/book title. Used by the offline catalog
    // (`titles(type:)`) and the cross-reference detail methods
    // below (collectionDetail, actorDetail, etc.). Each call
    // re-walks the disk — cheap relative to the downloads count
    // a single device carries.

    private func cachedVideoTitles() -> [ApiTitleDetail] {
        // One detail per unique titleID across the download entries.
        // Dedup because TV shows have many entries (one per episode)
        // all pointing at the same parent MMTitleDetail.
        var seen = Set<Int64>()
        var out: [ApiTitleDetail] = []
        for entry in downloads.entries {
            guard !seen.contains(entry.titleID) else { continue }
            guard let detail = downloads.loadCachedTitleDetail(for: TitleID(rawValue: Int(entry.titleID)))
            else { continue }
            seen.insert(entry.titleID)
            out.append(detail)
        }
        return out
    }

    private func cachedBookTitles() -> [ApiTitleDetail] {
        guard let bookCache = AppServices.shared.bookCache else { return [] }
        var seen = Set<Int64>()
        var out: [ApiTitleDetail] = []
        for book in bookCache.downloads {
            guard !seen.contains(book.titleId) else { continue }
            guard let detail = bookCache.loadCachedTitleDetail(titleId: book.titleId) else { continue }
            seen.insert(book.titleId)
            out.append(detail)
        }
        return out
    }

    func titles(type: MediaType, page: Int, sort: String?, query: String?) async throws -> ApiTitlePage {
        // Collect cached video titles for the requested media type
        // (movie / tv / personal). Books aren't reached via this API
        // — Books tab navigates through AuthorsView which has its
        // own offline path. Pagination is meaningless offline (small
        // dataset), so we return everything on page 1.
        var candidates = cachedVideoTitles().filter { $0.mediaType == type }
        if let q = query?.trimmingCharacters(in: .whitespaces), !q.isEmpty {
            candidates = candidates.filter { $0.name.localizedCaseInsensitiveContains(q) }
        }
        var response = MMTitlePageResponse()
        response.titles = candidates.map { $0.proto.title }
        var pagination = MMPaginationInfo()
        pagination.page = 1
        pagination.limit = Int32(response.titles.count)
        pagination.total = Int32(response.titles.count)
        pagination.totalPages = 1
        response.pagination = pagination
        return ApiTitlePage(proto: response)
    }

    func titleDetail(id: TitleID) async throws -> ApiTitleDetail {
        if let videoDetail = downloads.loadCachedTitleDetail(for: id) {
            return videoDetail
        }
        if let bookCache = AppServices.shared.bookCache,
           let bookDetail = bookCache.loadCachedTitleDetail(titleId: id.protoValue) {
            return bookDetail
        }
        throw DataModelError.offline
    }

    func seasons(titleId: TitleID) async throws -> [ApiSeason] {
        // Drive the visible list off completed downloads (the source
        // of truth) and use cached MMSeason protos as ENRICHMENT
        // when available. This makes the offline list resilient to
        // gaps in the season-metadata cache — every season with at
        // least one downloaded episode shows up, even if its
        // MMSeason wasn't persisted at navigation time (e.g.
        // downloaded individually from a pre-Phase-1 build).
        //
        // Season 0 (Specials) is included — the older
        // `seasonNumber > 0` filter was a holdover from the original
        // synthesizing logic and incorrectly hid Star Trek-style
        // bonus content.
        let cached = downloads.loadCachedSeasons(for: titleId)
        let cachedByNumber = Dictionary(uniqueKeysWithValues:
            cached.map { ($0.seasonNumber, $0) })

        var entriesBySeason: [Int: Int] = [:]
        for entry in downloads.entries
            where entry.titleID == titleId.protoValue && entry.state == .completed {
            entriesBySeason[Int(entry.seasonNumber), default: 0] += 1
        }
        if entriesBySeason.isEmpty { throw DataModelError.offline }

        return entriesBySeason.keys.sorted().map { num in
            if let real = cachedByNumber[num] {
                return real
            }
            var proto = MMSeason()
            proto.seasonNumber = Int32(num)
            proto.name = num == 0 ? "Specials" : "Season \(num)"
            proto.episodeCount = Int32(entriesBySeason[num] ?? 0)
            return ApiSeason(proto: proto)
        }
    }

    func episodes(titleId: TitleID, season: Int) async throws -> [ApiEpisode] {
        // Same pattern as seasons(): drive the visible list off
        // completed downloads, use cached MMEpisode protos as
        // enrichment. An episode without a cached MMEpisode still
        // surfaces, just with a minimal (synthesized) proto pulled
        // from MMDownloadEntry — same shape as before Phase 1.
        let cached = downloads.loadCachedEpisodes(for: titleId, season: season)
        let cachedByTranscode: [Int64: ApiEpisode] = Dictionary(uniqueKeysWithValues:
            cached.compactMap { ep -> (Int64, ApiEpisode)? in
                guard let tcId = ep.transcodeId else { return nil }
                return (tcId.protoValue, ep)
            })

        let entries = downloads.entries
            .filter {
                $0.titleID == titleId.protoValue
                && $0.state == .completed
                && Int($0.seasonNumber) == season
            }
            .sorted { $0.episodeNumber < $1.episodeNumber }
        if entries.isEmpty { throw DataModelError.offline }

        return entries.map { entry in
            if let real = cachedByTranscode[entry.transcodeID] {
                return real
            }
            var proto = MMEpisode()
            proto.episodeID = entry.transcodeID
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
        // Walk every cached video title, collect ones that include
        // this actor, peel a character name from each. The actor's
        // bio + headshot URL aren't persisted (only present on the
        // server's actor profile) — leave those fields empty; the
        // view falls back to a placeholder hero.
        let pid = id.protoValue
        var owned: [(MMTitle, String?)] = []
        var actorName: String?
        for detail in cachedVideoTitles() {
            guard let member = detail.cast.first(where: { $0.tmdbPersonId.protoValue == pid }) else { continue }
            owned.append((detail.proto.title, member.characterName))
            if actorName == nil { actorName = member.name }
        }
        guard !owned.isEmpty, let name = actorName else { throw DataModelError.offline }
        var proto = MMActorDetail()
        proto.name = name
        proto.ownedTitles = owned.map { (title, character) in
            var credit = MMOwnedCredit()
            credit.title = title
            if let c = character { credit.characterName = c }
            return credit
        }
        return ApiActorDetail(proto: proto)
    }

    func collections() async throws -> ApiCollectionListResponse {
        throw DataModelError.offline
    }

    func collectionDetail(id: TmdbCollectionID) async throws -> ApiCollectionDetail {
        // Walk cached movie titles, find ones with this
        // tmdbCollectionId, and build a CollectionDetail. The
        // collection's "name" comes from any matching title's
        // tmdbCollectionName.
        let cid = id.protoValue
        let matching = cachedVideoTitles().filter { $0.tmdbCollectionId?.protoValue == cid }
        guard !matching.isEmpty else { throw DataModelError.offline }

        var proto = MMCollectionDetail()
        proto.name = matching.first?.tmdbCollectionName ?? "Collection"
        proto.items = matching.map { detail in
            var item = MMCollectionItem()
            item.tmdbMovieID = detail.tmdbId?.protoValue ?? 0
            item.name = detail.name
            if let year = detail.year { item.year = Int32(year) }
            item.owned = true        // by construction — cached means owned
            item.playable = detail.playable
            item.titleID = detail.id.protoValue
            if let tcId = detail.transcodeId { item.transcodeID = tcId.protoValue }
            // quality / contentRating live as enum protos — leave
            // unset; offline UI doesn't strongly depend on them and
            // populating would need a string->enum reverse lookup.
            return item
        }
        return ApiCollectionDetail(proto: proto)
    }

    func tags() async throws -> ApiTagListResponse {
        throw DataModelError.offline
    }

    func tagDetail(id: TagID) async throws -> ApiTagDetail {
        let tid = id.protoValue
        var name: String?
        var color: MMColor = MMColor()
        var titles: [MMTitle] = []
        for detail in cachedVideoTitles() {
            guard let tag = detail.tags.first(where: { $0.id.protoValue == tid }) else { continue }
            titles.append(detail.proto.title)
            if name == nil { name = tag.name; color = tag.proto.color }
        }
        guard let n = name else { throw DataModelError.offline }
        var proto = MMTagDetail()
        proto.name = n
        proto.color = color
        proto.titles = titles
        return ApiTagDetail(proto: proto)
    }

    func genreDetail(id: GenreID) async throws -> ApiGenreDetail {
        let gid = id.protoValue
        var name: String?
        var titles: [MMTitle] = []
        for detail in cachedVideoTitles() {
            guard let g = detail.genres.first(where: { $0.id.protoValue == gid }) else { continue }
            titles.append(detail.proto.title)
            if name == nil { name = g.name }
        }
        guard let n = name else { throw DataModelError.offline }
        var proto = MMGenreDetail()
        proto.name = n
        proto.titles = titles
        return ApiGenreDetail(proto: proto)
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

    // MARK: - Books offline (uses cached book detail.pb)

    func authors(page: Int, sort: AuthorSort, query: String?, hiddenOnly: Bool) async throws -> ApiAuthorListResponse {
        // Aggregate downloaded books by their lead author.
        // Mirrors `artists(...)` below structurally.
        var byAuthorId: [Int64: (proto: MMAuthor, count: Int, fallbackTitleId: Int64)] = [:]
        for detail in cachedBookTitles() {
            guard let book = detail.book else { continue }
            for author in book.authors {
                let aid = author.id.protoValue
                if var existing = byAuthorId[aid] {
                    existing.count += 1
                    byAuthorId[aid] = existing
                } else {
                    byAuthorId[aid] = (author.proto, 1, detail.id.protoValue)
                }
            }
        }
        var items: [MMAuthorListItem] = byAuthorId.map { _, value in
            var item = MMAuthorListItem()
            item.id = value.proto.id
            item.name = value.proto.name
            item.ownedBookCount = Int32(value.count)
            item.hasHeadshot_p = value.proto.hasHeadshot_p
            item.fallbackBookTitleID = value.fallbackTitleId
            return item
        }
        if let q = query, !q.isEmpty {
            items = items.filter { $0.name.localizedCaseInsensitiveContains(q) }
        }
        switch sort {
        case .name: items.sort { $0.name.localizedCompare($1.name) == .orderedAscending }
        case .books: items.sort { $0.ownedBookCount > $1.ownedBookCount }
        case .recent:
            // No persisted download-timestamp at the author level —
            // fall back to alphabetical so the order is at least stable.
            items.sort { $0.name.localizedCompare($1.name) == .orderedAscending }
        }

        var response = MMAuthorListResponse()
        response.authors = items
        var p = MMPaginationInfo()
        p.page = 1; p.limit = Int32(items.count); p.total = Int32(items.count); p.totalPages = 1
        response.pagination = p
        return ApiAuthorListResponse(proto: response)
    }

    func authorDetail(id: AuthorID) async throws -> ApiAuthorDetail {
        let aid = id.protoValue
        var author: MMAuthor?
        var ownedBooks: [MMTitle] = []
        for detail in cachedBookTitles() {
            guard let book = detail.book else { continue }
            guard let match = book.authors.first(where: { $0.id.protoValue == aid }) else { continue }
            if author == nil { author = match.proto }
            ownedBooks.append(detail.proto.title)
        }
        guard let resolvedAuthor = author else { throw DataModelError.offline }
        var proto = MMAuthorDetail()
        proto.author = resolvedAuthor
        proto.ownedBooks = ownedBooks
        // otherWorks (OpenLibrary bibliography) is server-side only.
        return ApiAuthorDetail(proto: proto)
    }

    func bookSeriesDetail(id: BookSeriesID) async throws -> ApiBookSeriesDetail {
        let sid = id.protoValue
        var seriesRef: MMBookSeriesRef?
        var volumes: [MMBookSeriesVolume] = []
        for detail in cachedBookTitles() {
            guard let series = detail.book?.bookSeries, series.id.protoValue == sid else { continue }
            if seriesRef == nil { seriesRef = series.proto }
            var v = MMBookSeriesVolume()
            v.titleID = detail.id.protoValue
            v.titleName = detail.name
            if let n = series.number { v.seriesNumber = n }
            if let y = detail.year { v.firstPublicationYear = Int32(y) }
            volumes.append(v)
        }
        guard let ref = seriesRef else { throw DataModelError.offline }
        var proto = MMBookSeriesDetail()
        proto.name = ref.name
        // seriesNumber is a string ("0.5", "3", "12") — sort
        // numerically when both sides parse, otherwise lexicographically.
        proto.volumes = volumes.sorted { lhs, rhs in
            if let l = Double(lhs.seriesNumber), let r = Double(rhs.seriesNumber) {
                return l < r
            }
            return lhs.seriesNumber < rhs.seriesNumber
        }
        // missingVolumes is server-only (OpenLibrary lookup).
        return ApiBookSeriesDetail(proto: proto)
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
        // Local shadow is the only source offline. Populated by
        // OnlineDataModel.reportProgress on every report — including
        // online sessions — so a movie watched online yesterday
        // resumes at the right spot offline today.
        await LocalProgressStore.shared.apiPlaybackProgress(transcodeId: transcodeId.protoValue)
    }

    func reportProgress(transcodeId: TranscodeID, position: Double, duration: Double?) async {
        await LocalProgressStore.shared.recordPlayback(
            transcodeId: transcodeId.protoValue, position: position, duration: duration)
        // Queue for server sync when connectivity returns.
        downloads.queueProgressUpdate(transcodeId: transcodeId.protoValue, position: position, duration: duration)
    }

    func readingProgress(mediaItemId: Int64) async -> ApiReadingProgress? {
        await LocalProgressStore.shared.apiReadingProgress(mediaItemId: mediaItemId)
    }
    func reportReadingProgress(mediaItemId: Int64, locator: String, fraction: Double?) async {
        await LocalProgressStore.shared.recordReading(
            mediaItemId: mediaItemId, locator: locator, fraction: fraction)
        // Reading progress doesn't share DownloadManager's queue
        // (the schema differs — locator vs. seconds). The local
        // entry stays put; when the user goes back online,
        // OnlineDataModel.reportReadingProgress will push fresh
        // updates from the reader once it resumes.
    }

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
