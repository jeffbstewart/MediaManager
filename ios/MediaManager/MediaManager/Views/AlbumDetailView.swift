import SwiftUI
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "AlbumDetailView")

/// Album detail surface — square hero, action row (Play / Shuffle /
/// Add to Queue), tracklist with row-tap → play. Audio analog to
/// BookDetailView; routed from ContentView when ApiTitle.isAlbum.
struct AlbumDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(AudioPlayerManager.self) private var audio
    @Environment(AudioCacheManager.self) private var audioCache
    @Environment(\.marquee) private var layout
    let titleId: TitleID

    @State private var detail: ApiTitleDetail?
    @State private var loading = true
    /// Set when the user taps Play and we already have queued
    /// progress on the server. Sheet asks to resume or start over —
    /// matches the video-player resume prompt the user explicitly
    /// asked for.
    @State private var resumePrompt: ResumePrompt? = nil
    /// Context-menu "Add to Playlist…" target. nil = sheet closed.
    @State private var addingToPlaylist: AddToPlaylistContext? = nil

    private struct AddToPlaylistContext: Identifiable {
        let id = UUID()
        let trackId: Int64
        let trackName: String
    }

    private struct ResumePrompt: Identifiable {
        let id = UUID()
        let trackIndex: Int
        let positionSeconds: Double
        let resumeTrackName: String
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmPage()
            } else if let detail, let album = detail.album {
                ScrollView {
                    VStack(alignment: .leading, spacing: layout.sectionGap) {
                        hero(detail, album: album)
                        actionRow(detail, album: album)
                        if let desc = detail.description, !desc.isEmpty {
                            descriptionBlock(desc)
                        }
                        tracklistBlock(album)
                    }
                    .padding(.horizontal, layout.pageX)
                    .padding(.vertical, 16)
                    .padding(.bottom, 48)
                }
                .mmPage()
            } else {
                ContentUnavailableView("Album not found", systemImage: "music.note")
                    .mmPage()
            }
        }
        .navigationTitle(detail?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .sheet(item: $addingToPlaylist) { ctx in
            AddToPlaylistSheet(trackIds: [ctx.trackId], trackHeaderName: ctx.trackName)
        }
        .confirmationDialog(
            "Resume playback?",
            isPresented: Binding(
                get: { resumePrompt != nil },
                set: { if !$0 { resumePrompt = nil } }),
            titleVisibility: .visible,
            presenting: resumePrompt
        ) { prompt in
            Button("Resume \"\(prompt.resumeTrackName)\"") {
                if let album = detail?.album {
                    playAlbum(album, startIndex: prompt.trackIndex, seek: prompt.positionSeconds)
                }
                resumePrompt = nil
            }
            Button("Start from beginning") {
                if let album = detail?.album { playAlbum(album, startIndex: 0, seek: 0) }
                resumePrompt = nil
            }
            Button("Cancel", role: .cancel) { resumePrompt = nil }
        }
    }

    private func load() async {
        loading = true
        do {
            detail = try await dataModel.titleDetail(id: titleId)
        } catch {
            log.warning("titleDetail failed: \(error.localizedDescription)")
            // Fall back to the cached detail (written at download
            // time) so a downloaded album is still browsable when
            // offline.
            if let cached = audioCache.cachedAlbumDetail(titleId: titleId.protoValue) {
                detail = cached
            }
        }
        loading = false
    }

    @ViewBuilder
    private func hero(_ detail: ApiTitleDetail, album: ApiAlbum) -> some View {
        let heroSide: CGFloat = layout.isWide ? 200 : 150
        HStack(alignment: .top, spacing: 16) {
            // Square 1:1 — album cover aspect, not a 2:3 movie poster.
            MMPosterTile(
                name: detail.name,
                ref: .posterFull(titleId: detail.id.protoValue),
                square: true,
                width: heroSide,
                placeholderIcon: "music.note")

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.name)
                    .font(layout.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .lineLimit(3)
                    .minimumScaleFactor(0.7)
                if let artist = album.albumArtists.first {
                    NavigationLink(value: ArtistRoute(id: artist.id, name: artist.name)) {
                        Text(artist.name)
                            .font(MarqueeTheme.Fonts.control)
                            .foregroundStyle(MarqueeTheme.Colors.accent)
                    }
                    .buttonStyle(.plain)
                }
                HStack(spacing: 8) {
                    if let year = detail.year { Text(String(year)) }
                    if let count = album.trackCount {
                        Text("•")
                        Text("\(count) track\(count == 1 ? "" : "s")")
                    }
                    if let secs = album.totalDurationSeconds {
                        Text("•")
                        Text(formatDuration(secs))
                    }
                }
                .font(MarqueeTheme.Fonts.meta)
                .foregroundStyle(MarqueeTheme.Colors.text3)
                Spacer(minLength: 0)
            }
        }
    }

    @ViewBuilder
    private func actionRow(_ detail: ApiTitleDetail, album: ApiAlbum) -> some View {
        let allUnplayable = album.tracks.allSatisfy { !$0.playable }
        let albumId = titleId.protoValue
        let isDownloaded = audioCache.isDownloaded(titleId: albumId)
        let progress = audioCache.activeDownloads[albumId]
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                Button {
                    onPlayTapped(album)
                } label: {
                    Label("Play", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.mmPrimary)
                .disabled(allUnplayable)

                Button {
                    shufflePlay(album)
                } label: {
                    Label("Shuffle", systemImage: "shuffle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.mmGhost)
                .disabled(allUnplayable)
            }
            HStack(spacing: 8) {
                Button {
                    startAlbumStation(detail, album: album)
                } label: {
                    Label("Start Station", systemImage: "dot.radiowaves.left.and.right")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.mmGhost)
                .disabled(allUnplayable)

                downloadButton(
                    detail: detail,
                    album: album,
                    isDownloaded: isDownloaded,
                    progress: progress,
                    allUnplayable: allUnplayable)
            }
        }
    }

    /// Three-state download affordance:
    ///  - Idle: cloud-down icon, taps fire `downloadAlbum`
    ///  - In flight: spinner with "{done}/{total}" track count, taps cancel
    ///  - Downloaded: checkmark.circle.fill, taps remove
    @ViewBuilder
    private func downloadButton(
        detail: ApiTitleDetail,
        album: ApiAlbum,
        isDownloaded: Bool,
        progress: AlbumDownloadProgress?,
        allUnplayable: Bool
    ) -> some View {
        if let progress {
            Button {
                audioCache.cancelDownload(titleId: titleId.protoValue)
            } label: {
                HStack(spacing: 6) {
                    ProgressView(value: progress.fraction)
                        .progressViewStyle(.circular)
                        .controlSize(.small)
                        .tint(MarqueeTheme.Colors.accent)
                    Text("\(progress.tracksCompleted)/\(progress.tracksTotal)")
                        .monospacedDigit()
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.mmGhost)
        } else if isDownloaded {
            Button {
                audioCache.deleteAlbum(titleId: titleId.protoValue)
            } label: {
                Label("Downloaded", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(MarqueeTheme.Colors.green)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.mmGhost)
        } else {
            Button {
                audioCache.downloadAlbum(detail: detail, album: album)
            } label: {
                Label("Download", systemImage: "arrow.down.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.mmGhost)
            .disabled(allUnplayable)
        }
    }

    @ViewBuilder
    private func descriptionBlock(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("About")
                .mmSectionTitle()
            Text(text)
                .font(MarqueeTheme.Fonts.body)
                .foregroundStyle(MarqueeTheme.Colors.text2)
        }
    }

    @ViewBuilder
    private func tracklistBlock(_ album: ApiAlbum) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Tracks")
                .mmSectionTitle()
            ForEach(Array(album.tracks.enumerated()), id: \.element.id) { idx, track in
                trackRow(track, index: idx, album: album)
                Divider().overlay(MarqueeTheme.Colors.hairline)
            }
        }
    }

    @ViewBuilder
    private func trackRow(_ track: ApiTrack, index: Int, album: ApiAlbum) -> some View {
        let isCurrent = audio.currentTrack?.id == track.id
        Button {
            playAlbum(album, startIndex: index, seek: 0)
        } label: {
            HStack(spacing: 12) {
                if isCurrent {
                    Image(systemName: audio.isPlaying ? "speaker.wave.2.fill" : "pause.fill")
                        .foregroundStyle(MarqueeTheme.Colors.accent)
                        .frame(width: 28)
                } else {
                    Text("\(track.trackNumber)")
                        .font(MarqueeTheme.Fonts.meta.monospacedDigit())
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                        .frame(width: 28, alignment: .trailing)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.name)
                        .font(MarqueeTheme.Fonts.body)
                        .lineLimit(1)
                        .foregroundStyle(track.playable ? MarqueeTheme.Colors.text : MarqueeTheme.Colors.text3)
                    if let subtitle = trackSubtitle(track) {
                        Text(subtitle)
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.text3)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if audioCache.isTrackDownloaded(trackId: track.id) {
                    // Subtle dot — the album-level Downloaded button
                    // already conveys the bulk state; this is for
                    // the offline-mode case where only some tracks
                    // landed.
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.caption2)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                        .accessibilityLabel("Downloaded")
                }
                if let secs = track.durationSeconds {
                    Text(formatDuration(secs))
                        .font(MarqueeTheme.Fonts.meta.monospacedDigit())
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
            .opacity(track.playable ? 1.0 : 0.5)
        }
        .buttonStyle(.plain)
        .disabled(!track.playable)
        .contextMenu {
            Button {
                addingToPlaylist = AddToPlaylistContext(trackId: track.id, trackName: track.name)
            } label: {
                Label("Add to Playlist…", systemImage: "plus.rectangle.on.folder")
            }
            Button {
                startTrackStation(track)
            } label: {
                Label("Start Station from Song", systemImage: "dot.radiowaves.left.and.right")
            }
            .disabled(!track.playable)
        }
    }

    // MARK: - Playback wiring

    /// Decides whether to prompt for resume or just play. Phase 3
    /// keeps the prompt narrow — only triggers when there's saved
    /// listening progress and the user taps the album-level Play
    /// button. Track taps always play that specific track from 0
    /// (matches Apple Music / Spotify expectations).
    private func onPlayTapped(_ album: ApiAlbum) {
        // Phase 3 doesn't yet wire GetListeningProgress; resume
        // detection lands when the listening-progress queue arrives
        // in the audio Phase 5/6. For now, play from track 1.
        playAlbum(album, startIndex: 0, seek: 0)
    }

    private func shufflePlay(_ album: ApiAlbum) {
        let queued = album.tracks
            .filter { $0.playable }
            .shuffled()
            .map { makeQueuedTrack($0, albumName: detail?.name ?? "", album: album) }
        guard !queued.isEmpty else { return }
        audio.play(tracks: queued, startingAt: 0)
    }

    private func playAlbum(_ album: ApiAlbum, startIndex: Int, seek: Double) {
        let queued = album.tracks.map {
            makeQueuedTrack($0, albumName: detail?.name ?? "", album: album)
        }
        guard !queued.isEmpty else { return }
        let safeStart = max(0, min(startIndex, queued.count - 1))
        audio.play(tracks: queued, startingAt: safeStart)
        if seek > 0 {
            // Seek runs after the player loads the item — defer one
            // run-loop turn so the AVPlayerItem exists when seek hits.
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(200))
                audio.seek(to: seek)
            }
        }
    }

    private func makeQueuedTrack(_ t: ApiTrack, albumName: String, album: ApiAlbum) -> QueuedTrack {
        let credit = t.trackArtistNames.first
            ?? album.albumArtists.first?.name
            ?? ""
        return QueuedTrack(
            id: t.id,
            titleId: t.titleId,
            title: t.name,
            albumName: albumName,
            artistName: credit,
            trackNumber: t.trackNumber,
            discNumber: t.discNumber,
            durationSeconds: t.durationSeconds)
    }

    // MARK: - Radio entry points

    private func startAlbumStation(_ detail: ApiTitleDetail, album: ApiAlbum) {
        let albumId = titleId.protoValue
        startStation(seedTrackId: nil, seedAlbumId: albumId)
    }

    private func startTrackStation(_ track: ApiTrack) {
        startStation(seedTrackId: track.id, seedAlbumId: nil)
    }

    /// Single shared path for both seed types. Issues StartRadio,
    /// converts the initial batch via the standalone-track converter
    /// (server populates title_name + title_artist_name on tracks
    /// returned this way), and hands everything to AudioPlayerManager
    /// along with the closures it needs to keep the session running.
    private func startStation(seedTrackId: Int64?, seedAlbumId: Int64?) {
        Task {
            do {
                let response = try await dataModel.startRadio(
                    seedTrackId: seedTrackId,
                    seedAlbumId: seedAlbumId)
                let initial = response.initialBatch.map(Self.makeStationQueuedTrack)
                guard !initial.isEmpty else {
                    log.warning("startRadio returned empty initial batch")
                    return
                }
                let model = dataModel
                audio.startRadio(
                    seed: response.seed,
                    sessionId: response.sessionId,
                    initialTracks: initial,
                    fetchNextBatch: { [model] sessionId, history in
                        let tracks = try await model.nextRadioBatch(
                            sessionId: sessionId, history: history)
                        return tracks.map(Self.makeStationQueuedTrack)
                    },
                    endSession: { [model] sessionId in
                        try? await model.stopRadio(sessionId: sessionId)
                    })
            } catch {
                log.warning("startRadio failed: \(error.localizedDescription)")
            }
        }
    }

    /// Build a QueuedTrack from a track returned by the radio service.
    /// `nonisolated` so the @Sendable closure handed to
    /// AudioPlayerManager can call it from any actor without dragging
    /// the @MainActor-implicit view isolation across the boundary.
    nonisolated static func makeStationQueuedTrack(_ t: ApiTrack) -> QueuedTrack {
        QueuedTrack(
            id: t.id,
            titleId: t.titleId,
            title: t.name,
            albumName: t.albumName ?? "",
            artistName: t.trackArtistNames.first ?? t.albumArtistName ?? "",
            trackNumber: t.trackNumber,
            discNumber: t.discNumber,
            durationSeconds: t.durationSeconds)
    }

    private func formatDuration(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
        return String(format: "%d:%02d", m, s)
    }

    private func trackSubtitle(_ track: ApiTrack) -> String? {
        var parts: [String] = []
        if !track.trackArtistNames.isEmpty {
            parts.append(track.trackArtistNames.joined(separator: ", "))
        }
        if let chip = TrackMetadataChip.formatted(
            bpm: track.bpm,
            timeSignature: track.timeSignature
        ) {
            parts.append(chip)
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}
