import SwiftUI

/// Browse downloaded titles when offline. Groups by title, taps navigate
/// to TitleDetailView which loads from cached protobuf.
struct OfflineLibraryView: View {
    @Environment(OnlineDataModel.self) private var dataModel

    var body: some View {
        let completed = dataModel.downloads.entries
            .filter { $0.state == .completed }
        let titleGroups = Dictionary(grouping: completed) { $0.titleID }
        let sortedTitles = titleGroups.keys.sorted { a, b in
            let aName = titleGroups[a]?.first?.titleName ?? ""
            let bName = titleGroups[b]?.first?.titleName ?? ""
            return aName < bName
        }

        List {
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
