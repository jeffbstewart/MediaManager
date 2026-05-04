import SwiftUI

private let log = MMLogger(category: "BookDetailView")

/// Per-book detail page. Shows the cover, primary author, year, series
/// link, description, edition list, and a Read button (currently
/// disabled — wired up when the WKWebView reader lands). Distinct from
/// `TitleDetailView` because nothing about books needs the movie
/// vocabulary (Play, Seasons & Episodes, transcoding).
struct BookDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let titleId: TitleID

    @State private var detail: ApiTitleDetail?
    @State private var loading = true

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
            detail = try await dataModel.titleDetail(id: titleId)
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
                readButton(detail)
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

    /// Read button. Enabled when the book has at least one downloadable
    /// EPUB edition; tap routes into `BookReaderView`. PDF and audiobook
    /// formats stay disabled in v1 — the reader is EPUB-only for now.
    @ViewBuilder
    private func readButton(_ detail: ApiTitleDetail) -> some View {
        let epub = epubEdition(detail.book?.editions ?? [])
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
                // No-op: book has no readable edition (physical only,
                // or only PDF / audiobook which v1 doesn't render).
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
                    if edition.downloadable {
                        Spacer()
                        Text("digital")
                            .font(.caption)
                            .foregroundStyle(.tint)
                    }
                }
                .font(.subheadline)
                Divider()
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
