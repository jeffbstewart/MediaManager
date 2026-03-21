import SwiftUI

struct GenreRoute: Hashable {
    let id: Int
    let name: String
}

struct ApiGenreDetail: Codable {
    let name: String
    let titles: [ApiTitle]
}

struct GenreDetailView: View {
    @Environment(AuthManager.self) private var authManager
    let route: GenreRoute
    @State private var detail: ApiGenreDetail?
    @State private var loading = true

    private let columns = [
        GridItem(.adaptive(minimum: 110), spacing: 12)
    ]

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(detail.titles) { title in
                            NavigationLink(value: title) {
                                PosterCard(title: title, apiClient: authManager.apiClient)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding()
                }
            } else {
                ContentUnavailableView("Genre not found", systemImage: "music.note.list")
            }
        }
        .navigationTitle(route.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadDetail()
        }
    }

    private func loadDetail() async {
        loading = true
        detail = try? await authManager.apiClient.get("catalog/genres/\(route.id)")
        loading = false
    }
}
