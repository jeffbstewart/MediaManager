import SwiftUI
import GRPCCore

private let log = MMLogger(category: "ArtistsView")

/// Top-level artists browser, audio-side analog to AuthorsView. Sorts
/// default to ALBUMS (matching the server-side default); the grid
/// pages 60 cards at a time off `ArtistService.ListArtists` with
/// `playable_only=true` so users only see artists with at least one
/// playable owned album.
struct ArtistsView: View {
    @Environment(OnlineDataModel.self) private var dataModel

    @State private var artists: [ApiArtistListItem] = []
    @State private var page = 1
    @State private var totalPages = 0
    /// Same explicit-phase model as AuthorsView — the (loading: Bool,
    /// items: []) pair used to flash the empty / failure states for
    /// a frame on first navigation; the enum makes that impossible.
    @State private var phase: LoadPhase = .initial
    @State private var sort: ArtistSort = .albums
    @State private var query = ""

    enum LoadPhase: Equatable {
        case initial
        case loading
        case empty
        case loaded
        case failed(String)
    }

    private let columns = [
        GridItem(.adaptive(minimum: 130), spacing: 12)
    ]

    var body: some View {
        Group {
            switch phase {
            case .initial, .loading:
                ProgressView("Loading...")
            case .empty:
                ContentUnavailableView("No artists yet", systemImage: "music.mic",
                    description: Text("Add a CD by scanning its UPC, or import audio files to populate your library."))
            case .failed(let message):
                ContentUnavailableView("Couldn't load artists",
                    systemImage: "exclamationmark.triangle",
                    description: Text(message))
            case .loaded:
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(artists) { artist in
                            NavigationLink(value: ArtistRoute(id: artist.id, name: artist.name)) {
                                ArtistCard(artist: artist)
                            }
                            .buttonStyle(.plain)
                        }

                        if page < totalPages {
                            ProgressView()
                                .task { await loadMore() }
                        }
                    }
                    .padding()
                }
            }
        }
        .navigationTitle("Artists")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Sort", selection: $sort) {
                        ForEach(ArtistSort.allCases) { s in
                            Text(s.label).tag(s)
                        }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
            }
        }
        .searchable(text: $query, prompt: "Search artists")
        .task(id: LoadKey(sort: sort, query: query)) { await reload() }
        .refreshable { await reload() }
    }

    private struct LoadKey: Hashable {
        let sort: ArtistSort
        let query: String
    }

    private func reload() async {
        phase = .loading
        artists = []
        page = 1
        totalPages = 0
        await loadPage()
    }

    private func loadPage() async {
        if artists.isEmpty { phase = .loading }
        do {
            let response = try await dataModel.artists(
                page: page,
                sort: sort,
                query: query.isEmpty ? nil : query)
            artists.append(contentsOf: response.artists)
            totalPages = response.totalPages
            phase = artists.isEmpty ? .empty : .loaded
        } catch {
            // Same `.task` cancellation guard AuthorsView uses —
            // SwiftUI re-mounts the view around column transitions
            // and we don't want a fake-failure flash before the
            // successor task lands `.loaded`.
            if Task.isCancelled || isCancellationError(error) { return }
            log.warning("loadPage failed: \(error.localizedDescription)")
            if artists.isEmpty {
                phase = .failed(error.localizedDescription)
            }
        }
    }

    private func isCancellationError(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let rpcError = error as? RPCError, rpcError.code == .cancelled { return true }
        let s = error.localizedDescription.lowercased()
        return s.contains("cancelled") || s.contains("canceled")
    }

    private func loadMore() async {
        page += 1
        await loadPage()
    }
}

/// Artist card. Square hero (album cover aspect), name + owned-album
/// count. Layered fallback like the author card: the owned-album
/// poster sits behind the headshot, so a missing headshot reveals
/// the album, and a missing album reveals a synthesised swatch.
private struct ArtistCard: View {
    @Environment(AudioCacheManager.self) private var audioCache
    let artist: ApiArtistListItem

    var body: some View {
        VStack(alignment: .center, spacing: 6) {
            heroImage
                // Square — matches the album-cover aspect, different
                // from the 2:3 movie/book poster aspect.
                .frame(width: 130, height: 130)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(alignment: .bottomTrailing) {
                    // "Any album by this artist is offline" — same
                    // looser semantics the user accepted for TV
                    // shows. Driven by the artistIds captured on
                    // each DownloadedAlbum at cache-time.
                    if audioCache.offlineArtistIds.contains(artist.id.protoValue) {
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

            Text(artist.name)
                .font(.caption)
                .fontWeight(.medium)
                .lineLimit(2)
                .multilineTextAlignment(.center)

            Text("\(artist.ownedAlbumCount) album\(artist.ownedAlbumCount == 1 ? "" : "s")")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(width: 130)
    }

    @ViewBuilder
    private var heroImage: some View {
        ZStack {
            // Fallback layer: owned album poster (square) when the
            // artist has no real headshot. CachedImage handles the
            // synthesised-swatch fallback when neither layer
            // resolves — keyed off the artist name for stability.
            if let albumId = artist.fallbackAlbumTitleId {
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
    }
}

/// Navigation route into ArtistDetailView. Carrying the name in the
/// route lets the destination paint its title bar before the detail
/// fetch resolves.
struct ArtistRoute: Hashable {
    let id: ArtistID
    let name: String
}
