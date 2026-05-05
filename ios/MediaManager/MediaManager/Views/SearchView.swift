import SwiftUI

struct SearchView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var query = ""
    @State private var results: [ApiSearchResult] = []
    @State private var searching = false
    @State private var hasSearched = false
    @State private var searchTask: Task<Void, Never>?

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
        .searchable(text: $query, prompt: "Movies, TV, books, actors, authors…")
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

    private static let typeOrder: [String: Int] = [
        "movie": 0, "series": 0, "book": 1, "actor": 2,
        "author": 3, "collection": 4, "tag": 5, "genre": 5
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
    let result: ApiSearchResult
    let apiClient: APIClient

    var body: some View {
        HStack(spacing: 12) {
            if result.resultType == "actor", let personId = result.tmdbPersonId {
                CachedImage(ref: .headshot(tmdbPersonId: personId.protoValue), cornerRadius: 20)
                    .frame(width: 40, height: 40)
            } else if let titleId = result.titleId {
                CachedImage(ref: .posterThumbnail(titleId: titleId.protoValue))
                    .frame(width: 40, height: 60)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(result.name)
                    .fontWeight(.medium)

                HStack(spacing: 6) {
                    Text(resultTypeLabel)
                        .foregroundStyle(.secondary)
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
                }
                .font(.caption)
            }
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
        default: result.resultType
        }
    }
}
