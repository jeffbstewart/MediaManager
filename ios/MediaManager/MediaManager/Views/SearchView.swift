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
                    switch result.resultType {
                    case "movie", "series":
                        if let titleId = result.titleId {
                            NavigationLink(value: ApiTitle(
                                id: titleId, name: result.name,
                                mediaType: result.mediaType ?? .movie,
                                year: result.year, description: nil,
                                posterUrl: result.posterUrl,
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
                    case "actor":
                        if let personId = result.tmdbPersonId {
                            NavigationLink(value: ActorRoute(tmdbPersonId: personId, name: result.name)) {
                                SearchResultRow(result: result, apiClient: dataModel.apiClient)
                            }
                        } else {
                            SearchResultRow(result: result, apiClient: dataModel.apiClient)
                        }
                    case "collection":
                        if let collId = result.tmdbCollectionId {
                            NavigationLink(value: CollectionRoute(tmdbCollectionId: collId, name: result.name)) {
                                SearchResultRow(result: result, apiClient: dataModel.apiClient)
                            }
                        } else {
                            SearchResultRow(result: result, apiClient: dataModel.apiClient)
                        }
                    case "tag":
                        if let tagId = result.itemId {
                            NavigationLink(value: TagRoute(id: TagID(rawValue: tagId), name: result.name)) {
                                SearchResultRow(result: result, apiClient: dataModel.apiClient)
                            }
                        } else {
                            SearchResultRow(result: result, apiClient: dataModel.apiClient)
                        }
                    case "genre":
                        if let genreId = result.itemId {
                            NavigationLink(value: GenreRoute(id: GenreID(rawValue: genreId), name: result.name)) {
                                SearchResultRow(result: result, apiClient: dataModel.apiClient)
                            }
                        } else {
                            SearchResultRow(result: result, apiClient: dataModel.apiClient)
                        }
                    default:
                        SearchResultRow(result: result, apiClient: dataModel.apiClient)
                    }
                }
            }
        }
        .navigationTitle("Search")
        .searchable(text: $query, prompt: "Movies, TV, actors...")
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

    private static let typeOrder: [String: Int] = [
        "movie": 0, "series": 0, "actor": 1,
        "collection": 2, "tag": 3, "genre": 3
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
            if result.resultType == "actor" {
                AuthenticatedImage(path: result.headshotUrl, apiClient: apiClient, cornerRadius: 20)
                    .frame(width: 40, height: 40)
            } else {
                AuthenticatedImage(path: result.posterUrl, apiClient: apiClient)
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
