import SwiftUI

struct CollectionRoute: Hashable {
    let tmdbCollectionId: Int
    let name: String
}

struct CollectionsListView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var collections: [ApiCollectionListItem] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if collections.isEmpty {
                ContentUnavailableView("No collections", systemImage: "square.stack")
            } else {
                List(collections) { collection in
                    NavigationLink(value: CollectionRoute(tmdbCollectionId: collection.tmdbCollectionId, name: collection.name)) {
                        HStack(spacing: 12) {
                            AuthenticatedImage(path: collection.posterUrl, apiClient: authManager.apiClient)
                                .frame(width: 50, height: 75)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(collection.name)
                                    .fontWeight(.medium)
                                Text("\(collection.titleCount) titles")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
        }
        .navigationTitle("Collections")
        .task {
            await loadCollections()
        }
        .refreshable {
            await loadCollections()
        }
    }

    private func loadCollections() async {
        loading = collections.isEmpty
        let response: ApiCollectionListResponse? = try? await authManager.apiClient.get("catalog/collections")
        collections = response?.collections ?? []
        loading = false
    }
}
