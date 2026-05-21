import SwiftUI
import GRPCCore

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

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                shuffleHeroCard()
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
            }
            .padding()
        }
        .navigationTitle("Music")
        .sheet(isPresented: $showCreate) {
            CreatePlaylistSheet { name, description in
                await createPlaylist(name: name, description: description)
            }
        }
        .task { await load() }
        .refreshable { await load() }
    }

    // MARK: - Sections

    @ViewBuilder
    private func shuffleHeroCard() -> some View {
        Button {
            Task { await startShuffle() }
        } label: {
            HStack(spacing: 16) {
                Image(systemName: "shuffle")
                    .font(.system(size: 36, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 64, height: 64)
                    .background(LinearGradient(
                        colors: [.purple, .blue],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing))
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                VStack(alignment: .leading, spacing: 4) {
                    Text("Shuffle Library")
                        .font(.headline)
                    Text("Random tracks from your whole collection")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                Spacer()
                if shuffleStarting {
                    ProgressView()
                } else {
                    Image(systemName: "play.fill")
                        .font(.title3)
                        .foregroundStyle(.tint)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity)
            .background(.fill.quaternary)
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(shuffleStarting)
    }

    @ViewBuilder
    private func yourPlaylistsSection() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Your Playlists").font(.headline)
                Spacer()
                NavigationLink(value: AllPlaylistsRoute()) {
                    Text("See All").font(.subheadline)
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    // "+" card always first so creating a playlist is
                    // discoverable from the landing surface, not buried
                    // behind a See All tap. Hidden offline because
                    // playlist creation requires the server.
                    if dataModel.isOnline {
                    Button {
                        showCreate = true
                    } label: {
                        VStack(spacing: 8) {
                            ZStack {
                                LinearGradient(
                                    colors: [.gray.opacity(0.4), .gray.opacity(0.2)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing)
                                Image(systemName: "plus")
                                    .font(.system(size: 36, weight: .light))
                                    .foregroundStyle(.tint)
                            }
                            .frame(width: 130, height: 130)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            Text("New Playlist")
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(.tint)
                                .frame(maxWidth: 130, alignment: .leading)
                            Text(" ").font(.caption2)  // spacer to match other card heights
                        }
                        .frame(width: 130)
                    }
                    .buttonStyle(.plain)
                    }

                    ForEach(userPlaylists) { p in
                        NavigationLink(value: PlaylistRoute(id: p.id, name: p.name)) {
                            userPlaylistCard(p)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func userPlaylistCard(_ p: ApiPlaylistSummary) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Group {
                if let titleId = p.heroTitleId {
                    CachedImage(ref: .posterThumbnail(titleId: titleId.protoValue), cornerRadius: 8)
                } else {
                    LinearGradient(
                        colors: [.purple, .pink],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
            .frame(width: 130, height: 130)  // square — playlist hero aspect
            HStack(spacing: 4) {
                Text(p.name)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                if p.isPrivate {
                    Image(systemName: "lock.fill")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: 130, alignment: .leading)
            if let desc = p.description, !desc.isEmpty {
                Text(desc)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            } else {
                Text(" ").font(.caption2)
            }
        }
        .frame(width: 130)
    }

    @ViewBuilder
    private func smartPlaylistsSection() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Smart Playlists")
                .font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(smartPlaylists) { p in
                        NavigationLink(value: SmartPlaylistRoute(key: p.key, name: p.name)) {
                            smartPlaylistCard(p)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func smartPlaylistCard(_ p: ApiSmartPlaylistSummary) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Group {
                if let titleId = p.heroTitleId {
                    CachedImage(ref: .posterThumbnail(titleId: titleId.protoValue), cornerRadius: 8)
                } else {
                    LinearGradient(
                        colors: [.indigo, .teal],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
            .frame(width: 130, height: 130)  // square — album art aspect
            Text(p.name)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .frame(maxWidth: 130, alignment: .leading)
            Text("\(p.trackCount) track\(p.trackCount == 1 ? "" : "s")")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(width: 130)
    }

    @ViewBuilder
    private func recentlyAddedSection() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Recently Added")
                .font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(recentlyAdded) { album in
                        recentlyAddedCard(album)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func recentlyAddedCard(_ album: ApiTitle) -> some View {
        ZStack(alignment: .topTrailing) {
            NavigationLink(value: album) {
                VStack(alignment: .leading, spacing: 6) {
                    CachedImage(
                        ref: .posterThumbnail(titleId: album.id.protoValue),
                        cornerRadius: 8)
                        .frame(width: 130, height: 130)
                    Text(album.name)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                        .frame(maxWidth: 130, alignment: .leading)
                    if let year = album.year {
                        Text(String(year))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(width: 130)
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
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(6)
                    .background(.black.opacity(0.55))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .padding(6)
            .accessibilityLabel("Dismiss \(album.name)")
        }
    }

    @ViewBuilder
    private func forYouSection() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("For You")
                .font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(recommendedArtists) { rec in
                        forYouCard(rec)
                    }
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
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(6)
                    .background(.black.opacity(0.55))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .padding(6)
            .accessibilityLabel("Dismiss \(rec.name)")
        }
    }

    @ViewBuilder
    private func forYouCardBody(_ rec: ApiRecommendedArtist) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Group {
                if let rgid = rec.representativeReleaseGroupMbid, !rgid.isEmpty {
                    CachedImage(
                        ref: .coverArtArchiveReleaseGroup(releaseGroupId: rgid),
                        cornerRadius: 8)
                } else {
                    LinearGradient(
                        colors: [.orange, .pink],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay {
                            Image(systemName: "music.mic")
                                .font(.system(size: 36))
                                .foregroundStyle(.white.opacity(0.8))
                        }
                }
            }
            .frame(width: 130, height: 130)  // square — album cover aspect
            Text(rec.name)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .frame(maxWidth: 130, alignment: .leading)
            // Voters teaser: "Liked by X & Y" / "Liked by X, Y +N"
            // depending on count. Falls back to the representative
            // release title when the server didn't ship voters.
            Text(votersCaption(rec))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(maxWidth: 130, alignment: .leading)
        }
        .frame(width: 130)
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
            HStack {
                Image(systemName: "music.mic")
                    .foregroundStyle(.tint)
                Text("Browse Artists")
                    .font(.headline)
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(.tertiary)
                    .font(.caption)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 12)
            .background(.fill.quaternary)
            .clipShape(RoundedRectangle(cornerRadius: 12))
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
