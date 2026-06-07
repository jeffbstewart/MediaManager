import SwiftUI
import GRPCCore

private let log = MMLogger(category: "AuthorsView")

/// Top-level authors browser, mirroring the web app's /content/books grid.
/// Sort defaults to NAME (matching the server-side default); the grid
/// pages 60 cards at a time off `ArtistService.ListAuthors`.
struct AuthorsView: View {
    @Environment(OnlineDataModel.self) private var dataModel

    @State private var authors: [ApiAuthorListItem] = []
    @State private var page = 1
    @State private var totalPages = 0
    /// Re-entrancy guard for the infinite-scroll trigger — see
    /// ArtistsView for the rationale.
    @State private var isLoadingMore = false
    /// Three explicit phases — the previous (loading: Bool, authors: [])
    /// pair allowed a sliver where loading was momentarily false with an
    /// empty list and the body briefly painted the "No authors yet"
    /// empty state. With an enum, the *only* path to `.empty` is via
    /// `loadPage()`'s post-fetch branch when the response was actually
    /// empty — there's no longer a starting position that looks like
    /// "we tried and got nothing".
    @State private var phase: LoadPhase = .initial
    @State private var sort: AuthorSort = .name
    @State private var query = ""

    enum LoadPhase: Equatable {
        case initial      // never attempted — show spinner
        case loading      // refresh in flight — show spinner
        case empty        // attempt completed with no results — show empty state
        case loaded       // results in hand — show grid
        case failed(String)
    }
    /// Admin escape-hatch view. When true the grid shows authors with
    /// `hidden=true` so an admin can navigate to one and unhide via
    /// `AuthorDetailView`. Only togglable for admins; non-admins see
    /// the default view regardless.
    @State private var hiddenOnly = false

    private var isAdmin: Bool { dataModel.userInfo?.isAdmin == true }

    private let columns = [
        GridItem(.adaptive(minimum: 130), spacing: 12)
    ]

