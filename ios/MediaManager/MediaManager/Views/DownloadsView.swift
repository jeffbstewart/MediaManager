import SwiftUI

struct DownloadsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(BookCacheManager.self) private var bookCache
    @Environment(AudioCacheManager.self) private var audioCache
    @State private var pendingBookRemoval: Int64? = nil

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

            // ---- Books ----

            let activeBooks = Array(bookCache.activeDownloads.values)
                .sorted { $0.mediaItemId < $1.mediaItemId }
            let downloadedBooks = bookCache.downloads
                .sorted { $0.titleName < $1.titleName }

            if !activeBooks.isEmpty && !isOffline {
                Section("Active Books") {
                    ForEach(activeBooks, id: \.mediaItemId) { progress in
                        activeBookRow(progress)
                    }
                }
            }

            if !downloadedBooks.isEmpty {
                Section("Books") {
                    ForEach(downloadedBooks) { book in
                        bookRow(book)
                    }
                }
            }

            // ---- Audio (albums) ----

            let activeAlbums = Array(audioCache.activeDownloads.values)
                .sorted { $0.albumName < $1.albumName }
            let downloadedAlbums = audioCache.downloads
                .sorted { $0.name < $1.name }

            if !activeAlbums.isEmpty && !isOffline {
                Section("Active Albums") {
                    ForEach(activeAlbums, id: \.titleId) { progress in
                        activeAlbumRow(progress)
                    }
                }
            }

            if !downloadedAlbums.isEmpty {
                Section("Albums") {
                    ForEach(downloadedAlbums) { album in
                        albumRow(album)
                    }
                }
            }

            // ---- Audio (playlists) ----

            let activePlaylists = Array(audioCache.activePlaylistDownloads.values)
                .sorted { $0.playlistName < $1.playlistName }
            let downloadedPlaylists = audioCache.playlistDownloads
                .sorted { $0.name < $1.name }

            if !activePlaylists.isEmpty && !isOffline {
                Section("Active Playlists") {
                    ForEach(activePlaylists, id: \.playlistId) { progress in
                        activePlaylistRow(progress)
                    }
                }
            }

            if !downloadedPlaylists.isEmpty {
                Section("Playlists") {
                    ForEach(downloadedPlaylists) { playlist in
                        playlistRow(playlist)
                    }
                }
            }

            Section("Storage") {
                storageRow(items: items)
            }

            if active.isEmpty && completed.isEmpty && activeBooks.isEmpty && downloadedBooks.isEmpty && activeAlbums.isEmpty && downloadedAlbums.isEmpty && activePlaylists.isEmpty && downloadedPlaylists.isEmpty {
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
    private func albumRow(_ album: DownloadedAlbum) -> some View {
        // Tap navigates to AlbumDetailView; ApiTitle proto is built
        // from the cached fields so ContentView's isAlbum branch
        // routes correctly. Same trick SearchView's albumResultLink
        // uses for nav.
        NavigationLink(value: makeAlbumNavigationTitle(album)) {
            HStack(spacing: 12) {
                CachedImage(
                    ref: .posterThumbnail(titleId: album.titleId),
                    cornerRadius: 4)
                    .frame(width: 44, height: 44)  // square
                VStack(alignment: .leading, spacing: 2) {
                    Text(album.name)
                        .font(.headline)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        if !album.artistName.isEmpty {
                            Text(album.artistName)
                                .lineLimit(1)
                        }
                        Text("\(album.trackIds.count) tracks")
                        Text(ByteCountFormatter.string(
                            fromByteCount: album.sizeBytes,
                            countStyle: .file))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                audioCache.deleteAlbum(titleId: album.titleId)
            } label: {
                Label("Remove", systemImage: "trash")
            }
        }
    }

    private func makeAlbumNavigationTitle(_ album: DownloadedAlbum) -> ApiTitle {
        var proto = MMTitle()
        proto.id = album.titleId
        proto.name = album.name
        proto.mediaType = .album
        return ApiTitle(proto: proto)
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
    private func playlistRow(_ playlist: DownloadedPlaylist) -> some View {
        // Tap navigates to PlaylistDetailView via the same
        // PlaylistRoute the in-app playlist list uses; offline
        // load() falls back to cachedPlaylistDetail.
        NavigationLink(value: PlaylistRoute(id: playlist.playlistId, name: playlist.name)) {
            HStack(spacing: 12) {
                Image(systemName: "music.note.list")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .frame(width: 44, height: 44)
                    .background(.fill.quaternary)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                VStack(alignment: .leading, spacing: 2) {
                    Text(playlist.name)
                        .font(.headline)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Text("\(playlist.trackIds.count) tracks")
                        Text(ByteCountFormatter.string(
                            fromByteCount: playlist.sizeBytes,
                            countStyle: .file))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                audioCache.deletePlaylist(playlistId: playlist.playlistId)
            } label: {
                Label("Remove", systemImage: "trash")
            }
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
    private func bookRow(_ book: DownloadedBook) -> some View {
        NavigationLink(value: BookReaderRoute(
            mediaItemId: book.mediaItemId,
            titleName: book.titleName)
        ) {
            HStack(spacing: 12) {
                CachedImage(ref: .posterThumbnail(titleId: book.titleId), cornerRadius: 4)
                    .frame(width: 44, height: 64)
                VStack(alignment: .leading, spacing: 4) {
                    Text(book.titleName)
                        .font(.headline)
                        .lineLimit(1)
                    Text(book.authorName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    HStack(spacing: 8) {
                        Text(ByteCountFormatter.string(fromByteCount: book.sizeBytes, countStyle: .file))
                        if book.completedFraction > 0 {
                            Text("·")
                            Text("\(Int(book.completedFraction * 100))%")
                                .foregroundStyle(.tint)
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
                pendingBookRemoval = book.mediaItemId
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
        .confirmationDialog(
            "Remove this download?",
            isPresented: Binding(
                get: { pendingBookRemoval == book.mediaItemId },
                set: { if !$0 { pendingBookRemoval = nil } }),
            titleVisibility: .visible
        ) {
            Button("Remove from Device", role: .destructive) {
                try? bookCache.deleteDownload(book.mediaItemId)
                pendingBookRemoval = nil
            }
            Button("Cancel", role: .cancel) { pendingBookRemoval = nil }
        } message: {
            Text("\"\(book.titleName)\" (\(ByteCountFormatter.string(fromByteCount: book.sizeBytes, countStyle: .file))) will be removed. You can re-download any time.")
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
        let route = playbackRoute(for: item)
        return NavigationLink(value: route) {
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
        let route = playbackRoute(for: item)
        return NavigationLink(value: route) {
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
    }

    private func playbackRoute(for item: DownloadItem) -> PlaybackRoute {
        PlaybackRoute(
            transcodeId: item.transcodeId,
            titleName: item.titleName,
            episodeName: item.episodeTitle,
            hasSubtitles: item.hasSubtitles,
            seasonNumber: item.seasonNumber,
            episodeNumber: item.episodeNumber
        )
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
        CachedImage(ref: .posterThumbnail(titleId: item.titleId.protoValue), cornerRadius: 4, contentMode: .fill)
            .frame(width: 44, height: 64)
            .clipped()
    }

    private func storageRow(items: [DownloadItem]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            let videoUsed = dataModel.downloads.totalStorageUsed
            let bookUsed = bookCache.totalBytes
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
