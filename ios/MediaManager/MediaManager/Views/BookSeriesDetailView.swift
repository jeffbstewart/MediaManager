import SwiftUI

private let log = MMLogger(category: "BookSeriesDetailView")

/// Book-series detail page (Foundation, Dune, etc). Shows owned volumes
/// in series order, then unowned bibliography entries from OpenLibrary
/// when the server can fill in gaps. The hero cover comes from the
/// first owned volume's title — the proto's `cover_isbn` is not yet
/// wired into ImageService for ISBN lookups.
struct BookSeriesDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let route: BookSeriesRoute

    @State private var detail: ApiBookSeriesDetail?
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        header(detail)
                        if let desc = detail.description, !desc.isEmpty {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("About")
                                    .font(.headline)
                                Text(desc)
                                    .font(.body)
                            }
                        }
                        volumesSection(detail.volumes)
                        if !detail.missingVolumes.isEmpty {
                            missingSection(detail.missingVolumes)
                        }
                    }
                    .padding()
                }
            } else {
                ContentUnavailableView("Series not found", systemImage: "books.vertical")
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
            BookCoverView(ref: heroRef(detail), seed: detail.name, cornerRadius: 8)
                .frame(width: 120, height: 180)

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.name)
                    .font(.title2)
                    .fontWeight(.bold)
                if let author = detail.author {
                    NavigationLink(value: AuthorRoute(id: author.id, name: author.name)) {
                        Text(author.name)
                            .font(.subheadline)
                            .foregroundStyle(.tint)
                    }
                    .buttonStyle(.plain)
                }
                Text("\(detail.volumes.count) of \(detail.volumes.count + detail.missingVolumes.count) owned")
                    .font(.caption)
                    .foregroundStyle(.secondary)
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

    @ViewBuilder
    private func volumesSection(_ volumes: [ApiBookSeriesVolume]) -> some View {
        if volumes.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text("Volumes on your shelf")
                    .font(.headline)
                ForEach(volumes) { volume in
                    NavigationLink(value: ApiTitle(
                        id: volume.titleId,
                        name: volume.titleName,
                        mediaType: .movie,
                        year: volume.firstPublicationYear)) {
                        HStack(spacing: 12) {
                            BookCoverView(
                                ref: .posterThumbnail(titleId: volume.titleId.protoValue),
                                seed: volume.titleName)
                                .frame(width: 50, height: 75)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(volume.titleName)
                                    .font(.body)
                                    .lineLimit(2)
                                HStack(spacing: 8) {
                                    if let n = volume.seriesNumber {
                                        Text("#\(n)")
                                            .foregroundStyle(.secondary)
                                    }
                                    if let y = volume.firstPublicationYear {
                                        Text(String(y))
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .font(.caption)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundStyle(.tertiary)
                                .font(.caption)
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
            }
        }
    }

    @ViewBuilder
    private func missingSection(_ missing: [ApiBookSeriesMissingVolume]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Missing Volumes")
                .font(.headline)
            ForEach(missing) { volume in
                HStack(spacing: 12) {
                    BookCoverView(
                        ref: .openlibraryCover(workId: volume.openLibraryWorkId),
                        seed: volume.title)
                        .frame(width: 50, height: 75)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(volume.title)
                            .font(.body)
                            .lineLimit(2)
                        HStack(spacing: 8) {
                            if let n = volume.seriesNumber {
                                Text("#\(n)")
                                    .foregroundStyle(.secondary)
                            }
                            if let y = volume.year {
                                Text(String(y))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .font(.caption)
                    }
                    Spacer()
                    if volume.alreadyWished {
                        Image(systemName: "heart.fill")
                            .foregroundStyle(.red)
                    }
                }
                .padding(.vertical, 4)
                Divider()
            }
        }
    }
}

/// Navigation route into `BookSeriesDetailView`.
struct BookSeriesRoute: Hashable {
    let id: BookSeriesID
    let name: String
}
