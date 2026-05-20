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

    /// Transcode IDs eligible for bulk download from this collection.
    /// Owned + playable + has a transcodeId. ApiCollectionItem doesn't
    /// carry the `forMobileAvailable` flag the per-title detail page
    /// uses, so individual items with no mobile transcode may
    /// fail-fast in DownloadManager — the row's failed-state surfaces
    /// that.
    private var bulkTranscodeIds: [Int64] {
        guard let detail else { return [] }
        return detail.items.compactMap { item in
            guard item.owned, item.playable, let tcId = item.transcodeId
            else { return nil }
            return tcId.protoValue
        }
    }

    private var showBulkDownload: Bool {
        dataModel.capabilities.contains("downloads") && !bulkTranscodeIds.isEmpty
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                ScrollView {
                    VStack(spacing: 16) {
                        if showBulkDownload {
                            BulkDownloadActionRow(
                                status: dataModel.downloads
                                    .bulkStatus(forTranscodes: bulkTranscodeIds)
                                    .asBulkDownloadStatus,
                                noun: "movie",
                                pluralNoun: "movies",
                                action: { startBulkDownload(detail) })
                                .padding(.horizontal)
                                .padding(.top, 8)
                        }

                        LazyVGrid(columns: columns, spacing: 16) {
                            ForEach(detail.items) { item in
                                if item.owned, let titleId = item.titleId {
                                    NavigationLink(value: ApiTitle(
                                        id: titleId, name: item.name,
                                        mediaType: .movie, year: item.year,
                                        description: nil,
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
                        .padding(.horizontal)
                    }
                    .padding(.bottom)
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

    private func startBulkDownload(_ detail: ApiCollectionDetail) {
        for item in detail.items {
            guard item.owned, item.playable,
                  let tcId = item.transcodeId,
                  let titleId = item.titleId
            else { continue }
            // Skip whatever's already downloaded / in-flight — same
            // shape EpisodesView.downloadSeason uses.
            guard dataModel.downloads.state(for: tcId.protoValue) == .unknown
            else { continue }
            dataModel.downloads.startDownload(
                transcodeId: tcId.protoValue,
                titleId: titleId.protoValue,
                titleName: item.name,
                quality: .unknown,
                year: Int32(item.year ?? 0),
                mediaType: .movie,
                contentRating: .unknown)
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
                    .overlay(alignment: .bottomTrailing) {
                        // Same badge as ArtistDetailView's downloaded-
                        // album marker. Bottom-right so it doesn't
                        // collide with the top-right wish heart on
                        // un-owned tiles.
                        if item.owned, let titleId = item.titleId,
                           dataModel.downloads.offlineTitleIds.contains(titleId.protoValue) {
                            Image(systemName: "arrow.down.circle.fill")
                                .font(.caption)
                                .foregroundStyle(.white)
                                .padding(4)
                                .background(.black.opacity(0.55))
                                .clipShape(Circle())
                                .padding(6)
                                .accessibilityLabel("Downloaded")
                        }
                    }

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
            try? await dataModel.addWish(
                tmdbId: item.tmdbMovieId,
                mediaType: .movie,
                title: item.name,
                year: item.year,
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
