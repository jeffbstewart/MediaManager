import SwiftUI

struct SearchView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var query = ""
    @State private var results: [ApiSearchResult] = []
    @State private var searching = false
    @State private var hasSearched = false
    @State private var searchTask: Task<Void, Never>?
    /// Drives the advanced-search modal. Bool rather than item-bound
    /// so the sheet doesn't re-mount across submits.
    @State private var showAdvanced = false
    /// Set when the advanced-search submit closes — pushes the
    /// TrackSearchResultsView destination once we land on the next
    /// run loop turn (avoids fighting the sheet dismiss animation).
    @State private var pendingTrackSearch: AdvancedTrackSearchFilters? = nil

    var body: some View {
        List {
            if searching {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            } else if results.isEmpty && hasSearched {
                ContentUnavailableView.search(text: query)
            } else {
                ForEach(results) { result in
                    resultRow(result)
                }
            }
        }
        .navigationTitle("Search")
        .searchable(text: $query, prompt: "Movies, TV, books, music, actors…")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showAdvanced = true
                } label: {
                    Image(systemName: "slider.horizontal.3")
                }
                .accessibilityLabel("Advanced search")
            }
        }
        .sheet(isPresented: $showAdvanced) {
            AdvancedSearchSheet { filters in
                // Stash on state — the .onChange below promotes it to
                // a NavigationLink push once SwiftUI is back on the
                // base run loop.
                pendingTrackSearch = filters
            }
        }
        .navigationDestination(item: $pendingTrackSearch) { filters in
            TrackSearchResultsView(filters: filters)
        }
        .onChange(of: query) { _, newValue in
            searchTask?.cancel()
            hasSearched = false
            searching = !newValue.trimmingCharacters(in: .whitespaces).isEmpty
            searchTask = Task {
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }
                await performSearch(newValue)
            }
        }
    }

    /// Per-result row dispatch. Pulled out of the ForEach body
    /// because SwiftUI's type-checker chokes on a single switch with
    /// this many NavigationLink-bearing branches and emits misleading
    /// "cannot assign to property" errors. Splitting each result kind
    /// into its own helper keeps the per-closure body simple enough.
    @ViewBuilder
    private func resultRow(_ result: ApiSearchResult) -> some View {
        switch result.resultType {
        case "movie", "series": titleResultLink(result)
        case "actor":           actorResultLink(result)
        case "collection":      collectionResultLink(result)
        case "book":            bookResultLink(result)
        case "author":          authorResultLink(result)
        case "tag":             tagResultLink(result)
        case "genre":           genreResultLink(result)
        case "album":           albumResultLink(result)
        case "artist":          artistResultLink(result)
        case "track":           trackResultLink(result)
        default:                SearchResultRow(result: result, apiClient: dataModel.apiClient)
        }
    }

    @ViewBuilder
    private func titleResultLink(_ result: ApiSearchResult) -> some View {
        if let titleId = result.titleId {
            NavigationLink(value: ApiTitle(
                id: titleId, name: result.name,
                mediaType: result.mediaType ?? .movie,
                year: result.year, description: nil,
                backdropUrl: nil,
                contentRating: result.contentRating,
                popularity: nil,
                quality: result.quality,
                playable: result.transcodeId != nil,
                transcodeId: result.transcodeId,
                tmdbId: nil, tmdbCollectionId: nil,
                tmdbCollectionName: nil, familyMembers: nil,
                forMobileAvailable: nil
            )) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        }
    }

    @ViewBuilder
    private func actorResultLink(_ result: ApiSearchResult) -> some View {
        if let personId = result.tmdbPersonId {
            NavigationLink(value: ActorRoute(tmdbPersonId: personId, name: result.name)) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        } else {
            SearchResultRow(result: result, apiClient: dataModel.apiClient)
        }
    }

    @ViewBuilder
    private func collectionResultLink(_ result: ApiSearchResult) -> some View {
        if let collId = result.tmdbCollectionId {
            NavigationLink(value: CollectionRoute(tmdbCollectionId: collId, name: result.name)) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        } else {
            SearchResultRow(result: result, apiClient: dataModel.apiClient)
        }
    }

    /// Books route via the ApiTitle navigation surface; ContentView
    /// dispatches `isBook` titles to BookDetailView. The Swift-side
    /// `MediaType` enum doesn't model BOOK, so the `ApiTitle`
    /// convenience initializer would clamp the proto mediaType to
    /// PERSONAL and BookDetailView would never be selected. We
    /// build the MMTitle proto directly with the BOOK media type
    /// preserved (assignments inside @ViewBuilder bodies aren't
    /// allowed, hence the helper).
    @ViewBuilder
    private func bookResultLink(_ result: ApiSearchResult) -> some View {
        if let titleId = result.titleId {
            NavigationLink(value: ApiTitle(proto: makeBookTitleProto(titleId: titleId, result: result))) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        }
    }

    private func makeBookTitleProto(titleId: TitleID, result: ApiSearchResult) -> MMTitle {
        var proto = MMTitle()
        proto.id = titleId.protoValue
        proto.name = result.name
        proto.mediaType = .book
        if let y = result.year { proto.year = Int32(y) }
        return proto
    }

    @ViewBuilder
    private func authorResultLink(_ result: ApiSearchResult) -> some View {
        if let authorId = result.authorId {
            NavigationLink(value: AuthorRoute(id: authorId, name: result.name)) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        } else {
            SearchResultRow(result: result, apiClient: dataModel.apiClient)
        }
    }

    @ViewBuilder
    private func tagResultLink(_ result: ApiSearchResult) -> some View {
        if let tagId = result.itemId {
            NavigationLink(value: TagRoute(id: TagID(rawValue: tagId), name: result.name)) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        } else {
            SearchResultRow(result: result, apiClient: dataModel.apiClient)
        }
    }

    @ViewBuilder
    private func genreResultLink(_ result: ApiSearchResult) -> some View {
        if let genreId = result.itemId {
            NavigationLink(value: GenreRoute(id: GenreID(rawValue: genreId), name: result.name)) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        } else {
            SearchResultRow(result: result, apiClient: dataModel.apiClient)
        }
    }

    /// ALBUM hits route through the ApiTitle nav surface so
    /// ContentView's destination handler can dispatch isAlbum titles
    /// to AlbumDetailView. Built directly off the proto with the
    /// MUSIC media type preserved (the Swift MediaType clamp
    /// strategy used for Books — see bookResultLink).
    @ViewBuilder
    private func albumResultLink(_ result: ApiSearchResult) -> some View {
        if let titleId = result.titleId {
            NavigationLink(value: ApiTitle(proto: makeAlbumTitleProto(titleId: titleId, result: result))) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        }
    }

    private func makeAlbumTitleProto(titleId: TitleID, result: ApiSearchResult) -> MMTitle {
        var proto = MMTitle()
        proto.id = titleId.protoValue
        proto.name = result.name
        proto.mediaType = .album
        if let y = result.year { proto.year = Int32(y) }
        return proto
    }

    @ViewBuilder
    private func artistResultLink(_ result: ApiSearchResult) -> some View {
        if let artistId = result.artistId {
            NavigationLink(value: ArtistRoute(id: artistId, name: result.name)) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        } else {
            SearchResultRow(result: result, apiClient: dataModel.apiClient)
        }
    }

    /// TRACK hits navigate to the parent album, matching the Apple
    /// Music behaviour of "tap a song in search → land on its album".
    /// The track-id is preserved on the row for future surfaces
    /// (e.g. quick-play from a long-press) but isn't acted on yet.
    /// The destination proto carries the album's name (from the
    /// search result's `albumName` context line) so AlbumDetailView's
    /// nav title is right while the album detail is still loading.
    @ViewBuilder
    private func trackResultLink(_ result: ApiSearchResult) -> some View {
        if let albumTitleId = result.albumTitleId {
            NavigationLink(value: ApiTitle(proto: makeTrackParentAlbumProto(
                albumTitleId: albumTitleId,
                result: result))) {
                SearchResultRow(result: result, apiClient: dataModel.apiClient)
            }
        } else {
            SearchResultRow(result: result, apiClient: dataModel.apiClient)
        }
    }

    private func makeTrackParentAlbumProto(albumTitleId: TitleID, result: ApiSearchResult) -> MMTitle {
        var proto = MMTitle()
        proto.id = albumTitleId.protoValue
        proto.name = result.albumName ?? result.name
        proto.mediaType = .album
        return proto
    }

    private static let typeOrder: [String: Int] = [
        "movie": 0, "series": 0, "book": 1,
        "album": 2, "track": 2, "artist": 3,
        "actor": 4, "author": 5, "collection": 6,
        "tag": 7, "genre": 7
    ]

    private func performSearch(_ query: String) async {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else {
            results = []
            hasSearched = false
            return
        }
        searching = true
        let response = try? await dataModel.search(query: query)
        // Reorder: playable titles first, then actors, then collections/tags/genres
        results = (response?.results ?? []).sorted {
            let a = Self.typeOrder[$0.resultType] ?? 9
            let b = Self.typeOrder[$1.resultType] ?? 9
            return a < b
        }
        searching = false
        hasSearched = true
    }
}

