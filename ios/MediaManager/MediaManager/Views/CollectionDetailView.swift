import SwiftUI

struct CollectionDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let route: CollectionRoute
    @State private var detail: ApiCollectionDetail?
    @State private var loading = true
    @State private var localWished: [TmdbID: Bool] = [:]

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
                                    mediaType: .movie, year: item.year,
                                    description: nil, posterUrl: item.posterUrl,
                                    backdropUrl: nil, contentRating: item.contentRating,
                                    popularity: nil, quality: item.quality,
                                    playable: item.playable, transcodeId: item.transcodeId,
                                    tmdbId: item.tmdbMovieId, tmdbCollectionId: nil,
                                    tmdbCollectionName: nil, familyMembers: nil,
                                    forMobileAvailable: nil
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
        if let titleId = item.titleId {
            CachedImage(ref: .posterThumbnail(titleId: titleId.protoValue))
        } else {
            CachedImage(ref: .tmdbPoster(tmdbId: item.tmdbMovieId.protoValue, mediaType: .movie))
        }
    }

    private func toggleWish(_ item: ApiCollectionItem) async {
        let currentlyWished = isWished(item)
        localWished[item.tmdbMovieId] = !currentlyWished

        if currentlyWished {
            let response = try? await dataModel.wishList()
            if let wish = response?.wishes.first(where: { $0.tmdbId == item.tmdbMovieId && $0.mediaType == .movie }),
               let wishId = wish.wishId {
                try? await dataModel.deleteWish(id: wishId)
            }
        } else {
            var posterPath: String? = nil
            if let posterUrl = item.posterUrl, posterUrl.contains("image.tmdb.org") {
                posterPath = posterUrl.replacingOccurrences(of: "https://image.tmdb.org/t/p/w500", with: "")
            }
            try? await dataModel.addWish(
                tmdbId: item.tmdbMovieId,
                mediaType: .movie,
                title: item.name,
                year: item.year,
                posterPath: posterPath,
                seasonNumber: nil
            )
        }
    }

    private func loadDetail() async {
        loading = true
        detail = try? await dataModel.collectionDetail(id: route.tmdbCollectionId)
        loading = false
    }
}
