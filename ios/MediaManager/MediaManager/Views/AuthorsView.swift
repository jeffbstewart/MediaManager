import SwiftUI

private let log = MMLogger(category: "AuthorsView")

/// Top-level authors browser, mirroring the web app's /content/books grid.
/// Sort defaults to NAME (matching the server-side default); the grid
/// pages 60 cards at a time off `ArtistService.ListAuthors`.
struct AuthorsView: View {
    @Environment(OnlineDataModel.self) private var dataModel

    @State private var authors: [ApiAuthorListItem] = []
    @State private var page = 1
    @State private var totalPages = 0
    @State private var loading = true
    @State private var sort: AuthorSort = .name
    @State private var query = ""
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
            if loading && authors.isEmpty {
                ProgressView("Loading...")
            } else if authors.isEmpty {
                ContentUnavailableView("No authors yet", systemImage: "books.vertical",
                    description: Text("Scan a book by ISBN or import an ebook to populate your library."))
            } else {
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
                                .task { await loadMore() }
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
        .searchable(text: $query, prompt: "Search authors")
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
        authors = []
        page = 1
        totalPages = 0
        await loadPage()
    }

    private func loadPage() async {
        loading = true
        do {
            let response = try await dataModel.authors(
                page: page,
                sort: sort,
                query: query.isEmpty ? nil : query,
                hiddenOnly: hiddenOnly)
            authors.append(contentsOf: response.authors)
            totalPages = response.totalPages
        } catch {
            log.warning("loadPage failed: \(error.localizedDescription)")
        }
        loading = false
    }

    private func loadMore() async {
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
