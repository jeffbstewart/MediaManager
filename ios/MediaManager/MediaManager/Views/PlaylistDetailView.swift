import SwiftUI

private let log = MMLogger(category: "PlaylistDetailView")

/// User-playlist detail surface. Square hero, action row, full
/// tracklist with EditMode-driven reorder + swipe-to-remove,
/// rename / delete / privacy toggle in the toolbar's More menu,
/// and a star-pin affordance per row to set the playlist hero.
///
/// All mutations apply optimistically — the server is single-writer
/// per playlist (caller is the owner), so optimistic updates are
/// safe. Reverted on RPC failure.
struct PlaylistDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(AudioPlayerManager.self) private var audio
    @Environment(AudioCacheManager.self) private var audioCache
    @Environment(\.dismiss) private var dismiss
    let route: PlaylistRoute

    @State private var detail: ApiPlaylistDetail?
    @State private var loading = true
    @State private var editMode: EditMode = .inactive

    /// Local pending name/description edits shown in the rename
    /// sheet. nil = sheet closed.
    @State private var editingMetadata: EditingMetadata? = nil
    /// True while the delete confirmation dialog is up.
    @State private var showDeleteConfirm = false

    private struct EditingMetadata: Identifiable {
        let id = UUID()
        var name: String
        var description: String
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                List {
                    Section { hero(detail); actionRow(detail) }
                    Section("Tracks") {
                        ForEach(detail.tracks) { entry in
                            trackRow(entry, in: detail)
                        }
                        .onMove { from, to in
                            reorder(from: from, to: to, in: detail)
                        }
                        .onDelete { offsets in
                            removeTracks(at: offsets, in: detail)
                        }
                    }
                }
                .environment(\.editMode, $editMode)
            } else {
                ContentUnavailableView("Playlist not found",
                    systemImage: "music.note.list")
            }
        }
        .navigationTitle(detail?.summary.name ?? route.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if detail != nil {
                    Menu {
                        Button {
                            startEditMode()
                        } label: {
                            Label(editMode == .active ? "Done" : "Reorder Tracks",
                                  systemImage: "arrow.up.arrow.down")
                        }
                        Button {
                            beginRename()
                        } label: {
                            Label("Rename / Edit", systemImage: "pencil")
                        }
                        if let d = detail {
                            Button {
                                togglePrivacy(d)
                            } label: {
                                Label(
                                    d.summary.isPrivate ? "Make Public" : "Make Private",
                                    systemImage: d.summary.isPrivate ? "lock.open" : "lock")
                            }
                        }
                        Divider()
                        Button(role: .destructive) {
                            showDeleteConfirm = true
                        } label: {
                            Label("Delete Playlist", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .sheet(item: $editingMetadata) { meta in
            RenamePlaylistSheet(initialName: meta.name, initialDescription: meta.description) { newName, newDesc in
                await rename(name: newName, description: newDesc)
            }
        }
        .confirmationDialog(
            "Delete \"\(detail?.summary.name ?? "")\"?",
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                Task { await deletePlaylist() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This permanently removes the playlist for everyone. The tracks themselves stay in your library.")
        }
        .task { await load() }
    }

    private func load() async {
        loading = true
        do {
            detail = try await dataModel.playlist(id: route.id)
        } catch {
            log.warning("playlist failed: \(error.localizedDescription)")
            // Fall back to the cached MMPlaylistDetail proto written
            // at download time so a downloaded playlist is browsable
            // while offline. Same pattern as AlbumDetailView's
            // offline fallback.
            if let cached = audioCache.cachedPlaylistDetail(playlistId: route.id) {
                detail = cached
            }
        }
        loading = false
    }

    // MARK: - Hero + actions

    @ViewBuilder
    private func hero(_ detail: ApiPlaylistDetail) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Group {
                if let titleId = detail.summary.heroTitleId {
                    CachedImage(ref: .posterFull(titleId: titleId.protoValue), cornerRadius: 8)
                } else {
                    LinearGradient(
                        colors: [.indigo, .teal],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
            .frame(width: 130, height: 130)  // square

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.summary.name).font(.title2).fontWeight(.bold)
                if let desc = detail.summary.description, !desc.isEmpty {
                    Text(desc)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
                let count = detail.tracks.count
                Text("\(count) track\(count == 1 ? "" : "s") · \(formatDuration(Double(detail.totalDurationSeconds)))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if detail.summary.isPrivate {
                    Label("Private", systemImage: "lock.fill")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
        }
    }

    @ViewBuilder
    private func actionRow(_ detail: ApiPlaylistDetail) -> some View {
        let isEmpty = detail.tracks.isEmpty
        let playlistId = detail.summary.id
        let isDownloaded = audioCache.isPlaylistDownloaded(playlistId: playlistId)
        let progress = audioCache.activePlaylistDownloads[playlistId]
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                Button {
                    play(detail, startIndex: 0, shuffled: false)
                } label: {
                    Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(isEmpty)

                Button {
                    play(detail, startIndex: 0, shuffled: true)
                } label: {
                    Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .disabled(isEmpty)
            }
            playlistDownloadButton(
                detail: detail,
                isEmpty: isEmpty,
                isDownloaded: isDownloaded,
                progress: progress)
        }
    }

    /// Three-state download affordance, mirrors AlbumDetailView's
    /// downloadButton: idle (cloud-down) / in-flight (ring + N/M) /
    /// downloaded (green checkmark, taps remove).
    @ViewBuilder
    private func playlistDownloadButton(
        detail: ApiPlaylistDetail,
        isEmpty: Bool,
        isDownloaded: Bool,
        progress: PlaylistDownloadProgress?
    ) -> some View {
        if let progress {
            Button {
                audioCache.cancelPlaylistDownload(playlistId: detail.summary.id)
            } label: {
                HStack(spacing: 6) {
                    ProgressView(value: progress.fraction)
                        .progressViewStyle(.circular)
                        .controlSize(.small)
                    Text("\(progress.tracksCompleted)/\(progress.tracksTotal)")
                        .font(.caption.monospacedDigit())
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.regular)
        } else if isDownloaded {
            Button {
                audioCache.deletePlaylist(playlistId: detail.summary.id)
            } label: {
                Label("Downloaded", systemImage: "checkmark.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.regular)
            .tint(.green)
        } else {
            Button {
                audioCache.downloadPlaylist(detail: detail)
            } label: {
                Label("Download", systemImage: "arrow.down.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.regular)
            .disabled(isEmpty)
        }
    }

    // MARK: - Track rows

    @ViewBuilder
    private func trackRow(_ entry: ApiPlaylistTrackEntry, in detail: ApiPlaylistDetail) -> some View {
        let isHero = detail.summary.heroTrackId == entry.track.id
        let isCurrent = audio.currentTrack?.id == entry.track.id
        Button {
            // EditMode disables row taps for play; the user wants to
            // reorder, not start audio.
            guard editMode != .active else { return }
            if let idx = detail.tracks.firstIndex(where: { $0.id == entry.id }) {
                play(detail, startIndex: idx, shuffled: false)
            }
        } label: {
            HStack(spacing: 12) {
                CachedImage(
                    ref: .posterThumbnail(titleId: entry.albumTitleId),
                    cornerRadius: 4)
                    .frame(width: 40, height: 40)  // square album cover

                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.track.name)
                        .lineLimit(1)
                        .foregroundStyle(entry.track.playable ? .primary : .secondary)
                    Text(subtitle(for: entry))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                if isCurrent {
                    Image(systemName: audio.isPlaying ? "speaker.wave.2.fill" : "pause.fill")
                        .foregroundStyle(.tint)
                } else if let secs = entry.track.durationSeconds {
                    Text(formatDuration(secs))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Button {
                    toggleHero(entry, in: detail)
                } label: {
                    Image(systemName: isHero ? "star.fill" : "star")
                        .foregroundStyle(isHero ? .yellow : .secondary)
                        .font(.caption)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isHero ? "Clear hero pick" : "Pin as playlist hero")
            }
            .contentShape(Rectangle())
            .opacity(entry.track.playable ? 1.0 : 0.5)
        }
        .buttonStyle(.plain)
        .disabled(!entry.track.playable && editMode != .active)
    }

    // MARK: - Mutations

    private func play(_ detail: ApiPlaylistDetail, startIndex: Int, shuffled: Bool) {
        let entries = shuffled ? detail.tracks.shuffled() : detail.tracks
        let queued = entries.compactMap { entry -> QueuedTrack? in
            guard entry.track.playable else { return nil }
            return QueuedTrack(
                id: entry.track.id,
                titleId: entry.albumTitleId,
                title: entry.track.name,
                albumName: entry.albumName,
                artistName: entry.primaryArtistName,
                trackNumber: entry.track.trackNumber,
                discNumber: entry.track.discNumber,
                durationSeconds: entry.track.durationSeconds)
        }
        guard !queued.isEmpty else { return }
        let safeStart = shuffled ? 0 : max(0, min(startIndex, queued.count - 1))
        audio.play(tracks: queued, startingAt: safeStart)
    }

    private func startEditMode() {
        editMode = (editMode == .active) ? .inactive : .active
    }

    private func reorder(from offsets: IndexSet, to dest: Int, in detail: ApiPlaylistDetail) {
        var trackProtos = detail.proto.tracks
        trackProtos.move(fromOffsets: offsets, toOffset: dest)
        // Optimistic local update — rebuild the proto with the new
        // order so the body re-renders before the RPC round-trips.
        var newProto = detail.proto
        newProto.tracks = trackProtos
        self.detail = ApiPlaylistDetail(proto: newProto)
        let order = trackProtos.map { $0.playlistTrackID }
        Task {
            do {
                try await dataModel.reorderPlaylist(id: route.id, playlistTrackIdsInOrder: order)
            } catch {
                log.warning("reorder failed: \(error.localizedDescription)")
                await load()  // refetch authoritative order on failure
            }
        }
    }

    private func removeTracks(at offsets: IndexSet, in detail: ApiPlaylistDetail) {
        let toRemove = offsets.map { detail.proto.tracks[$0].playlistTrackID }
        var newProto = detail.proto
        newProto.tracks.remove(atOffsets: offsets)
        self.detail = ApiPlaylistDetail(proto: newProto)
        Task {
            for ptId in toRemove {
                do {
                    try await dataModel.removeTrackFromPlaylist(id: route.id, playlistTrackId: ptId)
                } catch {
                    log.warning("remove track \(ptId) failed: \(error.localizedDescription)")
                }
            }
            // After all removals, refetch the totals (server
            // recomputes total_duration_seconds and the count).
            await load()
        }
    }

    private func toggleHero(_ entry: ApiPlaylistTrackEntry, in detail: ApiPlaylistDetail) {
        let currentHero = detail.summary.heroTrackId
        let pickingThisOne = currentHero != entry.track.id
        Task {
            do {
                try await dataModel.setPlaylistHero(
                    id: route.id,
                    trackId: pickingThisOne ? entry.track.id : nil)
                await load()
            } catch {
                log.warning("setPlaylistHero failed: \(error.localizedDescription)")
            }
        }
    }

    private func togglePrivacy(_ detail: ApiPlaylistDetail) {
        let newValue = !detail.summary.isPrivate
        Task {
            do {
                try await dataModel.setPlaylistPrivacy(id: route.id, isPrivate: newValue)
                await load()
            } catch {
                log.warning("setPlaylistPrivacy failed: \(error.localizedDescription)")
            }
        }
    }

    private func beginRename() {
        guard let d = detail else { return }
        editingMetadata = EditingMetadata(
            name: d.summary.name,
            description: d.summary.description ?? "")
    }

    private func rename(name: String, description: String?) async {
        do {
            try await dataModel.renamePlaylist(id: route.id, name: name, description: description)
            await load()
        } catch {
            log.warning("renamePlaylist failed: \(error.localizedDescription)")
        }
    }

    private func deletePlaylist() async {
        do {
            try await dataModel.deletePlaylist(id: route.id)
            dismiss()
        } catch {
            log.warning("deletePlaylist failed: \(error.localizedDescription)")
        }
    }

    private func formatDuration(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
        return String(format: "%d:%02d", m, s)
    }

    private func subtitle(for entry: ApiPlaylistTrackEntry) -> String {
        var parts: [String] = []
        if !entry.primaryArtistName.isEmpty {
            parts.append(entry.primaryArtistName)
        }
        if !entry.albumName.isEmpty {
            parts.append(entry.albumName)
        }
        if let chip = TrackMetadataChip.formatted(
            bpm: entry.track.bpm,
            timeSignature: entry.track.timeSignature
        ) {
            parts.append(chip)
        }
        return parts.joined(separator: " · ")
    }
}

/// Sheet for renaming / editing description. Same shape as
/// CreatePlaylistSheet but pre-populated.
struct RenamePlaylistSheet: View {
    @Environment(\.dismiss) private var dismiss
    let initialName: String
    let initialDescription: String
    let onSave: (_ name: String, _ description: String?) async -> Void

    @State private var name: String
    @State private var description: String
    @State private var saving = false

    init(initialName: String, initialDescription: String,
         onSave: @escaping (_ name: String, _ description: String?) async -> Void) {
        self.initialName = initialName
        self.initialDescription = initialDescription
        self.onSave = onSave
        _name = State(initialValue: initialName)
        _description = State(initialValue: initialDescription)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Playlist name", text: $name)
                        .textInputAutocapitalization(.words)
                }
                Section("Description") {
                    TextField("Description", text: $description, axis: .vertical)
                        .lineLimit(2...4)
                }
            }
            .navigationTitle("Edit Playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: save) {
                        if saving { ProgressView() } else { Text("Save") }
                    }
                    .disabled(saving || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func save() {
        saving = true
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let trimmedDesc = description.trimmingCharacters(in: .whitespaces)
        Task {
            await onSave(trimmedName, trimmedDesc.isEmpty ? nil : trimmedDesc)
            saving = false
            dismiss()
        }
    }
}
