import SwiftUI

struct HomeView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var feed: ApiHomeFeed?
    @State private var error: String?
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let error {
                ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
            } else if let feed, !feed.carousels.isEmpty {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 24) {
                        // Missing seasons notifications
                        if let missing = feed.missingSeasons, !missing.isEmpty {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("New Seasons Available")
                                    .font(.title2)
                                    .fontWeight(.bold)
                                    .padding(.horizontal)

                                ForEach(missing) { ms in
                                    HStack(spacing: 12) {
                                        CachedImage(ref: .posterThumbnail(titleId: ms.titleId.protoValue))
                                            .frame(width: 50, height: 75)

                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(ms.titleName)
                                                .fontWeight(.medium)
                                            ForEach(ms.seasons) { season in
                                                Text(season.name ?? "Season \(season.seasonNumber)")
                                                    .font(.caption)
                                                    .foregroundStyle(.secondary)
                                            }
                                        }

                                        Spacer()

                                        Button {
                                            Task { await wishForSeason(ms) }
                                        } label: {
                                            Image(systemName: "heart.circle")
                                                .font(.title2)
                                                .foregroundStyle(.red)
                                        }

                                        Button {
                                            Task { await dismissMissingSeason(ms) }
                                        } label: {
                                            Image(systemName: "xmark.circle.fill")
                                                .font(.title3)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    .padding(.horizontal)
                                }
                            }
                        }

                        ForEach(feed.carousels, id: \.name) { carousel in
                            if carousel.name == "Resume Playing" {
                                CarouselView(
                                    carousel: carousel,
                                    apiClient: dataModel.apiClient,
                                    dismissable: true,
                                    onDismiss: { titleId in
                                        await dismissContinueWatching(titleId)
                                    }
                                )
                            } else {
                                CarouselView(carousel: carousel, apiClient: dataModel.apiClient)
                            }
                        }
                    }
                    .padding(.vertical)
                }
            } else {
                ContentUnavailableView("No content yet", systemImage: "film.stack",
                    description: Text("Add titles to your catalog to see them here."))
            }
        }
        .navigationTitle("Home")
        .task {
            await loadFeed()
        }
        .refreshable {
            await loadFeed()
        }
    }

    private func wishForSeason(_ ms: ApiMissingSeason) async {
        guard let tmdbId = ms.tmdbId else { return }
        for season in ms.seasons {
            try? await dataModel.addWish(
                tmdbId: tmdbId,
                mediaType: ms.mediaType ?? .tv,
                title: ms.titleName,
                year: nil,
                posterPath: nil,
                seasonNumber: season.seasonNumber
            )
        }
        await loadFeed()
    }

    private func dismissMissingSeason(_ ms: ApiMissingSeason) async {
        guard let tmdbId = ms.tmdbId else { return }
        for season in ms.seasons {
            try? await dataModel.dismissMissingSeason(
                titleId: ms.titleId,
                tmdbId: tmdbId,
                mediaType: ms.mediaType ?? .tv,
                seasonNumber: season.seasonNumber
            )
        }
        await loadFeed()
    }

    private func dismissContinueWatching(_ titleId: TitleID) async {
        try? await dataModel.dismissContinueWatching(titleId: titleId)
        await loadFeed()
    }

    private func loadFeed() async {
        loading = feed == nil
        do {
            feed = try await dataModel.homeFeed()
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }
}

struct CarouselView: View {
    let carousel: ApiCarousel
    let apiClient: APIClient
    var dismissable: Bool = false
    var onDismiss: ((TitleID) async -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(carousel.name)
                .font(.title2)
                .fontWeight(.bold)
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(carousel.items) { title in
                        ZStack(alignment: .topTrailing) {
                            NavigationLink(value: title) {
                                PosterCard(title: title)
                            }
                            .buttonStyle(.plain)

                            if dismissable {
                                Button {
                                    Task { await onDismiss?(title.id) }
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.system(size: 18))
                                        .symbolRenderingMode(.palette)
                                        .foregroundStyle(.white, .black.opacity(0.5))
                                }
                                .offset(x: 4, y: -4)
                            }
                        }
                    }
                }
                .padding(.horizontal)
            }
        }
    }
}

struct PosterCard: View {
    let title: ApiTitle

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            CachedImage(ref: .posterThumbnail(titleId: title.id.protoValue))
                .frame(width: 120, height: 180)
                .clipped()
                .opacity(title.playable ? 1.0 : 0.5)

            Text(title.name)
                .font(.caption)
                .lineLimit(2)

            if let year = title.year {
                Text(String(year))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if !title.playable {
                Text("Not Playable")
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }

            if let members = title.familyMembers, !members.isEmpty {
                Text(members.joined(separator: ", "))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(width: 120, alignment: .topLeading)
        .contentShape(Rectangle())
    }
}
