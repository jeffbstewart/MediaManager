import SwiftUI

private let log = MMLogger(category: "ArtistDetailView")

/// Artist detail surface — audio analog to AuthorDetailView. Header
/// (square headshot or album-cover fallback), bio, owned albums grid
/// (square covers), GROUP/PERSON members links, and "Other Works"
/// discography from MusicBrainz with tap-to-wish hearts.
struct ArtistDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let route: ArtistRoute

    @State private var detail: ApiArtistDetail?
    @State private var loading = true
    /// Set of release_group_ids whose wish-toggle is currently in flight.
    @State private var togglingWish: Set<String> = []
    /// Optimistic-update overlay. Maps release_group_id → wished?
    /// Avoids a full reload after every heart tap (matches the
    /// AuthorDetailView behaviour we shipped earlier).
    @State private var wishOverrides: [String: Bool] = [:]

    private let albumColumns = [
        GridItem(.adaptive(minimum: 130), spacing: 12)
    ]

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
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
                    .padding()
                }
            } else {
                ContentUnavailableView("Artist not found", systemImage: "music.mic")
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
        HStack(alignment: .top, spacing: 16) {
            ZStack {
                if let albumId = detail.ownedAlbums.first?.id {
                    CachedImage(
                        ref: .posterThumbnail(titleId: albumId.protoValue),
                        cornerRadius: 8)
                } else {
                    Color.gray.opacity(0.2)
                }
                if artist.hasHeadshot {
                    CachedImage(
                        ref: .artistHeadshot(artistId: artist.id.protoValue),
                        cornerRadius: 8,
                        transparentPlaceholder: true)
                }
            }
            // Square hero (1:1) — albums, not posters.
            .frame(width: 130, height: 130)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 6) {
                Text(artist.name)
                    .font(.title2)
                    .fontWeight(.bold)
                if let years = lifespanText(artist) {
                    Text(years)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Text(artistTypeLabel(artist.artistType))
                    .font(.caption)
                    .foregroundStyle(.secondary)
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
        VStack(alignment: .leading, spacing: 6) {
            Text("About")
                .font(.headline)
            Text(text)
                .font(.body)
        }
    }

    @ViewBuilder
    private func ownedAlbumsSection(_ albums: [ApiTitle]) -> some View {
        if !albums.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Albums on your shelf")
                    .font(.headline)
                LazyVGrid(columns: albumColumns, spacing: 16) {
                    ForEach(albums) { album in
                        // Album navigation routes via ApiTitle (a future
                        // AlbumDetailView lands in Phase 3); for now
                        // ContentView will fall back to TitleDetailView
                        // for non-book / non-movie titles. That path
                        // will get a proper destination in Phase 3.
                        NavigationLink(value: album) {
                            VStack(alignment: .center, spacing: 6) {
                                CachedImage(
                                    ref: .posterThumbnail(titleId: album.id.protoValue),
                                    cornerRadius: 8)
                                    .frame(width: 130, height: 130)  // square
                                Text(album.name)
                                    .font(.caption)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.center)
                                if let year = album.year {
                                    Text(String(year))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .frame(width: 130)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func membershipSection(_ title: String, entries: [ApiArtistMember]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
            ForEach(entries) { entry in
                NavigationLink(value: ArtistRoute(id: entry.id, name: entry.name)) {
                    HStack(spacing: 8) {
                        Image(systemName: entry.artistType == .person ? "person.fill" : "person.3.fill")
                            .foregroundStyle(.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.name)
                                .foregroundStyle(.tint)
                            if let inst = entry.instruments {
                                Text(inst)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        if let years = membershipYears(entry) {
                            Text(years)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .buttonStyle(.plain)
                Divider()
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
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Other Works")
                    .font(.headline)
                Spacer()
                Text("Tap to wish")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            LazyVGrid(columns: albumColumns, spacing: 16) {
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
            VStack(spacing: 4) {
                CachedImage(
                    ref: .coverArtArchiveReleaseGroup(releaseGroupId: entry.releaseGroupId),
                    cornerRadius: 8)
                    .frame(width: 130, height: 130)  // square
                    .overlay(alignment: .topTrailing) {
                        ZStack {
                            if inFlight {
                                ProgressView()
                                    .controlSize(.small)
                                    .padding(6)
                                    .background(.black.opacity(0.5))
                                    .clipShape(Circle())
                            } else if wished {
                                Image(systemName: "heart.fill")
                                    .foregroundStyle(.red)
                                    .padding(6)
                                    .background(.black.opacity(0.5))
                                    .clipShape(Circle())
                            } else {
                                Image(systemName: "heart")
                                    .foregroundStyle(.white)
                                    .padding(6)
                                    .background(.black.opacity(0.35))
                                    .clipShape(Circle())
                            }
                        }
                        .padding(4)
                    }
                Text(entry.name)
                    .font(.caption)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .frame(width: 130)
                if let year = entry.year {
                    Text(String(year))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
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
}
