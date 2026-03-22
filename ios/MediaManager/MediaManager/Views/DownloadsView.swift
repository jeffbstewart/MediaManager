import SwiftUI

struct DownloadsView: View {
    @Environment(OnlineDataModel.self) private var dataModel

    private var isOffline: Bool { dataModel.downloads.isEffectivelyOffline }

    var body: some View {
        List {
            let active = dataModel.downloads.items.filter {
                $0.state == .fetchingMetadata || $0.state == .downloading || $0.state == .paused || $0.state == .failed
            }
            let completed = dataModel.downloads.items.filter { $0.state == .completed }

            // Group completed downloads by title
            let titleGroups = Dictionary(grouping: completed) { $0.titleId }
            let sortedTitleIds = titleGroups.keys.sorted { a, b in
                let aName = titleGroups[a]?.first?.titleName ?? ""
                let bName = titleGroups[b]?.first?.titleName ?? ""
                return aName < bName
            }

            if !active.isEmpty && !isOffline {
                Section("Active") {
                    ForEach(active) { item in
                        activeRow(item)
                    }
                }
            }

            if !sortedTitleIds.isEmpty {
                Section("Downloaded") {
                    ForEach(sortedTitleIds, id: \.self) { titleId in
                        if let items = titleGroups[titleId], let first = items.first {
                            titleRow(first, episodeCount: items.count)
                        }
                    }
                }
            }

            Section("Storage") {
                storageRow
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
                    case .completed:
                        EmptyView()
                    }
                }

                Spacer()

                switch item.state {
                case .downloading:
                    Button { dataModel.downloads.pauseDownload(transcodeId: item.transcodeId) } label: {
                        Image(systemName: "pause.circle.fill")
                            .font(.title2)
                    }
                case .paused, .failed:
                    Button { dataModel.downloads.resumeDownload(transcodeId: item.transcodeId) } label: {
                        Image(systemName: "arrow.clockwise.circle.fill")
                            .font(.title2)
                    }
                default:
                    EmptyView()
                }

                Button { dataModel.downloads.deleteDownload(transcodeId: item.transcodeId) } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                }
            }

            if item.state == .downloading {
                ProgressView(value: item.progress)
                    .tint(.blue)
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func titleRow(_ item: DownloadItem, episodeCount: Int) -> some View {
        // Navigate to title detail for richer browsing (works offline via cache)
        NavigationLink(value: ApiTitle(
            id: item.titleId,
            name: item.titleName,
            mediaType: episodeCount > 1 ? .tv : .movie,
            year: item.year,
            description: nil,
            posterUrl: item.posterUrl,
            backdropUrl: nil,
            contentRating: nil,
            popularity: nil,
            quality: item.quality,
            playable: true,
            transcodeId: episodeCount == 1 ? item.transcodeId : nil,
            tmdbId: nil,
            tmdbCollectionId: nil,
            tmdbCollectionName: nil,
            familyMembers: nil
        )) {
            HStack {
                posterImage(item)

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.titleName)
                        .font(.headline)
                        .lineLimit(1)

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
            }
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                // Delete all downloads for this title
                let titleId = item.titleId
                let toDelete = dataModel.downloads.items.filter { $0.titleId == titleId && $0.state == .completed }
                for dl in toDelete {
                    dataModel.downloads.deleteDownload(transcodeId: dl.transcodeId)
                }
            } label: {
                Label("Delete", systemImage: "trash")
            }
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
        } else if let posterUrl = item.posterUrl {
            AuthenticatedImage(
                path: posterUrl,
                apiClient: dataModel.apiClient,
                cornerRadius: 4,
                contentMode: .fill
            )
            .frame(width: 44, height: 64)
            .clipped()
        } else {
            RoundedRectangle(cornerRadius: 4)
                .fill(.quaternary)
                .frame(width: 44, height: 64)
                .overlay {
                    Image(systemName: "film")
                        .foregroundStyle(.secondary)
                        .font(.caption)
                }
        }
    }

    private var storageRow: some View {
        VStack(alignment: .leading, spacing: 4) {
            let used = dataModel.downloads.totalStorageUsed
            let free = (try? FileManager.default.attributesOfFileSystem(
                forPath: NSHomeDirectory())[.systemFreeSize] as? Int64) ?? 0

            HStack {
                Text("Downloads")
                Spacer()
                Text(ByteCountFormatter.string(fromByteCount: used, countStyle: .file))
                    .foregroundStyle(.secondary)
            }

            HStack {
                Text("Free Space")
                Spacer()
                Text(ByteCountFormatter.string(fromByteCount: free, countStyle: .file))
                    .foregroundStyle(.secondary)
            }
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
}
