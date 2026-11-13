import SwiftUI
import GRPCCore
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "MusicView")

/// Music landing page. Replaces direct routing to ArtistsView with a
/// top-level surface that aggregates the audio module's discovery
/// affordances:
///
///   - "Library Shuffle" hero card (kicks AudioPlayerManager).
///   - Smart Playlists horizontal carousel (server-defined virtual
///     playlists like "Recently Added", "Most Played").
///   - Recently Added Albums carousel with per-card dismiss-X.
///   - "Browse Artists" link → ArtistsView grid.
///
/// "Recently Added Albums" hides itself entirely when the list comes
/// back empty (after server-side dismissal filtering) — per design
/// memory: the carousel can become annoying after a while, so the
/// dismiss path needs to be able to take it to zero and the UI
/// honours that.
struct MusicView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(AudioPlayerManager.self) private var audio
    @Environment(\.marquee) private var layout

    @State private var smartPlaylists: [ApiSmartPlaylistSummary] = []
    @State private var userPlaylists: [ApiPlaylistSummary] = []
    @State private var recentlyAdded: [ApiTitle] = []
    /// Recommended artists from the similar-artist graph. Server
    /// curates this in the background; the carousel hides itself
    /// when the list is empty (matches the Recently Added pattern).
    @State private var recommendedArtists: [ApiRecommendedArtist] = []
    @State private var loading = true
    @State private var shuffleStarting = false
    @State private var showCreate = false
    // Music-scoped search. Typing replaces the landing surface with
    // album / artist / track hits (via `SearchService.SearchMusicOnly`);
    // clearing the field restores the landing.
    @State private var query = ""
    @State private var searchResults: [ApiSearchResult] = []
    @State private var searching = false
    @State private var hasSearched = false
    @State private var searchTask: Task<Void, Never>? = nil

    private var trimmedQuery: String {
        query.trimmingCharacters(in: .whitespaces)
    }

    var body: some View {
        Group {
            // Hold the entire page behind a spinner until the first
            // load completes. Without this the static shuffle hero +
            // Browse Artists link paint instantly while the RPC-
            // driven sections (Your Playlists, Smart Playlists, For
            // You, Recently Added) trickle in over the next few
            // seconds — looks half-loaded.
            if loading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmGlowPage(MarqueeTheme.Colors.glowDiscover)
            } else if !trimmedQuery.isEmpty {
                searchResultsList()
            } else {
                ScrollView {
                    // Section heads and rows pad themselves to the
                    // page gutter; the rows bleed to the screen edge
                    // (spec 3.1), so the stack carries no horizontal
                    // padding of its own.
                    VStack(alignment: .leading, spacing: layout.sectionGap) {
                        shuffleHeroCard()
                            .padding(.horizontal, layout.pageX)
                        yourPlaylistsSection()
                        if !smartPlaylists.isEmpty {
                            smartPlaylistsSection()
                        }
                        if !recommendedArtists.isEmpty {
                            forYouSection()
                        }
                        // Recently Added is a server-driven carousel — the
                        // offline home feed doesn't carry it. Hide entirely
                        // offline rather than rendering a section that's
                        // either empty or showing stale post-restore content.
                        if dataModel.isOnline && !recentlyAdded.isEmpty {
                            recentlyAddedSection()
                        }
                        browseArtistsLink()
                            .padding(.horizontal, layout.pageX)
                    }
                    .padding(.vertical, 16)
                    .padding(.bottom, 48)
                }
                .mmGlowPage(MarqueeTheme.Colors.glowDiscover)
            }
        }
        .navigationTitle("Music")
        // Music-scoped search bar. Floats above the mini-player on
        // iOS 26 (same as every other tab destination's `.searchable`).
        // Hits `searchMusicOnly` so results stay scoped to albums,
        // artists, and tracks — Movies / TV / Books live in the
        // global Search tab. Suppressed offline because the server's
        // music index is the only useful target.
        .searchableIfOnline(
            text: $query,
            isOnline: dataModel.isOnline,
            prompt: "Search music")
        .onChange(of: dataModel.isOnline) { _, newValue in
            if !newValue { query = "" }
        }
        .onChange(of: query) { _, newValue in
            searchTask?.cancel()
            let trimmed = newValue.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty {
                searching = false
                hasSearched = false
                searchResults = []
                return
            }
            searching = true
            // 300ms debounce — matches SearchView so a fast typist
            // doesn't fire a dozen searches en route to the final
            // query.
            searchTask = Task {
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }
                await runSearch(trimmed)
            }
        }
        .sheet(isPresented: $showCreate) {
            CreatePlaylistSheet { name, description in
                await createPlaylist(name: name, description: description)
            }
        }
        .task { await load() }
        .refreshable { await load() }
    }

    @ViewBuilder
    private func searchResultsList() -> some View {
        List {
            if searching {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            } else if searchResults.isEmpty && hasSearched {
                ContentUnavailableView.search(text: trimmedQuery)
            } else {
                ForEach(searchResults) { result in
                    musicResultRow(result)
                }
                .mmRow()
            }
        }
        .mmListChrome()
    }

    /// Music-only result-row dispatch. `searchMusicOnly` only returns
    /// album / artist / track types, but we route by `resultType`
    /// anyway so an unexpected kind doesn't fall through silently.
    @ViewBuilder
    private func musicResultRow(_ result: ApiSearchResult) -> some View {
        switch result.resultType {
        case "album":
            if let titleId = result.titleId {
                NavigationLink(value: ApiTitle(
                    id: titleId, name: result.name,
                    mediaType: result.mediaType ?? .personal,
                    year: result.year, description: nil,
                    backdropUrl: nil, contentRating: result.contentRating,
                    popularity: nil, quality: result.quality,
                    playable: true, transcodeId: nil,
                    tmdbId: nil, tmdbCollectionId: nil,
                    tmdbCollectionName: nil, familyMembers: nil,
                    forMobileAvailable: nil
                )) {
                    SearchResultRow(result: result, apiClient: dataModel.apiClient)
                }
            }
        case "artist":
            if let artistId = result.artistId {
                NavigationLink(value: ArtistRoute(id: artistId, name: result.name)) {
                    SearchResultRow(result: result, apiClient: dataModel.apiClient)
                }
            }
        default:
            // Tracks + anything unexpected — non-navigable row; the
            // user already sees the metadata, can tap into the album
            // from the album hit if one's nearby.
            SearchResultRow(result: result, apiClient: dataModel.apiClient)
        }
    }

    private func runSearch(_ q: String) async {
        do {
            let response = try await dataModel.searchMusicOnly(query: q)
            guard !Task.isCancelled else { return }
            searchResults = response.results
        } catch {
            guard !Task.isCancelled else { return }
            log.warning("searchMusicOnly failed: \(error.localizedDescription)")
            searchResults = []
        }
        searching = false
        hasSearched = true
    }

    // MARK: - Sections

    @ViewBuilder
    private func shuffleHeroCard() -> some View {
        Button {
            Task { await startShuffle() }
        } label: {
            HStack(spacing: 16) {
                Image(systemName: "shuffle")
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 64, height: 64)
                    .background(LinearGradient(
                        colors: [MarqueeTheme.Colors.plum, MarqueeTheme.Colors.blue],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing))
                    .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.card, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text("Shuffle Library")
                        .font(MarqueeTheme.Fonts.h3)
                        .foregroundStyle(MarqueeTheme.Colors.text)
                    Text("Random tracks from your whole collection")
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                        .lineLimit(2)
                }
                Spacer()
                if shuffleStarting {
                    ProgressView()
                } else {
                    // The play glyph sits in the spec's white pill
                    // so the card reads as the page's primary action.
                    Image(systemName: "play.fill")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(MarqueeTheme.Colors.ink)
                        .frame(width: MarqueeTheme.Sizes.pillHeight, height: MarqueeTheme.Sizes.pillHeight)
                        .background(Color.white, in: Circle())
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity)
            .background(MarqueeTheme.Colors.surface)
            .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.hero, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: MarqueeTheme.Radius.hero, style: .continuous)
                    .strokeBorder(MarqueeTheme.Colors.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(shuffleStarting)
    }

    @ViewBuilder
    private func yourPlaylistsSection() -> some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            MMSectionHead("Your Playlists") {
                NavigationLink(value: AllPlaylistsRoute()) {
                    MMSeeAllChevron()
                }
                .buttonStyle(.plain)
                .accessibilityLabel("See all playlists")
                Spacer(minLength: 0)
                // Create lives beside the title as a quiet pill, so
                // it stays discoverable without competing with the
                // playlist art in the row. Hidden offline because
                // creation needs the server.
                if dataModel.isOnline {
                    Button {
                        showCreate = true
                    } label: {
                        Label("New", systemImage: "plus")
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.text)
                            .padding(.horizontal, 12)
                            .frame(height: 28)
                            .background(MarqueeTheme.Colors.surface, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("New playlist")
                }
            }
            MMRow {
                ForEach(userPlaylists) { p in
                    NavigationLink(value: PlaylistRoute(id: p.id, name: p.name)) {
                        userPlaylistCard(p)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder
    private func userPlaylistCard(_ p: ApiPlaylistSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Square — playlist hero aspect. No hero yet → the seeded
            // placeholder with a playlist glyph.
            MMPosterTile(
                name: p.name,
                ref: p.heroTitleId.map { .posterThumbnail(titleId: $0.protoValue) },
                square: true,
                width: layout.squareWidth,
                placeholderIcon: "music.note.list")
            MMCaption(
                title: p.name,
                meta: (p.description?.isEmpty == false) ? p.description : nil,
                credit: p.isPrivate ? "Private" : nil,
                width: layout.squareWidth)
        }
        .frame(width: layout.squareWidth, alignment: .topLeading)
    }

    @ViewBuilder
    private func smartPlaylistsSection() -> some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            MMSectionHead("Smart Playlists")
            MMRow {
                ForEach(smartPlaylists) { p in
                    NavigationLink(value: SmartPlaylistRoute(key: p.key, name: p.name)) {
                        smartPlaylistCard(p)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder
    private func smartPlaylistCard(_ p: ApiSmartPlaylistSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Square — album art aspect.
            MMPosterTile(
                name: p.name,
                ref: p.heroTitleId.map { .posterThumbnail(titleId: $0.protoValue) },
                square: true,
                width: layout.squareWidth,
                placeholderIcon: "sparkles")
            MMCaption(
                title: p.name,
                meta: "\(p.trackCount) track\(p.trackCount == 1 ? "" : "s")",
                width: layout.squareWidth)
        }
        .frame(width: layout.squareWidth, alignment: .topLeading)
    }

    @ViewBuilder
    private func recentlyAddedSection() -> some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            MMSectionHead("Recently Added")
            MMRow {
                ForEach(recentlyAdded) { album in
                    recentlyAddedCard(album)
                }
            }
        }
    }

    @ViewBuilder
    private func recentlyAddedCard(_ album: ApiTitle) -> some View {
        ZStack(alignment: .topTrailing) {
            NavigationLink(value: album) {
                VStack(alignment: .leading, spacing: 0) {
                    MMPosterTile(
                        name: album.name,
                        ref: .posterThumbnail(titleId: album.id.protoValue),
                        square: true,
                        width: layout.squareWidth,
                        placeholderIcon: "music.note")
                    MMCaption(
                        title: album.name,
                        meta: album.year.map(String.init),
                        width: layout.squareWidth)
                }
                .frame(width: layout.squareWidth, alignment: .topLeading)
            }
            .buttonStyle(.plain)

            // Dismiss-X overlay. Per design memory: per-card
            // affordance because the carousel becomes annoying after
            // a while. Optimistic local removal — server PR is
            // fire-and-forget; on error we'd just see it again on
            // next refresh, which is acceptable.
            Button {
                Task { await dismiss(album) }
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(6)
                    .background(MarqueeTheme.Colors.scrimStrong, in: Circle())
            }
            .buttonStyle(.plain)
            .padding(6)
            .accessibilityLabel("Dismiss \(album.name)")
        }
    }

    @ViewBuilder
    private func forYouSection() -> some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            MMSectionHead("For You")
            MMRow {
                ForEach(recommendedArtists) { rec in
                    forYouCard(rec)
                }
            }
        }
    }

    @ViewBuilder
    private func forYouCard(_ rec: ApiRecommendedArtist) -> some View {
        ZStack(alignment: .topTrailing) {
            Group {
                if let artistId = rec.localArtistId {
                    NavigationLink(value: ArtistRoute(
                        id: ArtistID(proto: artistId),
                        name: rec.name)
                    ) {
                        forYouCardBody(rec)
                    }
                    .buttonStyle(.plain)
                } else {
                    // Server hasn't seeded a local Artist row for this
                    // suggestion yet — most recommendations point at
                    // unowned artists. Card is informational only;
                    // dismiss-X is the only interactive affordance.
                    forYouCardBody(rec)
                }
            }

            // Dismiss-X overlay — same shape as Recently Added.
            // Optimistic local removal so the card disappears
            // immediately; server PR is fire-and-forget.
            Button {
                Task { await dismissRecommendation(rec) }
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(6)
                    .background(MarqueeTheme.Colors.scrimStrong, in: Circle())
            }
            .buttonStyle(.plain)
            .padding(6)
            .accessibilityLabel("Dismiss \(rec.name)")
        }
    }

    @ViewBuilder
    private func forYouCardBody(_ rec: ApiRecommendedArtist) -> some View {
        let rgid = rec.representativeReleaseGroupMbid.flatMap { $0.isEmpty ? nil : $0 }
        VStack(alignment: .leading, spacing: 0) {
            // Square — album cover aspect; a missing cover shows the
            // seeded placeholder with a microphone.
            MMPosterTile(
                name: rec.name,
                ref: rgid.map { .coverArtArchiveReleaseGroup(releaseGroupId: $0) },
                square: true,
                width: layout.squareWidth,
                placeholderIcon: "music.mic")
            // Voters teaser: "Liked by X & Y" / "Liked by X, Y +N"
            // depending on count. Falls back to the representative
            // release title when the server didn't ship voters.
            MMCaption(title: rec.name, meta: votersCaption(rec), width: layout.squareWidth)
        }
        .frame(width: layout.squareWidth, alignment: .topLeading)
    }

    private func votersCaption(_ rec: ApiRecommendedArtist) -> String {
        let voters = rec.voterArtistNames
        switch voters.count {
        case 0:
            // No voters surfaced — fall back to the representative
            // album title so the caption isn't blank.
            return rec.representativeReleaseTitle ?? " "
        case 1:
            return "Liked by \(voters[0])"
        case 2:
            return "Liked by \(voters[0]) & \(voters[1])"
        default:
            return "Liked by \(voters[0]), \(voters[1]) +\(voters.count - 2)"
        }
    }

    @ViewBuilder
    private func browseArtistsLink() -> some View {
        NavigationLink(value: BrowseArtistsRoute()) {
            HStack(spacing: 12) {
                Image(systemName: "music.mic")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(MarqueeTheme.Colors.accent)
                Text("Browse Artists")
                    .font(MarqueeTheme.Fonts.h3)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                Spacer()
                MMSeeAllChevron()
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 52)
            .background(MarqueeTheme.Colors.surface)
            .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.card, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Actions

    private func load() async {
        loading = true
        async let homeFeedTask: ApiHomeFeed? = try? await dataModel.homeFeed()
        async let smartTask: [ApiSmartPlaylistSummary] = (try? await dataModel.smartPlaylists()) ?? []
        async let userTask: [ApiPlaylistSummary] = (try? await dataModel.playlists(scope: .mine)) ?? []
        async let recsTask: [ApiRecommendedArtist] = (try? await dataModel.recommendedArtists()) ?? []
        if let feed = await homeFeedTask {
            recentlyAdded = feed.recentlyAddedAlbums
        }
        smartPlaylists = await smartTask
        userPlaylists = await userTask
        recommendedArtists = await recsTask
        // Rapid tab nav cancels and re-fires `.task`; if cancelled
        // we leave `loading=true` so the next firing's body still
        // shows the spinner instead of momentarily rendering a
        // half-populated page.
        if Task.isCancelled { return }
        loading = false
    }

    private func createPlaylist(name: String, description: String?) async {
        do {
            let created = try await dataModel.createPlaylist(name: name, description: description)
            userPlaylists.insert(created, at: 0)
        } catch {
            log.warning("createPlaylist failed: \(error.localizedDescription)")
        }
    }

    private func startShuffle() async {
        guard !shuffleStarting else { return }
        shuffleStarting = true
        defer { shuffleStarting = false }
        do {
            let tracks = try await dataModel.libraryShuffle(limit: 200)
            let queued = tracks.map { makeQueuedTrack($0) }
            guard !queued.isEmpty else {
                log.info("libraryShuffle returned 0 tracks; nothing to play")
                return
            }
            audio.play(tracks: queued, startingAt: 0)
        } catch {
            log.warning("libraryShuffle failed: \(error.localizedDescription)")
        }
    }

    private func dismissRecommendation(_ rec: ApiRecommendedArtist) async {
        // Optimistic local removal — same UX as Recently Added.
        recommendedArtists.removeAll { $0.mbid == rec.mbid }
        do {
            try await dataModel.dismissRecommendation(mbid: rec.mbid)
        } catch {
            log.warning("dismissRecommendation failed: \(error.localizedDescription)")
            // Re-add on failure to keep local + server views in sync.
            recommendedArtists.append(rec)
        }
    }

    private func dismiss(_ album: ApiTitle) async {
        // Optimistic local removal so the card disappears immediately.
        recentlyAdded.removeAll { $0.id == album.id }
        do {
            try await dataModel.dismissHomeCarouselItem(
                titleId: album.id, carousel: .recentlyAddedAlbums)
        } catch {
            log.warning("dismissHomeCarouselItem failed: \(error.localizedDescription)")
            // Re-add on failure to keep local + server views in sync.
            // Sort by original order is a nice-to-have; for now just
            // append.
            recentlyAdded.append(album)
        }
    }

    /// Library-shuffle tracks carry their parent album's name and
    /// lead-artist credit on the wire (server populates `title_name`
    /// + `title_artist_name` for the standalone-track case). Pick
    /// per-track credit when the album has multiple artists
    /// (compilations); fall back to the album credit otherwise.
    private func makeQueuedTrack(_ t: ApiTrack) -> QueuedTrack {
        QueuedTrack(
            id: t.id,
            titleId: t.titleId,
            title: t.name,
            albumName: t.albumName ?? "",
            artistName: t.trackArtistNames.first ?? t.albumArtistName ?? "",
            trackNumber: t.trackNumber,
            discNumber: t.discNumber,
            durationSeconds: t.durationSeconds)
    }
}

/// Navigation marker for the `Browse Artists` link. ContentView's
/// destination handler routes this to `ArtistsView`.
struct BrowseArtistsRoute: Hashable {}

/// Navigation marker for the `See All` link on the Your Playlists
/// section. Routes to the dedicated `PlaylistsView`.
struct AllPlaylistsRoute: Hashable {}

/// Navigation route into a smart-playlist detail page. The key is
/// the stable string the server uses (`recently-added`, `most-played`,
/// etc.); we carry the display name so the destination can paint
/// its title bar before the detail fetch lands.
struct SmartPlaylistRoute: Hashable {
    let key: String
    let name: String
}
