import SwiftUI
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "BookSeriesDetailView")

/// Book-series detail page (Foundation, Dune, etc). Shows owned volumes
/// in series order, then unowned bibliography entries from OpenLibrary
/// when the server can fill in gaps. The hero cover comes from the
/// first owned volume's title — the proto's `cover_isbn` is not yet
/// wired into ImageService for ISBN lookups.
struct BookSeriesDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(BookCacheManager.self) private var bookCache
    @Environment(\.marquee) private var layout
    let route: BookSeriesRoute

    @State private var detail: ApiBookSeriesDetail?
    @State private var loading = true
    @State private var fillingGaps = false
    @State private var fillResult: String? = nil
    /// Optimistic-update overlay for the missing-volumes section.
    /// Maps OL work id → whether the user has wished it locally
    /// (regardless of what the server-side `alreadyWished` says).
    /// Lets "Fill Gaps" instantly mark every missing volume as wished
    /// without round-tripping through a full reload.
    @State private var wishOverrides: [String: Bool] = [:]

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
                        if let desc = detail.description, !desc.isEmpty {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("About")
                                    .mmSectionTitle()
                                Text(desc)
                                    .font(MarqueeTheme.Fonts.body)
                                    .foregroundStyle(MarqueeTheme.Colors.text2)
                            }
                        }
                        volumesSection(detail.volumes)
                        if !detail.missingVolumes.isEmpty {
                            missingSection(detail.missingVolumes, canFill: detail.canFillGaps)
                        }
                    }
                    .padding(.horizontal, layout.pageX)
                    .padding(.vertical, 16)
                    .padding(.bottom, 48)
                }
                .mmPage()
            } else {
                ContentUnavailableView("Series not found", systemImage: "books.vertical")
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
            detail = try await dataModel.bookSeriesDetail(id: route.id)
        } catch {
            log.warning("bookSeriesDetail failed: \(error.localizedDescription)")
        }
        loading = false
    }

    @ViewBuilder
    private func header(_ detail: ApiBookSeriesDetail) -> some View {
        HStack(alignment: .top, spacing: 16) {
            MMArtFrame {
                BookCoverView(ref: heroRef(detail), seed: detail.name, cornerRadius: 0)
                    .frame(width: 120, height: 180)
            }
            .frame(width: 120, height: 180)

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.name)
                    .font(layout.hero)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .minimumScaleFactor(0.7)
                if let author = detail.author {
                    NavigationLink(value: AuthorRoute(id: author.id, name: author.name)) {
                        Text(author.name)
                            .font(MarqueeTheme.Fonts.control)
                            .foregroundStyle(MarqueeTheme.Colors.accent)
                    }
                    .buttonStyle(.plain)
                }
                Text("\(detail.volumes.count) of \(detail.volumes.count + detail.missingVolumes.count) owned")
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.text3)
                Spacer(minLength: 0)
            }
        }
    }

    /// Use the first owned volume's poster as the hero. Returns nil for
    /// empty series so CachedImage falls through to its placeholder.
    private func heroRef(_ detail: ApiBookSeriesDetail) -> MMImageRef? {
        if let first = detail.volumes.first {
            return .posterThumbnail(titleId: first.titleId.protoValue)
        }
        return nil
    }

    private func volumeBulkStatus(_ volumes: [ApiBookSeriesVolume]) -> BulkDownloadStatus {
        bookCache
            .bulkStatus(forTitleIds: volumes.map { $0.titleId.protoValue })
            .asBulkDownloadStatus
    }

    @ViewBuilder
    private func volumesSection(_ volumes: [ApiBookSeriesVolume]) -> some View {
        if volumes.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
                Text("Volumes on your shelf")
                    .mmSectionTitle()

                let status = volumeBulkStatus(volumes)
                if status.completed < status.total {
                    BulkDownloadActionRow(
                        status: status,
                        noun: "volume",
                        action: { Task { await startBulkVolumeDownload(volumes) } })
                }

                ForEach(volumes) { volume in
                    NavigationLink(value: ApiTitle(
                        id: volume.titleId,
                        name: volume.titleName,
                        mediaType: .movie,
                        year: volume.firstPublicationYear)) {
                        HStack(spacing: 12) {
                            MMArtFrame {
                                BookCoverView(
                                    ref: .posterThumbnail(titleId: volume.titleId.protoValue),
                                    seed: volume.titleName,
                                    cornerRadius: 0)
                                    .frame(width: 50, height: 75)
                            }
                            .frame(width: 50, height: 75)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(volume.titleName)
                                    .font(MarqueeTheme.Fonts.cardTitle)
                                    .foregroundStyle(MarqueeTheme.Colors.text)
                                    .lineLimit(2)
                                HStack(spacing: 8) {
                                    if let n = volume.seriesNumber {
                                        Text("#\(n)")
                                    }
                                    if let y = volume.firstPublicationYear {
                                        Text(String(y))
                                    }
                                    if bookCache.offlineTitleIds.contains(volume.titleId.protoValue) {
                                        // Inline (list-row) variant.
                                        Image(systemName: "arrow.down.circle.fill")
                                            .foregroundStyle(MarqueeTheme.Colors.green)
                                            .accessibilityLabel("Downloaded")
                                    }
                                }
                                .font(MarqueeTheme.Fonts.meta)
                                .foregroundStyle(MarqueeTheme.Colors.text3)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundStyle(MarqueeTheme.Colors.text3)
                                .font(.caption)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(MarqueeTheme.Colors.surface)
                        .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.card, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder
    private func missingSection(_ missing: [ApiBookSeriesMissingVolume], canFill: Bool) -> some View {
        VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
            HStack(alignment: .firstTextBaseline) {
                Text("Missing Volumes")
                    .mmSectionTitle()
                Spacer()
                // "Fill Gaps" is the bulk-wishlist affordance the
                // web app has had for a while — only render when the
                // server says it's actionable (the author has an OL
                // bibliography it can enumerate from). Disabled +
                // spinner during the round-trip; leaves a result
                // summary line below for one full UI cycle.
                if canFill {
                    Button {
                        Task { await fillGaps() }
                    } label: {
                        if fillingGaps {
                            ProgressView().controlSize(.small)
                        } else {
                            Label("Fill Gaps", systemImage: "wand.and.stars")
                        }
                    }
                    .buttonStyle(.mmGhost)
                    .disabled(fillingGaps)
                }
            }

            if let summary = fillResult {
                Text(summary)
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.accent)
            }

            ForEach(missing) { volume in
                HStack(spacing: 12) {
                    MMArtFrame {
                        BookCoverView(
                            ref: .openlibraryCover(workId: volume.openLibraryWorkId),
                            seed: volume.title,
                            cornerRadius: 0)
                            .frame(width: 50, height: 75)
                    }
                    .frame(width: 50, height: 75)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(volume.title)
                            .font(MarqueeTheme.Fonts.cardTitle)
                            .foregroundStyle(MarqueeTheme.Colors.text)
                            .lineLimit(2)
                        HStack(spacing: 8) {
                            if let n = volume.seriesNumber {
                                Text("#\(n)")
                            }
                            if let y = volume.year {
                                Text(String(y))
                            }
                        }
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                    }
                    Spacer()
                    let wished = wishOverrides[volume.openLibraryWorkId] ?? volume.alreadyWished
                    if wished {
                        Image(systemName: "heart.fill")
                            .foregroundStyle(MarqueeTheme.Colors.pink)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(MarqueeTheme.Colors.surface)
                .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.card, style: .continuous))
            }
        }
    }

    /// Bulk-add wishes for every missing volume in this series via
    /// the server's WishlistSeriesGaps RPC. Optimistically marks every
    /// missing entry as wished in the UI; the server resolves
    /// `already_wished` separately so the summary line tells the user
    /// what actually happened.
    private func fillGaps() async {
        fillingGaps = true
        defer { fillingGaps = false }
        do {
            let result = try await dataModel.wishlistSeriesGaps(seriesId: route.id)
            if let detail {
                for v in detail.missingVolumes {
                    wishOverrides[v.openLibraryWorkId] = true
                }
            }
            switch (result.added, result.alreadyWished) {
            case (0, _): fillResult = "Already wished — no new volumes."
            case (let n, 0): fillResult = "Added \(n) wish\(n == 1 ? "" : "es")."
            case (let n, let already): fillResult = "Added \(n), already wished \(already)."
            }
        } catch {
            log.warning("fillGaps failed: \(error.localizedDescription)")
            fillResult = "Couldn't fill gaps: \(error.localizedDescription)"
        }
    }

    /// Bulk "Download all volumes" — same shape as AuthorDetailView's
    /// bulk action. Fans out title-detail fetches, picks the first
    /// downloadable EPUB per book, kicks off downloads. Concurrency
    /// cap of 3.
    private func startBulkVolumeDownload(_ volumes: [ApiBookSeriesVolume]) async {
        let completed = bookCache.offlineTitleIds
        let inFlight = Set(bookCache.activeDownloads.values.map { $0.titleId })
        let needed = volumes.filter { v in
            let id = v.titleId.protoValue
            return !completed.contains(id) && !inFlight.contains(id)
        }

        await withTaskGroup(of: Void.self) { group in
            var iterator = needed.makeIterator()
            for _ in 0..<3 {
                guard let next = iterator.next() else { break }
                group.addTask { await fetchAndStartVolume(next) }
            }
            for await _ in group {
                if let next = iterator.next() {
                    group.addTask { await fetchAndStartVolume(next) }
                }
            }
        }
    }

    private func fetchAndStartVolume(_ volume: ApiBookSeriesVolume) async {
        do {
            let detail = try await dataModel.titleDetail(id: volume.titleId)
            let editions = detail.book?.editions ?? []
            guard let epub = editions.first(where: { $0.downloadable && $0.format == .ebookEpub }) else {
                log.info("startBulkVolumeDownload: titleId=\(volume.titleId.protoValue) has no downloadable EPUB — skipped")
                return
            }
            // Persist the title detail so the bulk-downloaded volume
            // reads offline without a follow-up online fetch.
            bookCache.cacheTitleDetail(detail)
            try bookCache.startDownload(
                mediaItemId: epub.id,
                titleId: volume.titleId.protoValue,
                titleName: detail.name,
                authorName: detail.authorName ?? "Unknown")
        } catch {
            log.warning("startBulkVolumeDownload: failed for titleId=\(volume.titleId.protoValue): \(error.localizedDescription)")
        }
    }
}

/// Navigation route into `BookSeriesDetailView`.
struct BookSeriesRoute: Hashable {
    let id: BookSeriesID
    let name: String
}
