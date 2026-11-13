import SwiftUI
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "ArtistDetailView")

/// Artist detail surface — audio analog to AuthorDetailView. Header
/// (square headshot or album-cover fallback), bio, owned albums grid
/// (square covers), GROUP/PERSON members links, and "Other Works"
/// discography from MusicBrainz with tap-to-wish hearts.
struct ArtistDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(AudioCacheManager.self) private var audioCache
    @Environment(\.marquee) private var layout
    let route: ArtistRoute

    @State private var detail: ApiArtistDetail?
    @State private var loading = true
    /// Set of release_group_ids whose wish-toggle is currently in flight.
    @State private var togglingWish: Set<String> = []
    /// Optimistic-update overlay. Maps release_group_id → wished?
    /// Avoids a full reload after every heart tap (matches the
    /// AuthorDetailView behaviour we shipped earlier).
    @State private var wishOverrides: [String: Bool] = [:]

    /// Square tiles at the spec's poster width and card gap.
    private var albumColumns: [GridItem] {
        layout.gridColumns
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmPage()
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: layout.sectionGap) {
                        header(detail)
                        if let bio = detail.biography, !bio.isEmpty {
                            biography(bio)
                        }
                        ownedAlbumsSection(detail.ownedAlbums)
                        if !detail.members.isEmpty {
                            membershipSection("Members", entries: detail.members)
                        }
                        if !detail.memberOf.isEmpty {
                            membershipSection("Member of", entries: detail.memberOf)
                        }
                        if !detail.otherWorks.isEmpty {
                            otherWorksSection(detail.otherWorks)
                        }
                    }
                    .padding(.horizontal, layout.pageX)
                    .padding(.vertical, 16)
                    .padding(.bottom, 48)
                }
                .mmPage()
            } else {
                ContentUnavailableView("Artist not found", systemImage: "music.mic")
                    .mmPage()
            }
        }
        .navigationTitle(route.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        loading = true
        do {
            detail = try await dataModel.artistDetail(id: route.id)
        } catch {
            log.warning("artistDetail failed: \(error.localizedDescription)")
        }
        loading = false
    }

    @ViewBuilder
    private func header(_ detail: ApiArtistDetail) -> some View {
        let artist = detail.artist
        let heroSide: CGFloat = layout.isWide ? 180 : 130
        HStack(alignment: .top, spacing: 16) {
            MMArtFrame {
                ZStack {
                    MMPlaceholderArt(name: artist.name, systemImage: "music.mic")
                    if let albumId = detail.ownedAlbums.first?.id {
                        CachedImage(
                            ref: .posterThumbnail(titleId: albumId.protoValue),
                            cornerRadius: 0,
                            transparentPlaceholder: true)
                    }
                    if artist.hasHeadshot {
                        CachedImage(
                            ref: .artistHeadshot(artistId: artist.id.protoValue),
                            cornerRadius: 0,
                            transparentPlaceholder: true)
                    }
                }
                // Square hero (1:1) — albums, not posters.
                .frame(width: heroSide, height: heroSide)
            }
            .frame(width: heroSide, height: heroSide)

            VStack(alignment: .leading, spacing: 6) {
                Text(artist.name)
                    .font(layout.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .lineLimit(3)
                    .minimumScaleFactor(0.7)
                HStack(spacing: 8) {
                    if let years = lifespanText(artist) {
                        Text(years)
                    }
                    Text(artistTypeLabel(artist.artistType))
                }
                .font(MarqueeTheme.Fonts.meta)
                .foregroundStyle(MarqueeTheme.Colors.text3)
                Spacer(minLength: 0)
            }
        }
    }

    private func lifespanText(_ artist: ApiArtist) -> String? {
        switch (artist.beginYear, artist.endYear) {
        case let (b?, e?): return "(\(b) – \(e))"
        case let (b?, nil): return "Since \(b)"
        default: return nil
        }
    }

    private func artistTypeLabel(_ type: MMArtistType) -> String {
        switch type {
        case .person: return "Solo Artist"
        case .group: return "Group"
        case .orchestra: return "Orchestra"
        case .choir: return "Choir"
        case .other, .unknown, .UNRECOGNIZED: return "Artist"
        }
    }

    @ViewBuilder
    private func biography(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("About")
                .mmSectionTitle()
            Text(text)
                .font(MarqueeTheme.Fonts.body)
                .foregroundStyle(MarqueeTheme.Colors.text2)
        }
    }

    /// Status across every owned album for the bulk action row.
    /// Recomputed on each render — AudioCacheManager's @Observable
    /// state flips this automatically as downloads complete.
    private func albumBulkStatus(_ albums: [ApiTitle]) -> BulkDownloadStatus {
        audioCache
            .bulkStatus(forAlbumTitleIds: albums.map { $0.id.protoValue })
            .asBulkDownloadStatus
    }

    @ViewBuilder
    private func ownedAlbumsSection(_ albums: [ApiTitle]) -> some View {
        if !albums.isEmpty {
            VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
                Text("Albums on your shelf")
                    .mmSectionTitle()

                let status = albumBulkStatus(albums)
                if status.completed < status.total {
                    BulkDownloadActionRow(
                        status: status,
                        noun: "album",
                        action: { Task { await startBulkAlbumDownload(albums) } })
                }

                LazyVGrid(columns: albumColumns, spacing: layout.sectionGap) {
                    ForEach(albums) { album in
                        // Album navigation routes via ApiTitle;
                        // ContentView dispatches isAlbum titles to
                        // AlbumDetailView.
                        NavigationLink(value: album) {
                            VStack(alignment: .leading, spacing: 0) {
                                MMPosterTile(
                                    name: album.name,
                                    ref: .posterThumbnail(titleId: album.id.protoValue),
                                    square: true,
                                    width: layout.gridCellWidth,
                                    placeholderIcon: "music.note")
                                .overlay(alignment: .bottomTrailing) {
                                    // Bottom-right corner badge, small
                                    // enough not to obscure the cover.
                                    // Same marker PosterCard uses.
                                    if audioCache.isDownloaded(titleId: album.id.protoValue) {
                                        MMArtBadge(systemImage: "arrow.down", label: "Downloaded")
                                    }
                                }
                                MMCaption(
                                    title: album.name,
                                    meta: album.year.map(String.init),
                                    width: layout.gridCellWidth)
                            }
                            .frame(width: layout.gridCellWidth, alignment: .topLeading)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func membershipSection(_ title: String, entries: [ApiArtistMember]) -> some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            Text(title)
                .mmSectionTitle()
            ForEach(entries) { entry in
                NavigationLink(value: ArtistRoute(id: entry.id, name: entry.name)) {
                    HStack(spacing: 10) {
                        Image(systemName: entry.artistType == .person ? "person.fill" : "person.3.fill")
                            .foregroundStyle(MarqueeTheme.Colors.text3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.name)
                                .font(MarqueeTheme.Fonts.control)
                                .foregroundStyle(MarqueeTheme.Colors.accent)
                            if let inst = entry.instruments {
                                Text(inst)
                                    .font(MarqueeTheme.Fonts.meta)
                                    .foregroundStyle(MarqueeTheme.Colors.text3)
                            }
                        }
                        Spacer()
                        if let years = membershipYears(entry) {
                            Text(years)
                                .font(MarqueeTheme.Fonts.meta)
                                .foregroundStyle(MarqueeTheme.Colors.text3)
                        }
                    }
                    .padding(.vertical, 6)
                }
                .buttonStyle(.plain)
                Divider().overlay(MarqueeTheme.Colors.hairline)
            }
        }
    }

    private func membershipYears(_ entry: ApiArtistMember) -> String? {
        switch (entry.beginYear, entry.endYear) {
        case let (b?, e?): return "\(b) – \(e)"
        case let (b?, nil): return "\(b) – "
        default: return nil
        }
    }

    @ViewBuilder
    private func otherWorksSection(_ entries: [ApiDiscographyEntry]) -> some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            HStack(alignment: .firstTextBaseline) {
                Text("Other Works")
                    .mmSectionTitle()
                Spacer()
                Text("Tap to wish")
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.text3)
            }
            LazyVGrid(columns: albumColumns, spacing: layout.sectionGap) {
                ForEach(entries) { entry in
                    discographyCard(entry)
                }
            }
        }
    }

    @ViewBuilder
    private func discographyCard(_ entry: ApiDiscographyEntry) -> some View {
        let inFlight = togglingWish.contains(entry.releaseGroupId)
        let wished = wishOverrides[entry.releaseGroupId] ?? entry.alreadyWished
        Button {
            Task { await toggleWish(entry, currentlyWished: wished) }
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                MMPosterTile(
                    name: entry.name,
                    ref: .coverArtArchiveReleaseGroup(releaseGroupId: entry.releaseGroupId),
                    square: true,
                    width: layout.gridCellWidth,
                    placeholderIcon: "music.note")
                    .overlay(alignment: .topTrailing) {
                        ZStack {
                            if inFlight {
                                ProgressView()
                                    .controlSize(.small)
                                    .padding(6)
                                    .background(MarqueeTheme.Colors.scrimStrong, in: Circle())
                            } else if wished {
                                Image(systemName: "heart.fill")
                                    .foregroundStyle(MarqueeTheme.Colors.pink)
                                    .padding(6)
                                    .background(MarqueeTheme.Colors.scrimStrong, in: Circle())
                            } else {
                                Image(systemName: "heart")
                                    .foregroundStyle(.white)
                                    .padding(6)
                                    .background(MarqueeTheme.Colors.scrim, in: Circle())
                            }
                        }
                        .padding(6)
                    }
                MMCaption(
                    title: entry.name,
                    meta: entry.year.map(String.init),
                    width: layout.gridCellWidth)
            }
            .frame(width: layout.gridCellWidth, alignment: .topLeading)
        }
        .buttonStyle(.plain)
        .disabled(inFlight)
    }

    /// Toggle the wishlist row for a discography entry. Optimistic
    /// flip — same pattern AuthorDetailView's bibliography uses.
    private func toggleWish(_ entry: ApiDiscographyEntry, currentlyWished: Bool) async {
        let mbid = entry.releaseGroupId
        guard !togglingWish.contains(mbid) else { return }
        togglingWish.insert(mbid)
        defer { togglingWish.remove(mbid) }

        wishOverrides[mbid] = !currentlyWished

        do {
            if currentlyWished {
                try await dataModel.removeAlbumWish(releaseGroupId: mbid)
            } else {
                let primary = detail?.artist.name
                try await dataModel.addAlbumWish(
                    releaseGroupId: mbid, title: entry.name, primaryArtist: primary)
            }
        } catch {
            log.warning("toggleWish failed for \(mbid): \(error.localizedDescription)")
            wishOverrides[mbid] = currentlyWished
        }
    }

    /// Fan out title-detail fetches for every owned album that
    /// isn't already cached or downloading, then kick off the
    /// per-album download. Concurrency-capped at 3 so a "Download
    /// all albums" tap on an artist with a deep catalog doesn't
    /// fire 40 parallel detail RPCs.
    private func startBulkAlbumDownload(_ albums: [ApiTitle]) async {
        let inFlight = Set(audioCache.activeDownloads.keys)
        let completed = Set(audioCache.downloads.map { $0.titleId })
        let needed = albums.filter { album in
            let id = album.id.protoValue
            return !completed.contains(id) && !inFlight.contains(id)
        }

        await withTaskGroup(of: Void.self) { group in
            var iterator = needed.makeIterator()
            // Prime three workers, then refill as each finishes.
            for _ in 0..<3 {
                guard let next = iterator.next() else { break }
                group.addTask { await fetchAndStartAlbum(next) }
            }
            for await _ in group {
                if let next = iterator.next() {
                    group.addTask { await fetchAndStartAlbum(next) }
                }
            }
        }
    }

    /// Single-album worker: fetch detail, hand to AudioCacheManager.
    /// Failures log + carry on; row's failed-state counter would
    /// surface anything mid-download, but a detail-fetch miss here
    /// just means that album doesn't show up in the bulk progress.
    private func fetchAndStartAlbum(_ album: ApiTitle) async {
        do {
            let detail = try await dataModel.titleDetail(id: album.id)
            guard let albumProto = detail.album else {
                log.warning("startBulkAlbumDownload: titleId=\(album.id.protoValue) had no album payload — skipped")
                return
            }
            audioCache.downloadAlbum(detail: detail, album: albumProto)
        } catch {
            log.warning("startBulkAlbumDownload: titleDetail failed for \(album.id.protoValue): \(error.localizedDescription)")
        }
    }
}
