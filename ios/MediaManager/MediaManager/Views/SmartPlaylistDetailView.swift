import SwiftUI

private let log = MMLogger(category: "SmartPlaylistDetailView")

/// Read-only detail surface for a server-defined smart playlist
/// ("Recently Added", "Most Played", etc.). Same shape as
/// AlbumDetailView's tracklist — square hero (server picks the
/// `hero_title_id`), Play/Shuffle action row, full tracklist with
/// row-tap to play from that index.
struct SmartPlaylistDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(AudioPlayerManager.self) private var audio
    let route: SmartPlaylistRoute

    @State private var detail: ApiSmartPlaylistDetail?
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        header(detail)
                        actionRow(detail)
                        tracklist(detail)
                    }
                    .padding()
                }
            } else {
                ContentUnavailableView("Playlist unavailable",
                    systemImage: "exclamationmark.triangle")
            }
        }
        .navigationTitle(route.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        loading = true
        do {
            detail = try await dataModel.smartPlaylist(key: route.key)
        } catch {
            log.warning("smartPlaylist failed: \(error.localizedDescription)")
        }
        loading = false
    }

    @ViewBuilder
    private func header(_ detail: ApiSmartPlaylistDetail) -> some View {
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
            .frame(width: 160, height: 160)  // square

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.summary.name)
                    .font(.title2)
                    .fontWeight(.bold)
                if !detail.summary.description.isEmpty {
                    Text(detail.summary.description)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
                let count = detail.summary.trackCount
                Text("\(count) track\(count == 1 ? "" : "s") · \(formatDuration(Double(detail.totalDurationSeconds)))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            }
        }
    }

    @ViewBuilder
    private func actionRow(_ detail: ApiSmartPlaylistDetail) -> some View {
        HStack(spacing: 12) {
            Button {
                play(detail, startIndex: 0, shuffled: false)
            } label: {
                Label("Play", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(detail.tracks.isEmpty)

            Button {
                play(detail, startIndex: 0, shuffled: true)
            } label: {
                Label("Shuffle", systemImage: "shuffle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(detail.tracks.isEmpty)
        }
    }

    @ViewBuilder
    private func tracklist(_ detail: ApiSmartPlaylistDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Tracks")
                .font(.headline)
            ForEach(Array(detail.tracks.enumerated()), id: \.element.id) { idx, entry in
                trackRow(entry, index: idx, detail: detail)
                Divider()
            }
        }
    }

    @ViewBuilder
    private func trackRow(_ entry: ApiPlaylistTrackEntry, index: Int, detail: ApiSmartPlaylistDetail) -> some View {
        let track = entry.track
        let isCurrent = audio.currentTrack?.id == track.id
        Button {
            play(detail, startIndex: index, shuffled: false)
        } label: {
            HStack(spacing: 12) {
                CachedImage(
                    ref: .posterThumbnail(titleId: entry.albumTitleId),
                    cornerRadius: 4)
                    .frame(width: 40, height: 40)  // square album cover
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.name)
                        .lineLimit(1)
                        .foregroundStyle(track.playable ? .primary : .secondary)
                    Text(subtitle(for: entry))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                if isCurrent {
                    Image(systemName: audio.isPlaying ? "speaker.wave.2.fill" : "pause.fill")
                        .foregroundStyle(.tint)
                } else if let secs = track.durationSeconds {
                    Text(formatDuration(secs))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .contentShape(Rectangle())
            .opacity(track.playable ? 1.0 : 0.5)
        }
        .buttonStyle(.plain)
        .disabled(!track.playable)
    }

    // MARK: - Playback wiring

    private func play(_ detail: ApiSmartPlaylistDetail, startIndex: Int, shuffled: Bool) {
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
