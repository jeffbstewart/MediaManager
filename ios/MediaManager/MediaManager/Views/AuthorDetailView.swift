import SwiftUI
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "AuthorDetailView")

/// Author detail: headshot + bio + owned books + bibliography. Owned
/// books group by series so multi-book series collapse to a single
/// "Foundation Series — 3 owned" entry that drills into
/// `BookSeriesDetailView`. Standalone books navigate straight into the
/// existing `TitleDetailView`. The "Other Works" section lists
/// OpenLibrary bibliography entries the user doesn't yet own.
struct AuthorDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(BookCacheManager.self) private var bookCache
    let route: AuthorRoute

    @State private var detail: ApiAuthorDetail?
    @State private var loading = true
    @State private var togglingHidden = false
    /// Set of OL work ids whose wish-toggle is currently in flight.
    /// Drives a per-card disabled / spinner state so the user can't
    /// double-tap and the heart icon appears to "flip" while the
    /// server round-trip is happening.
    @State private var togglingWish: Set<String> = []
    /// Optimistic-update overlay. Maps OL work id → whether the user
    /// has wished it locally regardless of what the server-known
    /// `alreadyWished` says. Avoids a full page reload after every
    /// toggle (the server canonicalises edition collapse, but we
    /// don't need to wait for that to flip the heart).
    @State private var wishOverrides: [String: Bool] = [:]

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

    private func bookBulkStatus(_ books: [ApiTitle]) -> BulkDownloadStatus {
        bookCache
            .bulkStatus(forTitleIds: books.map { $0.id.protoValue })
            .asBulkDownloadStatus
    }

    @ViewBuilder
    private func ownedBooksSection(_ books: [ApiTitle]) -> some View {
        if books.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text("Books on your shelf")
                    .font(.headline)

                let status = bookBulkStatus(books)
                if status.completed < status.total {
                    BulkDownloadActionRow(
                        status: status,
                        noun: "book",
                        action: { Task { await startBulkBookDownload(books) } })
                }

                LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 12)], spacing: 16) {
                    ForEach(books) { book in
                        NavigationLink(value: book) {
                            VStack(spacing: 4) {
                                BookCoverView(
                                    ref: .posterThumbnail(titleId: book.id.protoValue),
                                    seed: book.name)
                                    .frame(width: 110, height: 165)
                                    .overlay(alignment: .bottomTrailing) {
                                        if bookCache.offlineTitleIds.contains(book.id.protoValue) {
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
            HStack {
                Text("Other Works")
                    .font(.headline)
                Spacer()
                Text("Tap to wish")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 12)], spacing: 16) {
                ForEach(entries) { entry in
                    bibliographyCard(entry)
                }
            }
        }
    }

    @ViewBuilder
    private func bibliographyCard(_ entry: ApiBibliographyEntry) -> some View {
        let inFlight = togglingWish.contains(entry.openLibraryWorkId)
        let wished = wishOverrides[entry.openLibraryWorkId] ?? entry.alreadyWished
        Button {
            Task { await toggleWish(entry, currentlyWished: wished) }
        } label: {
            VStack(spacing: 4) {
                BookCoverView(
                    ref: .openlibraryCover(workId: entry.openLibraryWorkId),
                    seed: entry.name)
                    .frame(width: 110, height: 165)
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
                    .frame(width: 110)
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

    /// Toggle the wishlist row for an OL bibliography entry.
    /// Optimistic: flip `wishOverrides` immediately so the heart
    /// updates without a reload. On error the override is reverted
    /// so the visible state matches the server again.
    private func toggleWish(_ entry: ApiBibliographyEntry, currentlyWished: Bool) async {
        let workId = entry.openLibraryWorkId
        guard !togglingWish.contains(workId) else { return }
        togglingWish.insert(workId)
        defer { togglingWish.remove(workId) }

        // Optimistic flip. If the API call fails we'll revert below.
        wishOverrides[workId] = !currentlyWished

        do {
            if currentlyWished {
                try await dataModel.removeBookWish(olWorkId: workId)
            } else {
                let authorName = detail?.author.name
                try await dataModel.addBookWish(
                    olWorkId: workId, title: entry.name, author: authorName)
            }
        } catch {
            log.warning("toggleWish failed for \(workId): \(error.localizedDescription)")
            wishOverrides[workId] = currentlyWished
        }
    }

    private func lifespanText(_ author: ApiAuthor) -> String? {
        switch (author.birthYear, author.deathYear) {
        case let (b?, d?): return "(\(b) – \(d))"
        case let (b?, nil): return "Born \(b)"
        default: return nil
        }
    }

    /// Fan-out: fetch each book's detail to find its downloadable
    /// EPUB edition (the format BookDetailView's own download
    /// button targets), then start the per-book download. Skip
    /// books already downloaded or already in flight. Concurrency
    /// cap of 3 detail fetches at a time.
    private func startBulkBookDownload(_ books: [ApiTitle]) async {
        let completed = bookCache.offlineTitleIds
        let inFlight = Set(bookCache.activeDownloads.values.map { $0.titleId })
        let needed = books.filter { book in
            let id = book.id.protoValue
            return !completed.contains(id) && !inFlight.contains(id)
        }

        await withTaskGroup(of: Void.self) { group in
            var iterator = needed.makeIterator()
            for _ in 0..<3 {
                guard let next = iterator.next() else { break }
                group.addTask { await fetchAndStartBook(next) }
            }
            for await _ in group {
                if let next = iterator.next() {
                    group.addTask { await fetchAndStartBook(next) }
                }
            }
        }
    }

    private func fetchAndStartBook(_ book: ApiTitle) async {
        do {
            let detail = try await dataModel.titleDetail(id: book.id)
            let editions = detail.book?.editions ?? []
            guard let epub = editions.first(where: { $0.downloadable && $0.format == .ebookEpub }) else {
                log.info("startBulkBookDownload: titleId=\(book.id.protoValue) has no downloadable EPUB — skipped")
                return
            }
            // Persist the title detail before kicking off so the
            // bulk-downloaded book reads offline without a follow-up
            // tap on BookDetailView (which would re-fetch online).
            bookCache.cacheTitleDetail(detail)
            try bookCache.startDownload(
                mediaItemId: epub.id,
                titleId: book.id.protoValue,
                titleName: detail.name,
                authorName: detail.authorName ?? "Unknown")
        } catch {
            log.warning("startBulkBookDownload: failed for titleId=\(book.id.protoValue): \(error.localizedDescription)")
        }
    }
}
