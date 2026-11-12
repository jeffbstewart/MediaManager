import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct DownloadsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(BookCacheManager.self) private var bookCache
    @Environment(AudioCacheManager.self) private var audioCache
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

            // ---- Active sections for non-video downloads ----

            let activeBooks = Array(bookCache.activeDownloads.values)
                .sorted { $0.mediaItemId < $1.mediaItemId }
            if !activeBooks.isEmpty && !isOffline {
                Section("Active Books") {
                    ForEach(activeBooks, id: \.mediaItemId) { progress in
                        activeBookRow(progress)
                    }
                }
            }

            let activeAlbums = Array(audioCache.activeDownloads.values)
                .sorted { $0.albumName < $1.albumName }
            if !activeAlbums.isEmpty && !isOffline {
                Section("Active Albums") {
                    ForEach(activeAlbums, id: \.titleId) { progress in
                        activeAlbumRow(progress)
                    }
                }
            }

            let activePlaylists = Array(audioCache.activePlaylistDownloads.values)
                .sorted { $0.playlistName < $1.playlistName }
            if !activePlaylists.isEmpty && !isOffline {
                Section("Active Playlists") {
                    ForEach(activePlaylists, id: \.playlistId) { progress in
                        activePlaylistRow(progress)
                    }
                }
            }

            // ---- Completed: navigate to per-category sub-pages ----
            //
            // The whole downloaded-library used to render inline here,
            // which got crowded fast on iPad. Each category now lives
            // behind its own sub-page with a "Delete All" affordance
            // and per-item swipe deletes.

            let movieCount = completed.filter { $0.mediaType == .movie }.count
            let tvShowCount = Set(completed.filter { $0.mediaType == .tv }.map { $0.titleId }).count
            let bookCount = bookCache.downloads.count
            let musicCount = audioCache.downloads.count + audioCache.playlistDownloads.count
            let anyCompleted = movieCount + tvShowCount + bookCount + musicCount > 0

            if anyCompleted {
                Section("Downloaded") {
                    if movieCount > 0 {
                        NavigationLink {
                            DownloadedMoviesView()
                        } label: {
                            categoryRow("Movies", systemImage: "film", count: movieCount)
                        }
                    }
                    if tvShowCount > 0 {
                        NavigationLink {
                            DownloadedTVShowsView()
                        } label: {
                            categoryRow(
                                "TV Shows",
                                systemImage: "tv",
                                count: tvShowCount,
                                subtitle: "\(completed.filter { $0.mediaType == .tv }.count) episodes")
                        }
                    }
                    if bookCount > 0 {
                        NavigationLink {
                            DownloadedBooksView()
                        } label: {
                            categoryRow("Books", systemImage: "books.vertical", count: bookCount)
                        }
                    }
                    if musicCount > 0 {
                        NavigationLink {
                            DownloadedMusicView()
                        } label: {
                            categoryRow(
                                "Music",
                                systemImage: "music.note",
                                count: musicCount,
                                subtitle: musicSubtitle())
                        }
                    }
                }
            }

            Section("Storage") {
                storageRow(items: items)
            }

            if active.isEmpty && !anyCompleted && activeBooks.isEmpty && activeAlbums.isEmpty && activePlaylists.isEmpty {
                ContentUnavailableView(
                    "No Downloads",
                    systemImage: "arrow.down.circle",
                    description: Text("Download movies, books, or albums from their detail pages to use them offline.")
                )
            }
        }
        .navigationTitle("Downloads")
    }

    @ViewBuilder
    private func categoryRow(_ title: String, systemImage: String, count: Int, subtitle: String? = nil) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundStyle(.tint)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text("\(count)")
                .foregroundStyle(.secondary)
        }
    }

    private func musicSubtitle() -> String {
        let albums = audioCache.downloads.count
        let playlists = audioCache.playlistDownloads.count
        switch (albums, playlists) {
        case (let a, 0): return "\(a) album\(a == 1 ? "" : "s")"
        case (0, let p): return "\(p) playlist\(p == 1 ? "" : "s")"
        case (let a, let p):
            return "\(a) album\(a == 1 ? "" : "s"), \(p) playlist\(p == 1 ? "" : "s")"
        }
    }

    @ViewBuilder
    private func activeAlbumRow(_ progress: AlbumDownloadProgress) -> some View {
        HStack(spacing: 12) {
            ProgressView(value: progress.fraction)
                .progressViewStyle(.circular)
                .controlSize(.small)
            VStack(alignment: .leading, spacing: 2) {
                Text(progress.albumName)
                    .font(.headline)
                    .lineLimit(1)
                Text("\(progress.tracksCompleted) / \(progress.tracksTotal) tracks")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                audioCache.cancelDownload(titleId: progress.titleId)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.red.opacity(0.7))
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Cancel download")
        }
    }

    @ViewBuilder
    private func activePlaylistRow(_ progress: PlaylistDownloadProgress) -> some View {
        HStack(spacing: 12) {
            ProgressView(value: progress.fraction)
                .progressViewStyle(.circular)
                .controlSize(.small)
            VStack(alignment: .leading, spacing: 2) {
                Text(progress.playlistName)
                    .font(.headline)
                    .lineLimit(1)
                Text("\(progress.tracksCompleted) / \(progress.tracksTotal) tracks")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                audioCache.cancelPlaylistDownload(playlistId: progress.playlistId)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.red.opacity(0.7))
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Cancel download")
        }
    }

    @ViewBuilder
    private func activeBookRow(_ progress: BookDownloadProgress) -> some View {
        HStack(spacing: 12) {
            ProgressView()
                .controlSize(.small)
            VStack(alignment: .leading, spacing: 2) {
                Text("Book \(progress.mediaItemId)")
                    .font(.headline)
                    .lineLimit(1)
                if let total = progress.totalBytes, total > 0 {
                    let pct = Int((Double(progress.bytesReceived) / Double(total)) * 100)
                    Text("\(pct)% (\(ByteCountFormatter.string(fromByteCount: progress.bytesReceived, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: total, countStyle: .file)))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text(ByteCountFormatter.string(fromByteCount: progress.bytesReceived, countStyle: .file))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Button {
                bookCache.cancelDownload(progress.mediaItemId)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.red.opacity(0.7))
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.plain)
        }
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
                        // Auto-retry in progress writes a "Retrying
                        // in Ns — <last error>" message into
                        // errorMessage while state stays .downloading
                        // so the row doesn't flash between Retry and
                        // running. Surface it here when present so
                        // the user can see what's going on instead
                        // of staring at a frozen progress bar.
                        if let msg = item.errorMessage, !msg.isEmpty {
                            Text(msg)
                                .font(.caption)
                                .foregroundStyle(.orange)
                                .lineLimit(2)
                        } else {
                            Text(downloadProgressText(item))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    case .paused:
                        Text("Paused")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    case .failed:
                        Text(item.errorMessage ?? "Failed")
                            .font(.caption)
                            .foregroundStyle(.red)
                            .lineLimit(3)
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

    @ViewBuilder
    private func posterImage(_ item: DownloadItem) -> some View {
        CachedImage(ref: .posterThumbnail(titleId: item.titleId.protoValue), cornerRadius: 4, contentMode: .fill)
            .frame(width: 44, height: 64)
            .clipped()
    }

    private func storageRow(items: [DownloadItem]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            let videoUsed = dataModel.downloads.totalStorageUsed
            let bookUsed = bookCache.totalBytes
            let musicUsed = audioCache.totalStorageBytes
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

            if videoUsed > 0 {
                HStack {
                    Text("Movies & TV")
                    Spacer()
                    Text(ByteCountFormatter.string(fromByteCount: videoUsed, countStyle: .file))
                        .foregroundStyle(.secondary)
                }
            }

            if musicUsed > 0 {
                HStack {
                    Text("Music")
                    Spacer()
                    Text(ByteCountFormatter.string(fromByteCount: musicUsed, countStyle: .file))
                        .foregroundStyle(.secondary)
                }
            }

            if bookUsed > 0 {
                HStack {
                    Text("Books")
                    Spacer()
                    Text(ByteCountFormatter.string(fromByteCount: bookUsed, countStyle: .file))
                        .foregroundStyle(.secondary)
                }
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