    var body: some View {
        Group {
            switch phase {
            case .initial, .loading:
                ProgressView("Loading...")
            case .empty:
                ContentUnavailableView("No authors yet", systemImage: "books.vertical",
                    description: Text("Scan a book by ISBN or import an ebook to populate your library."))
            case .failed(let message):
                ContentUnavailableView("Couldn't load authors",
                    systemImage: "exclamationmark.triangle",
                    description: Text(message))
            case .loaded:
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(authors) { author in
                            NavigationLink(value: AuthorRoute(id: author.id, name: author.name)) {
                                AuthorCard(author: author)
                            }
                            .buttonStyle(.plain)
                        }

                        if page < totalPages {
                            ProgressView()
                                .onAppear {
                                    guard !isLoadingMore else { return }
                                    Task { await loadMore() }
                                }
                        }
                    }
                    .padding()
                }
            }
        }
        .navigationTitle(hiddenOnly ? "Hidden Authors" : "Authors")
        .toolbar {
            // Admin-only filter toggle: switch between visible authors
            // (default) and hidden authors (lets admin find a row and
            // tap Unhide on the detail page). Hidden under a Menu so
            // it's discoverable but doesn't take up a top-bar slot.
            if isAdmin {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Visibility", selection: $hiddenOnly) {
                            Label("Visible authors", systemImage: "eye").tag(false)
                            Label("Hidden authors", systemImage: "eye.slash").tag(true)
                        }
                    } label: {
                        Image(systemName: hiddenOnly ? "eye.slash" : "eye")
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Sort", selection: $sort) {
                        ForEach(AuthorSort.allCases) { s in
                            Text(s.label).tag(s)
                        }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
            }
        }
        // Tried pinning search via an inner `safeAreaInset(.bottom)`
        // so it'd always be in reach, but that nested inset (search
        // bar inside ContentView's mini-player inset) caused the
        // mini-player to grow to roughly half the screen while
        // AuthorsView fetched its initial data. The system
        // `.searchable` placement is stable; the column-level
        // mini-player inset (in ContentView) keeps it from being
        // visually covered. Suppressed offline — the offline author
        // list is small enough to scroll.
        .searchableIfOnline(
            text: $query,
            isOnline: dataModel.isOnline,
            prompt: "Search authors")
        .onChange(of: dataModel.isOnline) { _, newValue in
            if !newValue { query = "" }
        }
        // Single load trigger keyed on (sort, query, hiddenOnly). Two
        // parallel .task(id:) modifiers used to race and cancel each
        // other — keeping it consolidated avoids the partial-load bug.
        .task(id: LoadKey(sort: sort, query: query, hiddenOnly: hiddenOnly)) { await reload() }
        .refreshable { await reload() }
    }

    private struct LoadKey: Hashable {
        let sort: AuthorSort
        let query: String
        let hiddenOnly: Bool
    }

    private func reload() async {
        phase = .loading
        authors = []
        page = 1
        totalPages = 0
        await loadPage()
    }

    private func loadPage() async {
        // First-page fetches go to `.loading`; pagination from a
        // populated grid keeps `.loaded` so we don't dump the user
        // back to a spinner mid-scroll.
        if authors.isEmpty { phase = .loading }
        do {
            let response = try await dataModel.authors(
                page: page,
                sort: sort,
                query: query.isEmpty ? nil : query,
                hiddenOnly: hiddenOnly)
            authors.append(contentsOf: response.authors)
            totalPages = response.totalPages
            phase = authors.isEmpty ? .empty : .loaded
        } catch {
            // SwiftUI re-mounts the view around split-view column
            // transitions and cancels in-flight `.task`s. The
            // resulting CANCELLED gRPC error isn't a real failure —
            // the successor mount fires its own task immediately and
            // lands `.loaded`. Painting `.failed` here would just
            // flash a fake error before the real result arrives, so
            // keep the current phase and let the next task take over.
            if Task.isCancelled || isCancellationError(error) { return }
            log.warning("loadPage failed: \(error.localizedDescription)")
            if authors.isEmpty {
                phase = .failed(error.localizedDescription)
            }
        }
    }

    /// gRPC-Swift surfaces task-cancellation as `RPCError(code: .cancelled)`,
    /// not Swift's `CancellationError`. Match either shape so we don't
    /// paint a fake failure when the view is just being torn down.
    private func isCancellationError(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let rpcError = error as? RPCError, rpcError.code == .cancelled { return true }
        // Some error wrappers stringify the gRPC code into the
        // localizedDescription — match that as a last resort.
        let s = error.localizedDescription.lowercased()
        return s.contains("cancelled") || s.contains("canceled")
    }

    private func loadMore() async {
        guard !isLoadingMore else { return }
        isLoadingMore = true
        defer { isLoadingMore = false }
        page += 1
        await loadPage()
    }
}

/// Author exploration card — headshot when available, falls back to a
/// representative book cover for authors we have books for but no
/// Wikipedia thumbnail. Layout mirrors PosterCard's footprint so the
/// authors / movies / tv grids feel like the same surface.
private struct AuthorCard: View {
    let author: ApiAuthorListItem

    var body: some View {
        VStack(alignment: .center, spacing: 6) {
            heroImage
                .frame(width: 120, height: 180)
                .clipShape(RoundedRectangle(cornerRadius: 8))

            Text(author.name)
                .font(.caption)
                .fontWeight(.medium)
                .lineLimit(2)
                .multilineTextAlignment(.center)

            Text("\(author.ownedBookCount) book\(author.ownedBookCount == 1 ? "" : "s")")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(width: 120)
    }

    @ViewBuilder
    private var heroImage: some View {
        // Layered fallback: the book cover (or synthesised colour
        // swatch) sits behind the headshot. The server's `has_headshot`
        // is permissive — it returns true whenever the author has an
        // `open_library_author_id`, even when OL has no actual photo,
        // so the headshot fetch can 404. Layering means a missing
        // headshot reveals the book cover beneath, and a missing book
        // cover reveals the synthesised swatch keyed off the author's
        // name.
        ZStack {
            BookCoverView(
                ref: author.fallbackBookTitleId.map {
                    .posterThumbnail(titleId: $0.protoValue)
                },
                seed: author.name,
                cornerRadius: 8)
            if author.hasHeadshot {
                CachedImage(
                    ref: .authorHeadshot(authorId: author.id.protoValue),
                    cornerRadius: 8,
                    transparentPlaceholder: true)
            }
        }
    }
}

/// Navigation route into `AuthorDetailView`. Carrying the name in the
/// route lets the destination render its title bar before the detail
/// fetch resolves.
struct AuthorRoute: Hashable {
    let id: AuthorID
    let name: String
}
