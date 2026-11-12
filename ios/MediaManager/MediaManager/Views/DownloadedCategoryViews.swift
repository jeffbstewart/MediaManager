import SwiftUI
import MediaManagerCore
import MediaManagerProtos

// MARK: - Shared confirmation sheet
//
// Used by every "Delete All" affordance on the per-category
// downloaded sub-pages. Requires the user to type the literal
// string "DELETE" before the destructive button enables, so an
// accidental tap on a children-prone device can't wipe their
// downloaded library in one move.

private struct TypeDeleteConfirmationSheet: View {
    let title: String
    let message: String
    let confirmLabel: String
    let onCancel: () -> Void
    let onConfirm: () -> Void

    @State private var typed: String = ""
    private let requiredKeyword = "DELETE"

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "trash.fill")
                .font(.system(size: 52))
                .foregroundStyle(.red)
                .padding(.top, 24)

            Text(title)
                .font(.title2)
                .fontWeight(.semibold)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            Text(message)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal)

            VStack(spacing: 6) {
                Text("Type \(requiredKeyword) to confirm")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField(requiredKeyword, text: $typed)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit {
                        if typed == requiredKeyword { onConfirm() }
                    }
            }
            .padding(.horizontal)
            .padding(.top, 4)

            Spacer()

            VStack(spacing: 8) {
                Button(role: .destructive) {
                    onConfirm()
                } label: {
                    Text(confirmLabel)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(typed != requiredKeyword)

                Button("Cancel", role: .cancel, action: onCancel)
                    .padding(.bottom, 4)
            }
            .padding(.horizontal)
            .padding(.bottom)
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

/// Convenience modifier — attaches the typed-DELETE sheet to the
/// presenting view. Pulled into a method so every sub-page wires
/// it the same way.
private extension View {
    func deleteAllSheet(
        isPresented: Binding<Bool>,
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: @escaping () -> Void
    ) -> some View {
        sheet(isPresented: isPresented) {
            TypeDeleteConfirmationSheet(
                title: title,
                message: message,
                confirmLabel: confirmLabel,
                onCancel: { isPresented.wrappedValue = false },
                onConfirm: {
                    onConfirm()
                    isPresented.wrappedValue = false
                })
        }
    }
}

private func formatBytes(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}

// MARK: - Movies

struct DownloadedMoviesView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var showDeleteAll = false

    private var movies: [MMDownloadEntry] {
        dataModel.downloads.entries
            .filter { $0.state == .completed && $0.mediaType == .movie }
            .sorted { $0.titleName < $1.titleName }
    }

    var body: some View {
        List {
            if movies.isEmpty {
                ContentUnavailableView("No downloaded movies",
                    systemImage: "film",
                    description: Text("Download a movie from its detail page to use it offline."))
            } else {
                Section {
                    Button(role: .destructive) {
                        showDeleteAll = true
                    } label: {
                        Label("Delete All Movies", systemImage: "trash")
                    }
                }

                Section {
                    ForEach(movies, id: \.transcodeID) { entry in
                        movieRow(entry)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    dataModel.downloads.deleteDownload(transcodeId: entry.transcodeID)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .navigationTitle("Downloaded Movies (\(movies.count))")
        .navigationBarTitleDisplayMode(.inline)
        .deleteAllSheet(
            isPresented: $showDeleteAll,
            title: "Delete all \(movies.count) movies?",
            message: "This removes every downloaded movie (\(formatBytes(totalBytes))) from this device. You can re-download any title from its detail page.",
            confirmLabel: "Delete All Movies"
        ) {
            for entry in movies {
                dataModel.downloads.deleteDownload(transcodeId: entry.transcodeID)
            }
        }
    }

    private var totalBytes: Int64 {
        movies.map { $0.fileSizeBytes }.reduce(0, +)
    }

    @ViewBuilder
    private func movieRow(_ entry: MMDownloadEntry) -> some View {
        let item = DownloadItem(entry: entry)
        NavigationLink(value: PlaybackRoute(
            transcodeId: item.transcodeId,
            titleName: item.titleName,
            episodeName: nil,
            hasSubtitles: item.hasSubtitles)
        ) {
            HStack {
                CachedImage(ref: .posterThumbnail(titleId: entry.titleID), cornerRadius: 4, contentMode: .fill)
                    .frame(width: 44, height: 64)
                    .clipped()
                VStack(alignment: .leading, spacing: 4) {
                    Text(entry.titleName)
                        .font(.headline)
                        .lineLimit(1)
                    HStack(spacing: 8) {
                        if entry.year > 0 { Text(String(entry.year)) }
                        Text(formatBytes(entry.fileSizeBytes))
                    }
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

// MARK: - TV Shows

struct DownloadedTVShowsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var showDeleteAll = false

    /// Episodes grouped by show titleId, with the parent show name +
    /// total bytes for the disclosure label.
    private var groups: [(titleId: Int64, titleName: String, episodes: [MMDownloadEntry])] {
        let completed = dataModel.downloads.entries.filter {
            $0.state == .completed && $0.mediaType == .tv
        }
        let byTitle = Dictionary(grouping: completed) { $0.titleID }
        return byTitle.map { (titleId, eps) in
            let sorted = eps.sorted {
                ($0.seasonNumber, $0.episodeNumber) < ($1.seasonNumber, $1.episodeNumber)
            }
            return (titleId, sorted.first?.titleName ?? "", sorted)
        }
        .sorted { $0.titleName < $1.titleName }
    }

    var body: some View {
        List {
            if groups.isEmpty {
                ContentUnavailableView("No downloaded TV shows",
                    systemImage: "tv",
                    description: Text("Download episodes from a show's seasons page to use them offline."))
            } else {
                Section {
                    Button(role: .destructive) {
                        showDeleteAll = true
                    } label: {
                        Label("Delete All TV Episodes", systemImage: "trash")
                    }
                }

                ForEach(groups, id: \.titleId) { group in
                    Section {
                        DisclosureGroup {
                            ForEach(group.episodes, id: \.transcodeID) { ep in
                                episodeRow(ep)
                                    .swipeActions(edge: .trailing) {
                                        Button(role: .destructive) {
                                            dataModel.downloads.deleteDownload(transcodeId: ep.transcodeID)
                                        } label: {
                                            Label("Delete", systemImage: "trash")
                                        }
                                    }
                            }
                        } label: {
                            HStack {
                                CachedImage(ref: .posterThumbnail(titleId: group.titleId), cornerRadius: 4, contentMode: .fill)
                                    .frame(width: 44, height: 64)
                                    .clipped()
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(group.titleName)
                                        .font(.headline)
                                        .lineLimit(1)
                                    Text("\(group.episodes.count) episodes · \(formatBytes(group.episodes.map { $0.fileSizeBytes }.reduce(0, +)))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Downloaded TV (\(groups.count))")
        .navigationBarTitleDisplayMode(.inline)
        .deleteAllSheet(
            isPresented: $showDeleteAll,
            title: "Delete all downloaded TV?",
            message: "This removes every downloaded TV episode (\(formatBytes(totalBytes))) across \(groups.count) show\(groups.count == 1 ? "" : "s") from this device.",
            confirmLabel: "Delete All TV"
        ) {
            for group in groups {
                for ep in group.episodes {
                    dataModel.downloads.deleteDownload(transcodeId: ep.transcodeID)
                }
            }
        }
    }

    private var totalBytes: Int64 {
        groups.flatMap { $0.episodes }.map { $0.fileSizeBytes }.reduce(0, +)
    }

    @ViewBuilder
    private func episodeRow(_ entry: MMDownloadEntry) -> some View {
        let item = DownloadItem(entry: entry)
        NavigationLink(value: PlaybackRoute(
            transcodeId: item.transcodeId,
            titleName: item.titleName,
            episodeName: item.episodeTitle,
            hasSubtitles: item.hasSubtitles,
            seasonNumber: item.seasonNumber,
            episodeNumber: item.episodeNumber)
        ) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("S\(entry.seasonNumber)E\(entry.episodeNumber)")
                        .font(.subheadline)
                        .fontWeight(.medium)
                    if !entry.episodeTitle.isEmpty {
                        Text(entry.episodeTitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
                Text(formatBytes(entry.fileSizeBytes))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            }
        }
    }
}

// MARK: - Books

struct DownloadedBooksView: View {
    @Environment(BookCacheManager.self) private var bookCache
    @State private var showDeleteAll = false
    @State private var pendingRemoval: Int64? = nil

    private var books: [DownloadedBook] {
        bookCache.downloads.sorted { $0.titleName < $1.titleName }
    }

    var body: some View {
        List {
            if books.isEmpty {
                ContentUnavailableView("No downloaded books",
                    systemImage: "books.vertical",
                    description: Text("Download a book from its detail page to read it offline."))
            } else {
                Section {
                    Button(role: .destructive) {
                        showDeleteAll = true
                    } label: {
                        Label("Delete All Books", systemImage: "trash")
                    }
                }

                Section {
                    ForEach(books) { book in
                        bookRow(book)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    pendingRemoval = book.mediaItemId
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                            .confirmationDialog(
                                "Remove this download?",
                                isPresented: Binding(
                                    get: { pendingRemoval == book.mediaItemId },
                                    set: { if !$0 { pendingRemoval = nil } }),
                                titleVisibility: .visible
                            ) {
                                Button("Remove from Device", role: .destructive) {
                                    try? bookCache.deleteDownload(book.mediaItemId)
                                    pendingRemoval = nil
                                }
                                Button("Cancel", role: .cancel) { pendingRemoval = nil }
                            } message: {
                                Text("\"\(book.titleName)\" (\(formatBytes(book.sizeBytes))) will be removed. You can re-download any time.")
                            }
                    }
                }
            }
        }
        .navigationTitle("Downloaded Books (\(books.count))")
        .navigationBarTitleDisplayMode(.inline)
        .deleteAllSheet(
            isPresented: $showDeleteAll,
            title: "Delete all \(books.count) books?",
            message: "This removes every downloaded book (\(formatBytes(totalBytes))) from this device.",
            confirmLabel: "Delete All Books"
        ) {
            for book in books {
                try? bookCache.deleteDownload(book.mediaItemId)
            }
        }
    }

    private var totalBytes: Int64 {
        books.map { $0.sizeBytes }.reduce(0, +)
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
                        Text(formatBytes(book.sizeBytes))
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
    }
}

// MARK: - Music (albums + playlists)

struct DownloadedMusicView: View {
    @Environment(AudioCacheManager.self) private var audioCache
    @State private var showDeleteAllAlbums = false
    @State private var showDeleteAllPlaylists = false

    private var albums: [DownloadedAlbum] {
        audioCache.downloads.sorted { $0.name < $1.name }
    }
    private var playlists: [DownloadedPlaylist] {
        audioCache.playlistDownloads.sorted { $0.name < $1.name }
    }
    private var albumBytes: Int64 { albums.map(\.sizeBytes).reduce(0, +) }
    private var playlistBytes: Int64 { playlists.map(\.sizeBytes).reduce(0, +) }

    var body: some View {
        List {
            if albums.isEmpty && playlists.isEmpty {
                ContentUnavailableView("No downloaded music",
                    systemImage: "music.note",
                    description: Text("Download an album or playlist from its detail page to listen offline."))
            }

            if !albums.isEmpty {
                Section("Albums (\(albums.count))") {
                    Button(role: .destructive) {
                        showDeleteAllAlbums = true
                    } label: {
                        Label("Delete All Albums", systemImage: "trash")
                    }
                    ForEach(albums) { album in
                        albumRow(album)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    audioCache.deleteAlbum(titleId: album.titleId)
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                    }
                }
            }

            if !playlists.isEmpty {
                Section("Playlists (\(playlists.count))") {
                    Button(role: .destructive) {
                        showDeleteAllPlaylists = true
                    } label: {
                        Label("Delete All Playlists", systemImage: "trash")
                    }
                    ForEach(playlists) { playlist in
                        playlistRow(playlist)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    audioCache.deletePlaylist(playlistId: playlist.playlistId)
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .navigationTitle("Downloaded Music")
        .navigationBarTitleDisplayMode(.inline)
        .deleteAllSheet(
            isPresented: $showDeleteAllAlbums,
            title: "Delete all \(albums.count) albums?",
            message: "This removes every downloaded album (\(formatBytes(albumBytes))) from this device. Playlists are unaffected.",
            confirmLabel: "Delete All Albums"
        ) {
            for album in albums {
                audioCache.deleteAlbum(titleId: album.titleId)
            }
        }
        .deleteAllSheet(
            isPresented: $showDeleteAllPlaylists,
            title: "Delete all \(playlists.count) playlists?",
            message: "This removes every downloaded playlist (\(formatBytes(playlistBytes))) from this device. Albums are unaffected.",
            confirmLabel: "Delete All Playlists"
        ) {
            for playlist in playlists {
                audioCache.deletePlaylist(playlistId: playlist.playlistId)
            }
        }
    }

    private func albumNavTitle(_ album: DownloadedAlbum) -> ApiTitle {
        var proto = MMTitle()
        proto.id = album.titleId
        proto.name = album.name
        proto.mediaType = .album
        return ApiTitle(proto: proto)
    }

    @ViewBuilder
    private func albumRow(_ album: DownloadedAlbum) -> some View {
        // Tap navigates to AlbumDetailView via ApiTitle —
        // ContentView's isAlbum branch routes correctly.
        NavigationLink(value: albumNavTitle(album)) {
            HStack(spacing: 12) {
                CachedImage(ref: .posterThumbnail(titleId: album.titleId), cornerRadius: 4)
                    .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(album.name)
                        .font(.headline)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        if !album.artistName.isEmpty {
                            Text(album.artistName).lineLimit(1)
                        }
                        Text("\(album.trackIds.count) tracks")
                        Text(formatBytes(album.sizeBytes))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private func playlistRow(_ playlist: DownloadedPlaylist) -> some View {
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
                        Text(formatBytes(playlist.sizeBytes))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
        }
    }
}
