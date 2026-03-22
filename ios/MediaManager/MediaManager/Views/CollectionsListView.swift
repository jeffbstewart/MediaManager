import SwiftUI

struct CollectionRoute: Hashable {
    let tmdbCollectionId: TmdbCollectionID
    let name: String
}

struct CollectionsListView: View {
    @Environment(OnlineDataModel.self) private var dataModel
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
                            AuthenticatedImage(path: collection.posterUrl, apiClient: dataModel.apiClient)
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
        let response = try? await dataModel.collections()
        collections = response?.collections ?? []
        loading = false
    }
}
