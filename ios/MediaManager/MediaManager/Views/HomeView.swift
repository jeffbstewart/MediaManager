import SwiftUI
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "HomeView")

/// Home, composed per the Marquee spec (section 4): a welcome block
/// on the page glow, a featured hero, browse chips, Continue Watching
/// as wide cards, Recently Added posters, a New Seasons spotlight,
/// Continue Reading, and the remaining carousels the server sends.
struct HomeView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.marquee) private var layout
    @State private var feed: ApiHomeFeed?
    @State private var error: String?
    @State private var loading = true

    private var isOffline: Bool { !dataModel.isOnline }

    var body: some View {
        // Render priority: cached content first (so a slow / failed
        // refresh doesn't replace the page with a spinner or error
        // banner), then the loading affordance, then the error
        // surface only when we have nothing else to show.
        Group {
            if let feed, !feed.carousels.isEmpty {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: layout.sectionGap) {
                        welcome

                        if let featured = featured(feed), !featured.isEmpty {
                            HeroCarousel(titles: featured)
                        }

                        browseChips

                        if let resume = feed.carousels.first(where: { $0.name == "Resume Playing" }),
                           !resume.items.isEmpty {
                            ContinueWatchingRow(items: resume.items) { titleId in
                                await dismissContinueWatching(titleId)
                            }
                        }

                        // Continue Reading sits high: the user's
                        // intent is most likely to land here.
                        if !feed.resumeReading.isEmpty {
                            ContinueReadingRow(items: feed.resumeReading)
                        }

                        ForEach(feed.carousels.filter { $0.name != "Resume Playing" }, id: \.name) { carousel in
                            CarouselView(carousel: carousel, apiClient: dataModel.apiClient)
                        }

                        if let missing = feed.missingSeasons, !missing.isEmpty {
                            MissingSeasonsRow(
                                items: missing,
                                onWish: { await wishForSeason($0) },
                                onDismiss: { await dismissMissingSeason($0) })
                        }
                    }
                    .padding(.top, 8)
                    .padding(.bottom, 64)
                }
                .mmGlowPage()
            } else if loading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmGlowPage()
            } else if let error {
                ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
                    .mmGlowPage()
            } else {
                ContentUnavailableView("No content yet", systemImage: "film.stack",
                    description: Text("Add titles to your catalog to see them here."))
                    .mmGlowPage()
            }
        }
        .navigationTitle("Home")
        .toolbarTitleDisplayMode(.inline)
        .task {
            await loadFeed()
        }
        .refreshable {
            await loadFeed()
        }
    }

    // MARK: Sections

    /// Welcome block (spec 3.8): greeting + one-line intro on the glow.
    private var welcome: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(greeting)
                .font(layout.h1)
                .foregroundStyle(MarqueeTheme.Colors.text)
            Text("Pick up where you left off, or see what's new on your shelves.")
                .font(MarqueeTheme.Fonts.body)
                .foregroundStyle(MarqueeTheme.Colors.text2)
        }
        .padding(.horizontal, layout.pageX)
        .padding(.top, 8)
    }

    private var greeting: String {
        let hour = Calendar.current.component(.hour, from: Date())
        let base = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening"
        if let info = dataModel.userInfo, !info.displayName.isEmpty {
            return "\(base), \(info.displayName)"
        }
        return base
    }

    /// Browse Your Library (spec 3.5): one chip per catalog section.
    private var browseChips: some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            MMSectionHead("Browse Your Library")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(browseTargets, id: \.label) { target in
                        NavigationLink(value: target.route) {
                            MMChip(label: target.label, systemImage: target.icon)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, layout.pageX)
            }
        }
    }

    private struct BrowseTarget {
        let label: String
        let icon: String
        let route: BrowseRoute
    }

    private var browseTargets: [BrowseTarget] {
        var targets: [BrowseTarget] = [
            BrowseTarget(label: "Movies", icon: "film", route: .movies),
            BrowseTarget(label: "TV Shows", icon: "tv", route: .tvShows),
            BrowseTarget(label: "Books", icon: "books.vertical", route: .books),
            BrowseTarget(label: "Music", icon: "music.note", route: .music),
        ]
        if !isOffline {
            targets.append(BrowseTarget(label: "Collections", icon: "square.stack", route: .collections))
            targets.append(BrowseTarget(label: "Live TV", icon: "antenna.radiowaves.left.and.right", route: .liveTv))
        }
        return targets
    }

    /// Featured hero: recently added titles that carry a backdrop,
    /// newest first, at most six. The server's typed `featured` field
    /// isn't in the iOS proto bindings yet, so this derives the same
    /// set from the "Recently Added" carousel.
    private func featured(_ feed: ApiHomeFeed) -> [ApiTitle]? {
        guard let recent = feed.carousels.first(where: { $0.name == "Recently Added" }) else { return nil }
        return Array(recent.items.filter { $0.backdropUrl != nil && !$0.isBook && !$0.isAlbum }.prefix(6))
    }

    // MARK: Actions

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

