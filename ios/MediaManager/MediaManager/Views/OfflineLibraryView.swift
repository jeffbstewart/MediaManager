import SwiftUI

/// Browse downloaded titles when offline. Groups by title, taps navigate
/// to TitleDetailView which loads from cached protobuf for video, or
/// directly to BookReaderView for ebooks (the reader picks up the local
/// file path via [BookCacheManager.localBookURL] without any gRPC
/// round-trip).
struct OfflineLibraryView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(BookCacheManager.self) private var bookCache

    var body: some View {
        let completed = dataModel.downloads.entries
            .filter { $0.state == .completed }
        let titleGroups = Dictionary(grouping: completed) { $0.titleID }
        let sortedTitles = titleGroups.keys.sorted { a, b in
            let aName = titleGroups[a]?.first?.titleName ?? ""
            let bName = titleGroups[b]?.first?.titleName ?? ""
            return aName < bName
        }

        let books = bookCache.downloads.sorted { $0.titleName < $1.titleName }

        List {
            if !books.isEmpty {
                Section("Books") {
                    ForEach(books) { book in
                        NavigationLink(value: BookReaderRoute(
                            mediaItemId: book.mediaItemId,
                            titleName: book.titleName)
                        ) {
                            HStack(spacing: 12) {
                                CachedImage(ref: .posterThumbnail(titleId: book.titleId), cornerRadius: 4)
                                    .frame(width: 50, height: 75)

                                VStack(alignment: .leading, spacing: 4) {
                                    Text(book.titleName)
                                        .font(.headline)
                                        .lineLimit(1)
                                    Text(book.authorName)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                    if book.completedFraction > 0 {
                                        Text("\(Int(book.completedFraction * 100))% read")
                                            .font(.caption2)
                                            .foregroundStyle(.tint)
                                    }
                                }

                                Spacer()

                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.green)
                            }
                        }
                    }
                }
            }

            if !sortedTitles.isEmpty {
                Section(books.isEmpty ? "" : "Movies & TV") {
                    ForEach(sortedTitles, id: \.self) { titleId in
                        if let entries = titleGroups[titleId], let first = entries.first {
                            let apiTitle = makeApiTitle(from: first, episodeCount: entries.count)
                            NavigationLink(value: apiTitle) {
                                HStack(spacing: 12) {
                                    CachedImage(ref: .posterThumbnail(titleId: titleId), cornerRadius: 4)
                                        .frame(width: 50, height: 75)

                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(first.titleName)
                                            .font(.headline)
                                            .lineLimit(1)

                                        if entries.count > 1 {
                                            Text("\(entries.count) episodes")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }

                                        Text(first.quality.qualityLabel)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }

                                    Spacer()

                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.green)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Library")
    }

    private func makeApiTitle(from entry: MMDownloadEntry, episodeCount: Int) -> ApiTitle {
        var proto = MMTitle()
        proto.id = entry.titleID
        proto.name = entry.titleName
        proto.mediaType = entry.mediaType
        proto.year = entry.year
        proto.playable = true
        if episodeCount == 1 {
            proto.transcodeID = entry.transcodeID
        }
        return ApiTitle(proto: proto)
    }
}

private extension MMDownloadQuality {
    var qualityLabel: String {
        switch self {
        case .sd: "SD"
        case .fhd: "FHD"
        case .uhd: "UHD"
        default: ""
        }
    }
}
