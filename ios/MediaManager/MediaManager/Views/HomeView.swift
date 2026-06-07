import SwiftUI

private let log = MMLogger(category: "HomeView")

struct HomeView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var feed: ApiHomeFeed?
    @State private var error: String?
    @State private var loading = true

    var body: some View {
        // Render priority: cached content first (so a slow / failed
        // refresh doesn't replace the page with a spinner or error
        // banner), then the loading affordance, then the error
        // surface only when we have nothing else to show. The
        // previous ordering (loading → error → feed) flashed the
        // gRPC error screen during reloads with a stale `error`
        // value and `loading=false`.
        Group {
            if let feed, !feed.carousels.isEmpty {
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

                        // Continue Reading: rendered above the
                        // generic carousels because the user's intent
                        // is most likely to land here (the book they
                        // were reading) when they pop the home tab.
                        if !feed.resumeReading.isEmpty {
                            ContinueReadingCarousel(items: feed.resumeReading)
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
            } else if loading {
                ProgressView("Loading…")
            } else if let error {
                ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
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
        // Only show the spinner when we have no cached feed yet —
        // refreshes that already have content keep the page visible
        // (the body shows cached `feed` and ignores `loading`).
        loading = feed == nil
        // Clear any stale error from a previous (cancelled) task
        // run before kicking off this attempt. Without this, the
        // body's first render after a rapid tab-switch can flash
        // the error from the old task while the new task is still
        // waiting on its first await.
        error = nil
        do {
            feed = try await dataModel.homeFeed()
            error = nil
        } catch {
            // Rapid tab navigation causes SwiftUI to cancel + re-
            // fire `.task` for the same HomeView instance multiple
            // times. The cancelled call returns RPCError CANCELLED
            // (or wraps CancellationError) and would otherwise
            // surface as a flash of "GRPCCore.RPCError error 1"
            // before the next .task firing's call completes. Drop
            // those on the floor.
            if Task.isCancelled || error is CancellationError {
                log.info("loadFeed: dropping cancellation-induced error")
                return
            }
            // Don't surface a transient error if we have cached
            // content to fall back on. The page stays usable; the
            // next pull-to-refresh / tab-revisit will retry.
            if feed == nil {
                self.error = error.localizedDescription
            }
        }
        loading = false
    }
}

/// Resume-Reading carousel. Mirrors `CarouselView`'s row layout but
/// drives off `ApiResumeReading` instead of `ApiTitle`, so each tap
/// can navigate straight into `BookReaderView` (resume directly)
/// rather than the book detail page. The progress fraction is the
/// reading_progress.percent value the server stamps on every
/// `ReportReadingProgress` call.
struct ContinueReadingCarousel: View {
    let items: [ApiResumeReading]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Continue Reading")
                .font(.title2)
                .fontWeight(.bold)
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(items) { item in
                        NavigationLink(value: BookReaderRoute(
                            mediaItemId: item.mediaItemId,
                            titleName: item.titleName)) {
                            ResumeReadingCard(item: item)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }
}

private struct ResumeReadingCard: View {
    let item: ApiResumeReading

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottom) {
                BookCoverView(
                    ref: .posterThumbnail(titleId: item.titleId.protoValue),
                    seed: item.titleName)
                    .frame(width: 120, height: 180)

                // Reading-progress bar in the same shape as the
                // video resume bar on PosterCard, so the home page
                // reads consistently across media types.
                if item.percent > 0 {
                    GeometryReader { geo in
                        VStack {
                            Spacer()
                            ZStack(alignment: .leading) {
                                Rectangle()
                                    .fill(.black.opacity(0.5))
                                    .frame(height: 3)
                                Rectangle()
                                    .fill(.blue)
                                    .frame(width: geo.size.width * item.percent, height: 3)
                            }
                        }
                    }
                }
            }
            .frame(width: 120, height: 180)

            Text(item.titleName)
                .font(.caption)
                .lineLimit(2)
                .frame(width: 120, alignment: .leading)
        }
        .frame(width: 120)
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
    @Environment(OnlineDataModel.self) private var dataModel
    let title: ApiTitle

    /// `playable` is a video-only concept on the server (it
    /// reflects whether a transcode exists). Books open in the
    /// reader and albums route to the audio player from
    /// AlbumDetailView, so for them the flag is effectively
    /// meaningless and shouldn't gate dimming or "Not Playable"
    /// chrome on the card.
    private var showsPlayableState: Bool {
        !title.isAlbum && !title.isBook
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottom) {
                CachedImage(ref: .posterThumbnail(titleId: title.id.protoValue))
                    .frame(width: 120, height: 180)
                    .clipped()
                    .opacity(showsPlayableState && !title.playable ? 0.5 : 1.0)
                    .overlay(alignment: .bottomTrailing) {
                        // Mirrors ArtistDetailView's CD badge and
                        // CollectionDetailView's movie badge — same
                        // icon / colour / accessibility label so a
                        // downloaded item reads the same anywhere
                        // PosterCard appears (catalog grids, Home
                        // carousels, anywhere else this lands).
                        if dataModel.downloads.offlineTitleIds.contains(title.id.protoValue) {
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

                // Resume progress bar
                if let progress = title.resumeProgress, progress > 0 {
                    GeometryReader { geo in
                        VStack {
                            Spacer()
                            ZStack(alignment: .leading) {
                                Rectangle()
                                    .fill(.black.opacity(0.5))
                                    .frame(height: 3)
                                Rectangle()
                                    .fill(.blue)
                                    .frame(width: geo.size.width * progress, height: 3)
                            }
                        }
                    }
                }
            }
            .frame(width: 120, height: 180)

            Text(title.name)
                .font(.caption)
                .lineLimit(2)

            // Episode context for TV resume
            if let s = title.resumeSeasonNumber, let e = title.resumeEpisodeNumber {
                HStack(spacing: 2) {
                    Text("S\(s)E\(e)")
                        .fontWeight(.medium)
                    if let name = title.resumeEpisodeName {
                        Text(name)
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            } else if let year = title.year {
                Text(String(year))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if showsPlayableState && !title.playable {
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