/// Where a "Browse Your Library" chip goes. Resolved by ContentView's
/// `navigationDestination(for: BrowseRoute.self)` to the same views
/// the sidebar tabs show.
enum BrowseRoute: Hashable {
    case movies, tvShows, books, music, collections, liveTv
}

// MARK: - Hero carousel (spec 3.4)

/// Paged hero banners with pill dots underneath. Each banner's tint
/// is derived from its backdrop on first appearance.
private struct HeroCarousel: View {
    @Environment(\.marquee) private var layout
    let titles: [ApiTitle]
    @State private var page: Int = 0

    var body: some View {
        VStack(spacing: 12) {
            TabView(selection: $page) {
                ForEach(Array(titles.enumerated()), id: \.element.id) { index, title in
                    HeroTitleBanner(title: title)
                        .padding(.horizontal, layout.pageX)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: layout.isWide ? 340 : heroHeight)

            if titles.count > 1 {
                HStack(spacing: 6) {
                    ForEach(0..<titles.count, id: \.self) { i in
                        Capsule()
                            .fill(i == page ? MarqueeTheme.Colors.text : MarqueeTheme.Colors.text3.opacity(0.5))
                            .frame(width: i == page ? 20 : 8, height: 4)
                            .animation(.easeOut(duration: 0.2), value: page)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Featured, page \(page + 1) of \(titles.count)")
            }
        }
    }

    /// Stacked banner: the 16:9 image (about 200pt at phone width)
    /// plus the text block (two-line title, meta, kicker, three
    /// description lines, buttons). A page-style `TabView` needs a
    /// fixed height, so this is the sum at the phone width.
    private var heroHeight: CGFloat { 520 }
}

private struct HeroTitleBanner: View {
    @Environment(ImageProvider.self) private var imageProvider
    let title: ApiTitle
    @State private var tint: Color = BackdropTint.fallback

    var body: some View {
        MMHeroBanner(
            title: title.name,
            ref: .backdrop(titleId: title.id.protoValue),
            tint: tint,
            contentRating: title.contentRating,
            year: title.year,
            kind: title.mediaType == .tv ? "TV Series" : "Movie",
            kicker: "Just added to your library",
            description: title.description
        ) {
            if title.playable, let tcId = title.transcodeId {
                NavigationLink(value: PlaybackRoute(
                    transcodeId: tcId, titleName: title.name, episodeName: nil, hasSubtitles: false)) {
                    Label("Play", systemImage: "play.fill")
                }
                .buttonStyle(.mmPrimary)
            }
            NavigationLink(value: title) {
                Label("Details", systemImage: "info.circle")
            }
            .buttonStyle(.mmGhost)
        }
        .task(id: title.id.protoValue) {
            tint = await BackdropTint.tint(forTitleId: title.id.protoValue, imageProvider: imageProvider)
        }
    }
}

// MARK: - Continue Watching (spec 3.3 wide cards)

private struct ContinueWatchingRow: View {
    let items: [ApiTitle]
    let onDismiss: (TitleID) async -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            MMSectionHead("Continue Watching")
            MMRow {
                ForEach(items) { title in
                    ZStack(alignment: .topTrailing) {
                        NavigationLink(value: title) {
                            MMWideCard(
                                name: title.name,
                                ref: title.backdropUrl != nil
                                    ? .backdrop(titleId: title.id.protoValue)
                                    : .posterThumbnail(titleId: title.id.protoValue),
                                caption: episodeCaption(title),
                                meta: timeRemaining(title),
                                progress: title.resumeProgress,
                                showPlay: true)
                        }
                        .buttonStyle(.plain)

                        Button {
                            Task { await onDismiss(title.id) }
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(6)
                                .background(MarqueeTheme.Colors.scrimStrong, in: Circle())
                        }
                        .buttonStyle(.plain)
                        .padding(6)
                        .accessibilityLabel("Dismiss \(title.name)")
                    }
                }
            }
        }
    }

    private func episodeCaption(_ title: ApiTitle) -> String? {
        guard let s = title.resumeSeasonNumber, let e = title.resumeEpisodeNumber else { return nil }
        var parts = ["S\(s) • E\(e)"]
        if let name = title.resumeEpisodeName, !name.isEmpty { parts.append(name) }
        return parts.joined(separator: " • ")
    }

    private func timeRemaining(_ title: ApiTitle) -> String? {
        guard let pos = title.resumePositionSeconds, let dur = title.resumeDurationSeconds, dur > pos else { return nil }
        let minutes = Int(((dur - pos) / 60).rounded(.up))
        if minutes >= 60 {
            return "\(minutes / 60)h \(minutes % 60)m left"
        }
        return "\(minutes)m left"
    }
}

// MARK: - Continue Reading

/// Resume-Reading row: poster cards with the reading-progress pill
/// and an amber "% read" line. Each tap opens `BookReaderView`
/// directly, since the user's intent is unambiguous from a resume
/// list.
struct ContinueReadingRow: View {
    @Environment(\.marquee) private var layout
    let items: [ApiResumeReading]

