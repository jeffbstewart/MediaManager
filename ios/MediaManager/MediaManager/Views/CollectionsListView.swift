import SwiftUI
import MediaManagerCore
import MediaManagerProtos

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
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmPage()
            } else if collections.isEmpty {
                ContentUnavailableView("No collections", systemImage: "square.stack")
                    .mmPage()
            } else {
                List(collections) { collection in
                    NavigationLink(value: CollectionRoute(tmdbCollectionId: collection.tmdbCollectionId, name: collection.name)) {
                        HStack(spacing: 12) {
                            MMPosterTile(
                                name: collection.name,
                                ref: .collectionPoster(tmdbCollectionId: collection.tmdbCollectionId.protoValue),
                                width: 50,
                                placeholderIcon: "square.stack")

                            VStack(alignment: .leading, spacing: 4) {
                                Text(collection.name)
                                    .font(MarqueeTheme.Fonts.cardTitle)
                                    .foregroundStyle(MarqueeTheme.Colors.text)
                                Text("\(collection.titleCount) titles")
                                    .font(MarqueeTheme.Fonts.meta)
                                    .foregroundStyle(MarqueeTheme.Colors.text3)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .mmRow()
                }
                .mmListChrome()
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
        do {
            let response = try await dataModel.collections()
            collections = response.collections
        } catch {
            // Rapid tab nav cancels and re-fires `.task`; the
            // cancelled call would otherwise drop `loading=false`
            // with `collections.isEmpty=true` and briefly flash
            // the "No collections" empty state. Bail before
            // touching state if we were cancelled.
            if Task.isCancelled || error is CancellationError { return }
        }
        loading = false
    }
}
