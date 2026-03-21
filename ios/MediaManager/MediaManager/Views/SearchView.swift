import SwiftUI

struct SearchView: View {
    @Environment(AuthManager.self) private var authManager
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
                                mediaType: result.mediaType ?? "MOVIE",
                                year: result.year, description: nil,
                                posterUrl: result.posterUrl,
                                backdropUrl: nil,
                                contentRating: result.contentRating,
                                popularity: nil,
                                quality: result.quality,
                                playable: result.transcodeId != nil,
                                transcodeId: result.transcodeId,
                                tmdbId: nil, tmdbCollectionId: nil,
                                tmdbCollectionName: nil
                            )) {
                                SearchResultRow(result: result, apiClient: authManager.apiClient)
                            }
                        }
                    case "actor":
                        if let personId = result.tmdbPersonId {
                            NavigationLink(value: ActorRoute(tmdbPersonId: personId, name: result.name)) {
                                SearchResultRow(result: result, apiClient: authManager.apiClient)
                            }
                        } else {
                            SearchResultRow(result: result, apiClient: authManager.apiClient)
                        }
                    default:
                        SearchResultRow(result: result, apiClient: authManager.apiClient)
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

    private func performSearch(_ query: String) async {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else {
            results = []
            hasSearched = false
            return
        }
        searching = true
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        let response: ApiSearchResponse? = try? await authManager.apiClient.get("catalog/search?q=\(encoded)")
        results = response?.results ?? []
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