    var body: some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            MMSectionHead("Continue Reading")
            MMRow {
                ForEach(items) { item in
                    NavigationLink(value: BookReaderRoute(
                        mediaItemId: item.mediaItemId,
                        titleName: item.titleName)) {
                        VStack(alignment: .leading, spacing: 0) {
                            MMPosterTile(
                                name: item.titleName,
                                ref: .posterThumbnail(titleId: item.titleId.protoValue),
                                progress: item.percent,
                                placeholderIcon: "book.closed")
                            MMCaption(
                                title: item.titleName,
                                meta: "\(Int((item.percent * 100).rounded()))% read",
                                metaAccent: true,
                                width: layout.posterWidth)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

// MARK: - New Seasons Available (wide cards with the amber badge)

private struct MissingSeasonsRow: View {
    let items: [ApiMissingSeason]
    let onWish: (ApiMissingSeason) async -> Void
    let onDismiss: (ApiMissingSeason) async -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            MMSectionHead("New Seasons Available")
            MMRow {
                ForEach(items) { ms in
                    VStack(alignment: .leading, spacing: 8) {
                        MMWideCard(
                            name: ms.titleName,
                            ref: .backdrop(titleId: ms.titleId.protoValue),
                            meta: "Missing " + seasonList(ms),
                            badge: badge(ms),
                            badgeTone: .accent)
                        HStack(spacing: 8) {
                            Button {
                                Task { await onWish(ms) }
                            } label: {
                                Label("Wish List", systemImage: "heart")
                            }
                            .buttonStyle(.mmGhost)
                            Button {
                                Task { await onDismiss(ms) }
                            } label: {
                                Image(systemName: "xmark")
                                    .frame(width: 20)
                            }
                            .buttonStyle(.mmGhost)
                            .accessibilityLabel("Dismiss \(ms.titleName)")
                        }
                    }
                }
            }
        }
    }

    private func seasonList(_ ms: ApiMissingSeason) -> String {
        let numbers = ms.seasons.map { "S\($0.seasonNumber)" }
        return numbers.count > 3 ? numbers.prefix(3).joined(separator: ", ") + "…" : numbers.joined(separator: ", ")
    }

    private func badge(_ ms: ApiMissingSeason) -> String {
        if ms.seasons.count == 1, let s = ms.seasons.first {
            return "New S\(s.seasonNumber)"
        }
        return "New Seasons"
    }
}

// MARK: - Generic carousel

struct CarouselView: View {
    let carousel: ApiCarousel
    let apiClient: APIClient
    var dismissable: Bool = false
    var onDismiss: ((TitleID) async -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            MMSectionHead(carousel.name)
            MMRow {
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
                                Image(systemName: "xmark")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(.white)
                                    .padding(6)
                                    .background(MarqueeTheme.Colors.scrimStrong, in: Circle())
                            }
                            .buttonStyle(.plain)
                            .padding(6)
                            .accessibilityLabel("Dismiss \(title.name)")
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Poster card (spec 3.2) over an ApiTitle

/// The title-aware poster card used by Home carousels, catalog grids,
/// actor pages and collections: spec art (8pt radius, shadow,
/// hairline, amber progress pill) plus the download marker, playable
/// dimming and the resume / year / family meta lines.
struct PosterCard: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.marquee) private var layout
    let title: ApiTitle
    /// Override the spec width (grids pass the column width).
    var width: CGFloat? = nil

    /// `playable` is a video-only concept on the server (it reflects
    /// whether a transcode exists). Books open in the reader and
    /// albums route to the audio player from AlbumDetailView, so for
    /// them the flag shouldn't gate dimming or "Not Playable" chrome.
    private var showsPlayableState: Bool {
        !title.isAlbum && !title.isBook
    }

    private var w: CGFloat { width ?? layout.posterWidth }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            MMPosterTile(
                name: title.name,
                ref: .posterThumbnail(titleId: title.id.protoValue),
                square: title.isAlbum,
                width: w,
                progress: title.resumeProgress,
                placeholderIcon: title.isAlbum ? "music.note" : title.isBook ? "book.closed" : nil,
                dimmed: showsPlayableState && !title.playable)
            .overlay(alignment: .bottomTrailing) {
                // Same icon / colour / accessibility label as the
                // other downloaded markers so a downloaded item reads
                // the same anywhere PosterCard appears.
                if dataModel.downloads.offlineTitleIds.contains(title.id.protoValue) {
                    MMArtBadge(systemImage: "arrow.down", label: "Downloaded")
                        .padding(.bottom, title.resumeProgress ?? 0 > 0 ? 12 : 0)
                }
            }

            MMCaption(title: title.name, meta: metaLine, metaAccent: metaIsAccent, width: w)

            if showsPlayableState && !title.playable {
                Text("Not Playable")
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.orange)
                    .padding(.top, 2)
            }

            if let members = title.familyMembers, !members.isEmpty {
                Text(members.joined(separator: ", "))
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.text3)
                    .lineLimit(1)
                    .frame(width: w, alignment: .leading)
                    .padding(.top, 2)
            }
        }
        .frame(width: w, alignment: .topLeading)
        .contentShape(Rectangle())
    }

    /// Episode context for TV resume, else the year.
    private var metaLine: String? {
        if let s = title.resumeSeasonNumber, let e = title.resumeEpisodeNumber {
            if let name = title.resumeEpisodeName, !name.isEmpty {
                return "S\(s) • E\(e) • \(name)"
            }
            return "S\(s) • E\(e)"
        }
        if let year = title.year { return String(year) }
        return nil
    }

    private var metaIsAccent: Bool {
        title.resumeSeasonNumber != nil
    }
}