struct SearchResultRow: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let result: ApiSearchResult
    let apiClient: APIClient

    /// True when this row points at a video/audio titleId that's
    /// downloaded for offline. Tracks search to the row's albumTitleId
    /// (the parent album is what's actually downloaded). Actor/artist
    /// rows have no titleId — never downloaded.
    private var isDownloaded: Bool {
        let candidate: TitleID? = result.resultType == "track"
            ? result.albumTitleId
            : result.titleId
        guard let id = candidate else { return false }
        return dataModel.downloads.offlineTitleIds.contains(id.protoValue)
    }

    var body: some View {
        HStack(spacing: 12) {
            artwork
            VStack(alignment: .leading, spacing: 2) {
                Text(result.name)
                    .fontWeight(.medium)

                HStack(spacing: 6) {
                    Text(resultTypeLabel)
                        .foregroundStyle(.secondary)
                    if let context = audioContextLabel {
                        Text("·").foregroundStyle(.secondary)
                        Text(context)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    if let year = result.year {
                        Text("(\(String(year)))")
                            .foregroundStyle(.secondary)
                    }
                    if let quality = result.quality {
                        Text(quality)
                            .fontWeight(.semibold)
                            .foregroundStyle(.secondary)
                    }
                    if let count = result.titleCount {
                        Text("\(count) titles")
                            .foregroundStyle(.secondary)
                    }
                    if isDownloaded {
                        // Inline (list-row) variant of the offline
                        // indicator: green icon, no chrome — matches
                        // the per-season indicator in SeasonsView.
                        // The 40px row thumbnail is too small for the
                        // corner-badge style used on poster tiles.
                        Image(systemName: "arrow.down.circle.fill")
                            .foregroundStyle(.green)
                            .accessibilityLabel("Downloaded")
                    }
                }
                .font(.caption)
            }
        }
    }

    /// Left-side thumbnail. Audio types render square (album-art
    /// aspect) so albums and tracks don't get stretched into the
    /// 40×60 poster aspect we use for video.
    @ViewBuilder
    private var artwork: some View {
        switch result.resultType {
        case "actor":
            if let personId = result.tmdbPersonId {
                CachedImage(ref: .headshot(tmdbPersonId: personId.protoValue), cornerRadius: 20)
                    .frame(width: 40, height: 40)
            }
        case "artist":
            if let artistId = result.artistId {
                CachedImage(ref: .artistHeadshot(artistId: artistId.protoValue), cornerRadius: 20)
                    .frame(width: 40, height: 40)
            }
        case "album":
            if let titleId = result.titleId {
                CachedImage(ref: .posterThumbnail(titleId: titleId.protoValue), cornerRadius: 4)
                    .frame(width: 40, height: 40)  // square
            }
        case "track":
            if let albumTitleId = result.albumTitleId {
                CachedImage(ref: .posterThumbnail(titleId: albumTitleId.protoValue), cornerRadius: 4)
                    .frame(width: 40, height: 40)  // square
            }
        default:
            if let titleId = result.titleId {
                CachedImage(ref: .posterThumbnail(titleId: titleId.protoValue))
                    .frame(width: 40, height: 60)
            }
        }
    }

    /// Tucked next to the type label: "Album · Taylor Swift" for an
    /// album hit, "Song · Folklore" for a track hit. Returns nil for
    /// non-audio rows so existing layout stays intact.
    private var audioContextLabel: String? {
        switch result.resultType {
        case "album":
            return result.artistName?.nonEmptyOrNil
        case "track":
            // Album name is the most useful context; fall back to
            // artist name when the server didn't ship the album.
            return result.albumName?.nonEmptyOrNil
                ?? result.artistName?.nonEmptyOrNil
        default:
            return nil
        }
    }

    private var resultTypeLabel: String {
        switch result.resultType {
        case "movie": "Movie"
        case "series": "TV Series"
        case "actor": "Actor"
        case "collection": "Collection"
        case "tag": "Tag"
        case "genre": "Genre"
        case "album": "Album"
        case "artist": "Artist"
        case "track": "Song"
        case "book": "Book"
        case "author": "Author"
        default: result.resultType
        }
    }
}

private extension String {
    var nonEmptyOrNil: String? { isEmpty ? nil : self }
}
