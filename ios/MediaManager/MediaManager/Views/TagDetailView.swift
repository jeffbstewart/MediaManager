import SwiftUI

struct TagDetailView: View {
    @Environment(AuthManager.self) private var authManager
    let route: TagRoute
    @State private var detail: ApiTagDetail?
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
                ContentUnavailableView("Tag not found", systemImage: "tag")
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
        detail = try? await authManager.apiClient.get("catalog/tags/\(route.id)")
        loading = false
    }
}
