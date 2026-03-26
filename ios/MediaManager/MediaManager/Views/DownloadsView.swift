import SwiftUI

struct DownloadsView: View {
    @Environment(OnlineDataModel.self) private var dataModel

    private var isOffline: Bool { dataModel.downloads.isEffectivelyOffline }

    var body: some View {
        List {
            let items = dataModel.downloads.entries.map { DownloadItem(entry: $0) }
            let active = items.filter {
                $0.state == .fetchingMetadata || $0.state == .downloading || $0.state == .paused || $0.state == .failed
            }
            let queued = items.filter { $0.state == .queued }
            let completed = items.filter { $0.state == .completed }

            if !active.isEmpty && !isOffline {
                Section("Active") {
                    ForEach(active) { item in
                        activeRow(item)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    dataModel.downloads.deleteDownload(transcodeId: item.transcodeId.protoValue)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                }
            }

            if !queued.isEmpty && !isOffline {
                Section("Queued (\(queued.count))") {
                    ForEach(queued) { item in
                        queuedRow(item)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    dataModel.downloads.deleteDownload(transcodeId: item.transcodeId.protoValue)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                }
            }

            if !completed.isEmpty {
                let movieItems = completed.filter { $0.mediaType == .movie }
                let tvGroups = Dictionary(grouping: completed.filter { $0.mediaType == .tv }) { $0.titleId }
                let sortedTvTitles = tvGroups.keys.sorted { a, b in
                    (tvGroups[a]?.first?.titleName ?? "") < (tvGroups[b]?.first?.titleName ?? "")
                }

                Section("Downloaded") {
                    // Movies — flat list
                    ForEach(movieItems) { item in
                        completedRow(item)
                            .swipeActions(edge: .trailing) {
                                deleteSwipeAction(item)
                            }
                    }

                    // TV — grouped by show with disclosure
                    ForEach(sortedTvTitles, id: \.self) { titleId in
                        if let episodes = tvGroups[titleId]?.sorted(by: {
                            ($0.seasonNumber ?? 0, $0.episodeNumber ?? 0) < ($1.seasonNumber ?? 0, $1.episodeNumber ?? 0)
                        }), let first = episodes.first {
                            DisclosureGroup {
                                ForEach(episodes) { ep in
                                    completedEpisodeRow(ep)
                                        .swipeActions(edge: .trailing) {
                                            deleteSwipeAction(ep)
                                        }
                                }
                            } label: {
                                HStack {
                                    posterImage(first)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(first.titleName)
                                            .font(.headline)
                                            .lineLimit(1)
                                        Text("\(episodes.count) episodes")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Section("Storage") {
                storageRow(items: items)
            }

            if active.isEmpty && completed.isEmpty {
                ContentUnavailableView(
                    "No Downloads",
                    systemImage: "arrow.down.circle",
                    description: Text("Download movies from the title detail page to watch offline.")
                )
            }
        }
        .navigationTitle("Downloads")
    }

    @ViewBuilder
    private func activeRow(_ item: DownloadItem) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                posterImage(item)

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.titleName)
                        .font(.headline)
                        .lineLimit(1)

                    if let s = item.seasonNumber, let e = item.episodeNumber {
                        HStack(spacing: 4) {
                            Text("S\(s)E\(e)")
                                .fontWeight(.medium)
                            if let title = item.episodeTitle {
                                Text(title)
                            }
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    }

                    switch item.state {
                    case .fetchingMetadata:
                        Text("Preparing...")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    case .downloading:
                        Text(downloadProgressText(item))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    case .paused:
                        Text("Paused")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    case .failed:
                        Text(item.errorMessage ?? "Failed")
                            .font(.caption)
                            .foregroundStyle(.red)
                    case .completed, .queued, .unknown, .UNRECOGNIZED:
                        EmptyView()
                    }
                }

                Spacer()

                switch item.state {
                case .downloading:
                    Button { dataModel.downloads.pauseDownload(transcodeId: item.transcodeId.protoValue) } label: {
                        Image(systemName: "pause.circle.fill")
                            .font(.title2)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                case .paused, .failed:
                    Button { dataModel.downloads.resumeDownload(transcodeId: item.transcodeId.protoValue) } label: {
                        Image(systemName: "arrow.clockwise.circle.fill")
                            .font(.title2)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                default:
                    EmptyView()
                }

                Button { dataModel.downloads.deleteDownload(transcodeId: item.transcodeId.protoValue) } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.red.opacity(0.7))
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
            }

            if item.state == .downloading {
                ProgressView(value: item.progress)
                    .tint(.blue)
            }
        }
        .padding(.vertical, 4)
    }

    private func titleRow(_ item: DownloadItem, episodeCount: Int) -> some View {
        let title = makeTitleForNavigation(item: item, episodeCount: episodeCount)
        return NavigationLink(value: title) {
            titleRowContent(item: item, episodeCount: episodeCount)
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                let titleId = item.titleId
                let toDelete = dataModel.downloads.entries.filter {
                    $0.titleID == titleId.protoValue && $0.state == .completed
                }
                for dl in toDelete {
                    dataModel.downloads.deleteDownload(transcodeId: dl.transcodeID)
                }
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    @ViewBuilder
    private func queuedRow(_ item: DownloadItem) -> some View {
        HStack {
            posterImage(item)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.titleName)
                    .font(.headline)
                    .lineLimit(1)

                if let s = item.seasonNumber, let e = item.episodeNumber {
                    HStack(spacing: 4) {
                        Text("S\(s)E\(e)")
                            .fontWeight(.medium)
                        if let title = item.episodeTitle {
                            Text(title)
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                }

                Text("Queued")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button { dataModel.downloads.deleteDownload(transcodeId: item.transcodeId.protoValue) } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.red.opacity(0.7))
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 4)
    }

    private func completedRow(_ item: DownloadItem) -> some View {
        let title = makeTitleForNavigation(item: item, episodeCount: 1)
        return NavigationLink(value: title) {
            HStack {
                posterImage(item)

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.titleName)
                        .font(.headline)
                        .lineLimit(1)

                    if let s = item.seasonNumber, let e = item.episodeNumber {
                        HStack(spacing: 4) {
                            Text("S\(s)E\(e)")
                                .fontWeight(.medium)
                            if let epTitle = item.episodeTitle {
                                Text(epTitle)
                            }
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    }

                    HStack(spacing: 8) {
                        if let year = item.year {
                            Text(String(year))
                        }
                        if let quality = item.quality {
                            Text(quality)
                        }
                        if let size = item.fileSizeBytes {
                            Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                Spacer()

                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)

                Button {
                    dataModel.downloads.deleteDownload(transcodeId: item.transcodeId.protoValue)
                } label: {
                    Image(systemName: "trash.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.red.opacity(0.7))
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func completedEpisodeRow(_ item: DownloadItem) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                if let s = item.seasonNumber, let e = item.episodeNumber {
                    Text("S\(s)E\(e)")
                        .font(.subheadline)
                        .fontWeight(.medium)
                }
                if let epTitle = item.episodeTitle {
                    Text(epTitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer()

            if let size = item.fileSizeBytes {
                Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)

            Button {
                dataModel.downloads.deleteDownload(transcodeId: item.transcodeId.protoValue)
            } label: {
                Image(systemName: "trash.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.red.opacity(0.7))
                    .frame(minWidth: 36, minHeight: 36)
            }
            .buttonStyle(.plain)
        }
    }

    private func deleteSwipeAction(_ item: DownloadItem) -> some View {
        Button(role: .destructive) {
            dataModel.downloads.deleteDownload(transcodeId: item.transcodeId.protoValue)
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    @ViewBuilder
    private func posterImage(_ item: DownloadItem) -> some View {
        // Try cached poster first (works offline), fall back to authenticated fetch
        if let data = dataModel.downloads.loadCachedImage(for: item.titleId, name: "poster.jpg"),
           let uiImage = UIImage(data: data) {
            Image(uiImage: uiImage)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 44, height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 4))
        } else {
            CachedImage(ref: .posterThumbnail(titleId: item.titleId.protoValue), cornerRadius: 4, contentMode: .fill)
                .frame(width: 44, height: 64)
                .clipped()
        }
    }

    private func storageRow(items: [DownloadItem]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            let used = dataModel.downloads.totalStorageUsed
            let free = (try? FileManager.default.attributesOfFileSystem(
                forPath: NSHomeDirectory())[.systemFreeSize] as? Int64) ?? 0
            let pendingBytes = items
                .filter { $0.state != .completed }
                .compactMap { $0.fileSizeBytes }
                .reduce(Int64(0), +)
            let remaining = pendingBytes - items
                .filter { $0.state != .completed }
                .map { $0.bytesDownloaded }
                .reduce(Int64(0), +)

            HStack {
                Text("Downloads")
                Spacer()
                Text(ByteCountFormatter.string(fromByteCount: used, countStyle: .file))
                    .foregroundStyle(.secondary)
            }

            if remaining > 0 {
                HStack {
                    Text("Pending")
                    Spacer()
                    Text("+ \(ByteCountFormatter.string(fromByteCount: remaining, countStyle: .file))")
                        .foregroundStyle(.orange)
                }
            }

            HStack {
                Text("Free Space")
                Spacer()
                Text(ByteCountFormatter.string(fromByteCount: free, countStyle: .file))
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func titleRowContent(item: DownloadItem, episodeCount: Int) -> some View {
        HStack {
            posterImage(item)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.titleName)
                    .font(.headline)
                    .lineLimit(1)

                if let s = item.seasonNumber, let e = item.episodeNumber {
                    HStack(spacing: 4) {
                        Text("S\(s)E\(e)")
                            .fontWeight(.medium)
                        if let title = item.episodeTitle {
                            Text(title)
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                }

                HStack(spacing: 8) {
                    if let year = item.year {
                        Text(String(year))
                    }
                    if let quality = item.quality {
                        Text(quality)
                    }
                    if episodeCount > 1 {
                        Text("\(episodeCount) episodes")
                    } else if let size = item.fileSizeBytes {
                        Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)

            Button {
                let titleId = item.titleId
                let toDelete = dataModel.downloads.entries.filter {
                    $0.titleID == titleId.protoValue && $0.state == .completed
                }
                for dl in toDelete {
                    dataModel.downloads.deleteDownload(transcodeId: dl.transcodeID)
                }
            } label: {
                Image(systemName: "trash.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.red.opacity(0.7))
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.plain)
        }
    }

    private func downloadProgressText(_ item: DownloadItem) -> String {
        let downloaded = ByteCountFormatter.string(fromByteCount: item.bytesDownloaded, countStyle: .file)
        if let total = item.fileSizeBytes {
            let totalStr = ByteCountFormatter.string(fromByteCount: total, countStyle: .file)
            return "\(downloaded) / \(totalStr)"
        }
        return downloaded
    }

    private func makeTitleForNavigation(item: DownloadItem, episodeCount: Int) -> ApiTitle {
        var proto = MMTitle()
        proto.id = item.titleId.protoValue
        proto.name = item.titleName
        proto.mediaType = item.entry.mediaType
        if let year = item.year { proto.year = Int32(year) }
        proto.playable = true
        if episodeCount == 1 { proto.transcodeID = item.transcodeId.protoValue }
        return ApiTitle(proto: proto)
    }
}
