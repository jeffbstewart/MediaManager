import SwiftUI

struct CollectionDetailView: View {
    @Environment(AuthManager.self) private var authManager
    let route: CollectionRoute
    @State private var detail: ApiCollectionDetail?
    @State private var loading = true
    @State private var localWished: [Int: Bool] = [:]

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
                        ForEach(detail.items) { item in
                            if item.owned, let titleId = item.titleId {
                                NavigationLink(value: ApiTitle(
                                    id: titleId, name: item.name,
                                    mediaType: "MOVIE", year: item.year,
                                    description: nil, posterUrl: item.posterUrl,
                                    backdropUrl: nil, contentRating: item.contentRating,
                                    popularity: nil, quality: item.quality,
                                    playable: item.playable, transcodeId: item.transcodeId,
                                    tmdbId: item.tmdbMovieId, tmdbCollectionId: nil,
                                    tmdbCollectionName: nil, familyMembers: nil
                                )) {
                                    collectionItemCard(item)
                                }
                                .buttonStyle(.plain)
                            } else {
                                collectionItemCard(item)
                            }
                        }
                    }
                    .padding()
                }
            } else {
                ContentUnavailableView("Collection not found", systemImage: "square.stack")
            }
        }
        .navigationTitle(route.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadDetail()
        }
    }

    private func isWished(_ item: ApiCollectionItem) -> Bool {
        localWished[item.tmdbMovieId] ?? false
    }

    @ViewBuilder
    private func collectionItemCard(_ item: ApiCollectionItem) -> some View {
        VStack(alignment: .center, spacing: 4) {
            ZStack(alignment: .topTrailing) {
                posterImage(item)
                    .frame(width: 120, height: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .opacity(item.owned ? 1.0 : 0.6)

                // Wish heart for unowned items
                if !item.owned {
                    Button {
                        Task { await toggleWish(item) }
                    } label: {
                        Image(systemName: isWished(item) ? "heart.fill" : "heart")
                            .font(.body)
                            .foregroundStyle(isWished(item) ? .red : .white)
                            .padding(6)
                            .background(.black.opacity(0.5))
                            .clipShape(Circle())
                    }
                    .padding(4)
                }
            }

            Text(item.name)
                .font(.caption)
                .lineLimit(2)
                .multilineTextAlignment(.center)

            if let year = item.year {
                Text(String(year))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if !item.owned {
                Text("Not owned")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 120)
    }

    @ViewBuilder
    private func posterImage(_ item: ApiCollectionItem) -> some View {
        if let posterUrl = item.posterUrl {
            if posterUrl.hasPrefix("http") {
                AsyncImage(url: URL(string: posterUrl)) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Rectangle().fill(.quaternary)
                        .overlay { Image(systemName: "film").foregroundStyle(.secondary) }
                }
            } else {
                AuthenticatedImage(path: posterUrl, apiClient: authManager.apiClient)
            }
        } else {
            Rectangle().fill(.quaternary)
                .overlay { Image(systemName: "film").foregroundStyle(.secondary) }
        }
    }

    private func toggleWish(_ item: ApiCollectionItem) async {
        let currentlyWished = isWished(item)
        localWished[item.tmdbMovieId] = !currentlyWished

        if currentlyWished {
            let response: ApiWishListResponse? = try? await authManager.apiClient.get("wishlist")
            if let wish = response?.wishes.first(where: { $0.tmdbId == item.tmdbMovieId && $0.mediaType == "MOVIE" }),
               let wishId = wish.wishId {
                try? await authManager.apiClient.delete("wishlist/\(wishId)")
            }
        } else {
            var body: [String: Any] = [
                "tmdb_id": item.tmdbMovieId,
                "media_type": "MOVIE",
                "title": item.name,
                "popularity": 0
            ]
            if let posterUrl = item.posterUrl, posterUrl.contains("image.tmdb.org") {
                body["poster_path"] = posterUrl.replacingOccurrences(of: "https://image.tmdb.org/t/p/w500", with: "")
            }
            if let year = item.year {
                body["release_year"] = year
            }
            try? await authManager.apiClient.post("wishlist", body: body)
        }
    }

    private func loadDetail() async {
        loading = true
        detail = try? await authManager.apiClient.get("catalog/collections/\(route.tmdbCollectionId)")
        loading = false
    }
}
