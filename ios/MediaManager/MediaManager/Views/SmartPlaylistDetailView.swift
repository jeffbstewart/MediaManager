import SwiftUI
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "SmartPlaylistDetailView")

/// Read-only detail surface for a server-defined smart playlist
/// ("Recently Added", "Most Played", etc.). Same shape as
/// AlbumDetailView's tracklist — square hero (server picks the
/// `hero_title_id`), Play/Shuffle action row, full tracklist with
/// row-tap to play from that index.
struct SmartPlaylistDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(AudioPlayerManager.self) private var audio
    @Environment(AudioCacheManager.self) private var audioCache
    @Environment(\.marquee) private var layout
    let route: SmartPlaylistRoute

    @State private var detail: ApiSmartPlaylistDetail?
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmPage()
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: layout.sectionGap) {
                        header(detail)
                        actionRow(detail)
                        tracklist(detail)
                    }
                    .padding(.horizontal, layout.pageX)
                    .padding(.vertical, 16)
                    .padding(.bottom, 48)
                }
                .mmPage()
            } else {
                ContentUnavailableView("Playlist unavailable",
                    systemImage: "exclamationmark.triangle")
                    .mmPage()
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
        let heroSide: CGFloat = layout.isWide ? 200 : 150
        HStack(alignment: .top, spacing: 16) {
            // Square — the server-picked hero album's cover.
            MMPosterTile(
                name: detail.summary.name,
                ref: detail.summary.heroTitleId.map { .posterFull(titleId: $0.protoValue) },
                square: true,
                width: heroSide,
                placeholderIcon: "sparkles")

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.summary.name)
                    .font(layout.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .lineLimit(3)
                    .minimumScaleFactor(0.7)
                if !detail.summary.description.isEmpty {
                    Text(detail.summary.description)
                        .font(MarqueeTheme.Fonts.cardTitle)
                        .foregroundStyle(MarqueeTheme.Colors.text2)
                        .lineLimit(3)
                }
                let count = detail.summary.trackCount
                Text("\(count) track\(count == 1 ? "" : "s") · \(formatDuration(Double(detail.totalDurationSeconds)))")
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.text3)
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
            .buttonStyle(.mmPrimary)
            .disabled(detail.tracks.isEmpty)

            Button {
                play(detail, startIndex: 0, shuffled: true)
            } label: {
                Label("Shuffle", systemImage: "shuffle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.mmGhost)
            .disabled(detail.tracks.isEmpty)
        }
    }

    @ViewBuilder
    private func tracklist(_ detail: ApiSmartPlaylistDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Tracks")
                .mmSectionTitle()
            ForEach(Array(detail.tracks.enumerated()), id: \.element.id) { idx, entry in
                trackRow(entry, index: idx, detail: detail)
                Divider().overlay(MarqueeTheme.Colors.hairline)
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
                    .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(MarqueeTheme.Colors.hairline, lineWidth: 1))
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.name)
                        .font(MarqueeTheme.Fonts.body)
                        .lineLimit(1)
                        .foregroundStyle(track.playable ? MarqueeTheme.Colors.text : MarqueeTheme.Colors.text3)
                    Text(subtitle(for: entry))
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                        .lineLimit(1)
                }
                Spacer()
                if audioCache.isDownloaded(titleId: entry.albumTitleId) {
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.caption)
                        .foregroundStyle(MarqueeTheme.Colors.green)
                        .accessibilityLabel("Downloaded")
                }
                if isCurrent {
                    Image(systemName: audio.isPlaying ? "speaker.wave.2.fill" : "pause.fill")
                        .foregroundStyle(MarqueeTheme.Colors.accent)
                } else if let secs = track.durationSeconds {
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
