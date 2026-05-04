import SwiftUI

private let log = MMLogger(category: "AuthorDetailView")

/// Author detail: headshot + bio + owned books + bibliography. Owned
/// books group by series so multi-book series collapse to a single
/// "Foundation Series — 3 owned" entry that drills into
/// `BookSeriesDetailView`. Standalone books navigate straight into the
/// existing `TitleDetailView`. The "Other Works" section lists
/// OpenLibrary bibliography entries the user doesn't yet own.
struct AuthorDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let route: AuthorRoute

    @State private var detail: ApiAuthorDetail?
    @State private var loading = true
    @State private var togglingHidden = false

    private var isAdmin: Bool { dataModel.userInfo?.isAdmin == true }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        header(detail)
                        if let bio = detail.author.biography, !bio.isEmpty {
                            biography(bio)
                        }
                        ownedBooksSection(detail.ownedBooks)
                        if !detail.otherWorks.isEmpty {
                            otherWorksSection(detail.otherWorks)
                        }
                        if isAdmin {
                            hideButton(detail.author)
                        }
                    }
                    .padding()
                }
            } else {
                ContentUnavailableView("Author not found", systemImage: "person.fill")
            }
        }
        .navigationTitle(route.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    /// Admin escape-hatch toggle. Real labels reflect the current
    /// `author.hidden`: tap "Hide" on a visible author drops them out
    /// of the default grid; tap "Unhide" on a hidden one to restore.
    /// Reachable for visible authors via normal navigation, and for
    /// hidden ones via the "Hidden Authors" filter on AuthorsView.
    @ViewBuilder
    private func hideButton(_ author: ApiAuthor) -> some View {
        Button(role: author.hidden ? nil : .destructive) {
            Task { await toggleHidden(author) }
        } label: {
            Label(
                author.hidden ? "Unhide Author" : "Hide Author",
                systemImage: author.hidden ? "eye" : "eye.slash"
            )
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(togglingHidden)
    }

    private func toggleHidden(_ author: ApiAuthor) async {
        togglingHidden = true
        do {
            try await dataModel.setAuthorHidden(id: author.id, hidden: !author.hidden)
            // Re-fetch so the button label flips and any other view
            // observing this author reflects the new state.
            await load()
        } catch {
            log.warning("setAuthorHidden failed: \(error.localizedDescription)")
        }
        togglingHidden = false
    }

    private func load() async {
        loading = true
        do {
            detail = try await dataModel.authorDetail(id: route.id)
        } catch {
            log.warning("authorDetail failed: \(error.localizedDescription)")
        }
        loading = false
    }

    @ViewBuilder
    private func header(_ detail: ApiAuthorDetail) -> some View {
        HStack(alignment: .top, spacing: 16) {
            // Layered hero: same fallback strategy as AuthorsView's grid
            // card. has_headshot is permissive on the server, so a 404
            // on the headshot fetch should let the first owned book's
            // cover show through; a missing book cover falls through to
            // a synthesised swatch keyed off the author's name.
            ZStack {
                BookCoverView(
                    ref: detail.ownedBooks.first.map {
                        .posterThumbnail(titleId: $0.id.protoValue)
                    },
                    seed: detail.author.name,
                    cornerRadius: 8)
                if detail.author.hasHeadshot {
                    CachedImage(
                        ref: .authorHeadshot(authorId: detail.author.id.protoValue),
                        cornerRadius: 8,
                        transparentPlaceholder: true)
                }
            }
            .frame(width: 120, height: 160)

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.author.name)
                    .font(.title2)
                    .fontWeight(.bold)
                if let lifespan = lifespanText(detail.author) {
                    Text(lifespan)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Text("\(detail.ownedBooks.count) owned book\(detail.ownedBooks.count == 1 ? "" : "s")")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            }
        }
    }

    @ViewBuilder
    private func biography(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("About")
                .font(.headline)
            Text(text)
                .font(.body)
                .foregroundStyle(.primary)
        }
    }

    @ViewBuilder
    private func ownedBooksSection(_ books: [ApiTitle]) -> some View {
        if books.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text("Books on your shelf")
                    .font(.headline)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 12)], spacing: 16) {
                    ForEach(books) { book in
                        NavigationLink(value: book) {
                            VStack(spacing: 4) {
                                BookCoverView(
                                    ref: .posterThumbnail(titleId: book.id.protoValue),
                                    seed: book.name)
                                    .frame(width: 110, height: 165)
                                Text(book.name)
                                    .font(.caption)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.center)
                                    .frame(width: 110)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func otherWorksSection(_ entries: [ApiBibliographyEntry]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Other Works")
                .font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 12)], spacing: 16) {
                ForEach(entries) { entry in
                    VStack(spacing: 4) {
                        BookCoverView(
                            ref: .openlibraryCover(workId: entry.openLibraryWorkId),
                            seed: entry.name)
                            .frame(width: 110, height: 165)
                            .overlay(alignment: .topTrailing) {
                                if entry.alreadyWished {
                                    Image(systemName: "heart.fill")
                                        .foregroundStyle(.red)
                                        .padding(6)
                                        .background(.black.opacity(0.5))
                                        .clipShape(Circle())
                                        .padding(4)
                                }
                            }
                        Text(entry.name)
                            .font(.caption)
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                            .frame(width: 110)
                        if let year = entry.year {
                            Text(String(year))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    private func lifespanText(_ author: ApiAuthor) -> String? {
        switch (author.birthYear, author.deathYear) {
        case let (b?, d?): return "(\(b) – \(d))"
        case let (b?, nil): return "Born \(b)"
        default: return nil
        }
    }
}
