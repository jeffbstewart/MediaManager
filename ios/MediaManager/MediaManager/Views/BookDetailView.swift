import SwiftUI
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "BookDetailView")

/// Per-book detail page. Shows the cover, primary author, year, series
/// link, description, edition list, and a Read button (currently
/// disabled — wired up when the WKWebView reader lands). Distinct from
/// `TitleDetailView` because nothing about books needs the movie
/// vocabulary (Play, Seasons & Episodes, transcoding).
struct BookDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(BookCacheManager.self) private var bookCache
    let titleId: TitleID

    @State private var detail: ApiTitleDetail?
    @State private var loading = true
    /// Set to the mediaItemId we're prompting to remove. nil = sheet
    /// closed. Drives the SwiftUI `confirmationDialog` so the
    /// "Downloaded ✓" tap → "Remove from Device?" prompt is
    /// discoverable rather than buried in a long-press.
    @State private var pendingRemoval: Int64? = nil
    /// Edition currently being edited via the admin sheet. nil =
    /// sheet closed. Only set by admin paths — non-admin users
    /// never see the pencil button that opens it.
    @State private var editingEdition: ApiBookEdition? = nil

    private var isAdmin: Bool { dataModel.userInfo?.isAdmin == true }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                content(detail)
            } else {
                ContentUnavailableView("Book not found", systemImage: "books.vertical")
            }
        }
        .navigationTitle(detail?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        loading = true
        do {
            let fresh = try await dataModel.titleDetail(id: titleId)
            detail = fresh
            // If any edition of this title is already downloaded,
            // mirror the fresh detail proto into BookCacheManager
            // so the Books / Authors offline surfaces can find it.
            // Older downloads (predating the cacheTitleDetail
            // wiring in startDownload) wouldn't otherwise heal —
            // the user's first online view of the book repopulates
            // the cache for them.
            let mediaItemIds = (fresh.book?.editions ?? []).map { $0.id }
            if mediaItemIds.contains(where: { bookCache.isDownloaded($0) }) {
                bookCache.cacheTitleDetail(fresh)
            }
        } catch {
            log.warning("titleDetail failed: \(error.localizedDescription)")
        }
        loading = false
    }

    @ViewBuilder
    private func content(_ detail: ApiTitleDetail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                hero(detail)
                titleBlock(detail)
                readDownloadRow(detail)
                if let desc = detail.description, !desc.isEmpty {
                    descriptionBlock(desc)
                }
                if let book = detail.book, !book.editions.isEmpty {
                    editionsBlock(book.editions)
                }
            }
            .padding()
        }
    }

    @ViewBuilder
    private func hero(_ detail: ApiTitleDetail) -> some View {
        HStack(alignment: .top, spacing: 16) {
            BookCoverView(
                ref: .posterThumbnail(titleId: detail.id.protoValue),
                seed: detail.name,
                cornerRadius: 8)
                .frame(width: 130, height: 195)
                .overlay(alignment: .bottomTrailing) {
                    // Tile-variant badge — any cached edition (EPUB
                    // or PDF) of this book counts as "available
                    // offline". Per-edition status lives in
                    // editionsBlock below for finer-grained control.
                    if bookCache.offlineTitleIds.contains(detail.id.protoValue) {
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

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.name)
                    .font(.title2)
                    .fontWeight(.bold)

                if let series = detail.book?.bookSeries {
                    NavigationLink(value: BookSeriesRoute(id: series.id, name: series.name)) {
                        HStack(spacing: 4) {
                            if let n = series.number {
                                Text("#\(n)")
                            }
                            Text(series.name)
                        }
                        .font(.subheadline)
                        .foregroundStyle(.tint)
                    }
                    .buttonStyle(.plain)
                }

                if let author = detail.authorName {
                    Text(author)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 8) {
                    if let year = detail.year ?? detail.book?.firstPublicationYear {
                        Text(String(year))
                    }
                    if let pages = detail.book?.pageCount {
                        Text("•")
                        Text("\(pages) pages")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)

                Spacer(minLength: 0)
            }
        }
    }

    @ViewBuilder
    private func titleBlock(_ detail: ApiTitleDetail) -> some View {
        if let progress = detail.book?.readingProgress, progress.fraction > 0 {
            // Reading-progress chip: signal that this book has a saved
            // position. Tappable Read button below picks it up once the
            // reader lands.
            HStack(spacing: 6) {
                Image(systemName: "bookmark.fill")
                    .foregroundStyle(.tint)
                Text("\(Int(progress.fraction * 100))% read")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// Read + Download buttons. Read is the primary action (always
    /// shown, disabled when no readable edition exists); Download is
    /// the offline-pin affordance and only shows when the book has
    /// a downloadable EPUB. Sits in an HStack so the two share the
    /// row equally — discoverable from a single screen of detail
    /// without scrolling, and explicit about both capabilities.
    @ViewBuilder
    private func readDownloadRow(_ detail: ApiTitleDetail) -> some View {
        let epub = epubEdition(detail.book?.editions ?? [])
        HStack(spacing: 12) {
            readButton(detail, epub: epub)
            if let epub {
                downloadButton(detail, epub: epub)
            }
        }
    }

    /// Read button. Routes to `BookReaderView` when an EPUB edition
    /// exists; disabled when not. PDF and audiobook formats stay
    /// unsupported in v1 — the reader is EPUB-only.
    @ViewBuilder
    private func readButton(_ detail: ApiTitleDetail, epub: ApiBookEdition?) -> some View {
        if let epub {
            NavigationLink(value: BookReaderRoute(
                mediaItemId: epub.id,
                titleName: detail.name)) {
                Label("Read", systemImage: "book")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        } else {
            Button {
                // No-op: no readable edition (physical only, or only
                // PDF / audiobook which v1 doesn't render).
            } label: {
                Label("Read", systemImage: "book")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(true)
            .opacity(0.4)
        }
    }

    /// Download button. Three visual states driven by `BookCacheManager`:
    ///   - **idle / not downloaded** → "Download" (cloud icon), tap starts.
    ///   - **downloading** → spinner with optional percentage label,
    ///     tap cancels.
    ///   - **downloaded** → "Downloaded ✓", tap opens a confirmation
    ///     dialog asking to remove from device. The button label
    ///     literally says what tapping does — discoverable, no
    ///     hidden long-press / swipe.
    @ViewBuilder
    private func downloadButton(_ detail: ApiTitleDetail, epub: ApiBookEdition) -> some View {
        let mediaItemId = epub.id
        let isDownloaded = bookCache.isDownloaded(mediaItemId)
        let active = bookCache.activeDownloads[mediaItemId]
        let failure = bookCache.failedDownloads[mediaItemId]

        if let active {
            Button {
                bookCache.cancelDownload(mediaItemId)
            } label: {
                HStack(spacing: 6) {
                    ProgressView()
                        .controlSize(.small)
                    if let total = active.totalBytes, total > 0 {
                        let pct = Int((Double(active.bytesReceived) / Double(total)) * 100)
                        Text("\(pct)%")
                    } else {
                        Text("Downloading…")
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        } else if let failure {
            // Failure state: red Retry button stacked over the actual
            // error text, so the user knows the click had an effect
            // and what went wrong. Tap re-runs startDownload, which
            // clears the failure entry and starts fresh.
            VStack(alignment: .leading, spacing: 4) {
                Button {
                    do {
                        // Persist the parent ApiTitleDetail proto +
                        // pin cover/author headshot so the book reads
                        // offline. Idempotent — every retry / extra
                        // download of this same title rewrites the
                        // same .detail.pb.
                        bookCache.cacheTitleDetail(detail)
                        try bookCache.startDownload(
                            mediaItemId: mediaItemId,
                            titleId: detail.id.protoValue,
                            titleName: detail.name,
                            authorName: detail.authorName ?? "Unknown")
                    } catch {
                        log.warning("retry startDownload failed: \(error.localizedDescription)")
                    }
                } label: {
                    Label("Retry Download", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .tint(.red)
                Text(failure)
                    .font(.caption2)
                    .foregroundStyle(.red)
                    .lineLimit(3)
            }
        } else if isDownloaded {
            Button {
                pendingRemoval = mediaItemId
            } label: {
                Label("Downloaded", systemImage: "checkmark.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .confirmationDialog(
                "Remove this download?",
                isPresented: Binding(
                    get: { pendingRemoval == mediaItemId },
                    set: { if !$0 { pendingRemoval = nil } }),
                titleVisibility: .visible
            ) {
                Button("Remove from Device", role: .destructive) {
                    do {
                        try bookCache.deleteDownload(mediaItemId)
                    } catch {
                        log.warning("delete download failed: \(error.localizedDescription)")
                    }
                    pendingRemoval = nil
                }
                Button("Cancel", role: .cancel) { pendingRemoval = nil }
            } message: {
                Text("The book file (\(byteSize(of: mediaItemId))) will be removed. You can re-download it any time.")
            }
        } else {
            Button {
                do {
                    // Persist the parent ApiTitleDetail proto +
                    // pin cover/author headshot so the book reads
                    // offline AND so the Books/Authors offline
                    // surfaces can find it. The Retry branch above
                    // had this; the primary first-time-download
                    // branch was missing it, so fresh downloads
                    // landed an .epub but no .detail.pb and the
                    // offline AuthorsView showed "No authors yet"
                    // even with the book on disk.
                    bookCache.cacheTitleDetail(detail)
                    try bookCache.startDownload(
                        mediaItemId: mediaItemId,
                        titleId: detail.id.protoValue,
                        titleName: detail.name,
                        authorName: detail.authorName ?? "Unknown")
                } catch {
                    log.warning("startDownload failed: \(error.localizedDescription)")
                }
            } label: {
                Label("Download", systemImage: "arrow.down.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
    }

    /// Human-readable size of the downloaded book, looked up by id.
    /// Returns "—" if the row is missing (shouldn't happen on the
    /// downloaded-state path, but the fallback keeps the dialog
    /// rendering even in the race where the row was just deleted).
    private func byteSize(of mediaItemId: Int64) -> String {
        guard let row = bookCache.downloads.first(where: { $0.mediaItemId == mediaItemId }) else {
            return "—"
        }
        return ByteCountFormatter.string(fromByteCount: row.sizeBytes, countStyle: .file)
    }

    /// First downloadable EPUB edition, or nil. v2 will broaden this
    /// to PDF (via PDFKit) and digital audiobooks.
    private func epubEdition(_ editions: [ApiBookEdition]) -> ApiBookEdition? {
        editions.first { $0.downloadable && $0.format == .ebookEpub }
    }

    @ViewBuilder
    private func descriptionBlock(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Description")
                .font(.headline)
            Text(text)
                .font(.body)
        }
    }

    @ViewBuilder
    private func editionsBlock(_ editions: [ApiBookEdition]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Editions")
                .font(.headline)
            ForEach(editions) { edition in
                HStack(spacing: 8) {
                    Image(systemName: edition.downloadable ? "square.and.arrow.down" : "books.vertical")
                        .foregroundStyle(.secondary)
                    Text(format(edition.format))
                    if let location = edition.storageLocation {
                        Text("•")
                            .foregroundStyle(.secondary)
                        Text(location)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if edition.downloadable {
                        Text("digital")
                            .font(.caption)
                            .foregroundStyle(.tint)
                    }
                    if isAdmin {
                        Button {
                            editingEdition = edition
                        } label: {
                            Image(systemName: "pencil.circle")
                                .foregroundStyle(.tint)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .font(.subheadline)
                Divider()
            }
        }
        .sheet(item: $editingEdition) { edition in
            EditEditionSheet(edition: edition) {
                // Reload the detail so the editions row shows the
                // updated storage_location without leaving the page.
                Task { await load() }
            }
        }
    }

    /// Display string for the proto edition-format enum. Falls back to
    /// the raw enum name for any case we haven't named here yet so the
    /// UI doesn't render an empty string.
    private func format(_ format: MMBookEditionFormat) -> String {
        switch format {
        case .ebookEpub: return "EPUB"
        case .ebookPdf: return "PDF"
        case .audiobookDigital: return "Audiobook"
        case .paperback: return "Paperback"
        case .hardcover: return "Hardcover"
        case .unknown, .UNRECOGNIZED:
            return "Other"
        }
    }
}

/// Admin-only sheet for editing a book edition's metadata. v1 covers
/// `storage_location` (the most useful per-edition field — purchase
/// price / replacement value aren't on the BookEdition proto yet, so
/// we'd be editing blind). When more fields land they slot in here.
private struct EditEditionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(AuthManager.self) private var authManager
    let edition: ApiBookEdition
    let onSave: () -> Void

    @State private var storageLocation: String = ""
    @State private var saving = false
    @State private var error: String? = nil

    var body: some View {
        NavigationStack {
            Form {
                Section("Storage location") {
                    TextField("e.g. Living room, shelf 3", text: $storageLocation)
                        .textInputAutocapitalization(.sentences)
                        .autocorrectionDisabled(false)
                }

                if let error {
                    Section {
                        Text(error)
                            .font(.callout)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Edit Edition")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: save) {
                        if saving { ProgressView() } else { Text("Save") }
                    }
                    .disabled(saving)
                }
            }
            .onAppear {
                storageLocation = edition.storageLocation ?? ""
            }
        }
    }

    private func save() {
        saving = true
        error = nil
        Task {
            do {
                try await authManager.grpcClient.adminUpdateMediaItem(
                    mediaItemId: edition.id,
                    // Pass the field even when blank — empty string
                    // clears the value server-side, matching the web
                    // edit-page behaviour.
                    storageLocation: storageLocation)
                onSave()
                dismiss()
            } catch {
                self.error = error.localizedDescription
                saving = false
            }
        }
    }
}
