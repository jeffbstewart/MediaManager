import SwiftUI

private let log = MMLogger(category: "TrackSearchResultsView")

/// Navigation destination for advanced-search submit. Renders a
/// playable tracklist of `searchTracks` hits — Play All / Shuffle
/// All header buttons make the result set behave like a queueable
/// playlist (the typical dance-prep / DJ workflow).
struct TrackSearchResultsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(AudioPlayerManager.self) private var audio
    let filters: AdvancedTrackSearchFilters

    @State private var hits: [ApiTrackSearchHit] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading…")
            } else if hits.isEmpty {
                ContentUnavailableView(
                    "No tracks match",
                    systemImage: "music.note.list",
                    description: Text(filtersSummary).font(.body))
            } else {
                List {
                    Section {
                        actionRow
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    } header: {
                        Text(filtersSummary)
                            .font(.caption)
                            .textCase(nil)
                    }
                    Section {
                        ForEach(Array(hits.enumerated()), id: \.element.id) { index, hit in
                            Button {
                                play(startIndex: index, shuffled: false)
                            } label: {
                                trackRow(hit, index: index)
                            }
                            .buttonStyle(.plain)
                            .disabled(!hit.playable)
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Track Results")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private var actionRow: some View {
        HStack(spacing: 12) {
            Button {
                play(startIndex: 0, shuffled: false)
            } label: {
                Label("Play All", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(playableHits.isEmpty)

            Button {
                play(startIndex: 0, shuffled: true)
            } label: {
                Label("Shuffle", systemImage: "shuffle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(playableHits.isEmpty)
        }
    }

    @ViewBuilder
    private func trackRow(_ hit: ApiTrackSearchHit, index: Int) -> some View {
        let isCurrent = audio.currentTrack?.id == hit.trackId
        HStack(spacing: 12) {
            CachedImage(
                ref: .posterThumbnail(titleId: hit.titleId),
                cornerRadius: 4)
                .frame(width: 40, height: 40)  // square — album art aspect

            VStack(alignment: .leading, spacing: 2) {
                Text(hit.name)
                    .lineLimit(1)
                    .foregroundStyle(hit.playable ? .primary : .secondary)
                Text(subtitle(for: hit))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            if isCurrent {
                Image(systemName: audio.isPlaying ? "speaker.wave.2.fill" : "pause.fill")
                    .foregroundStyle(.tint)
            } else if let secs = hit.durationSeconds {
                Text(formatDuration(Double(secs)))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
        .opacity(hit.playable ? 1.0 : 0.5)
    }

    /// "Album · Artist · 120 BPM · 4/4" / "Album · 120 BPM" — same
    /// shape AlbumDetailView and PlaylistDetailView use.
    private func subtitle(for hit: ApiTrackSearchHit) -> String {
        var parts: [String] = []
        if !hit.albumName.isEmpty { parts.append(hit.albumName) }
        if let artist = hit.artistName, !artist.isEmpty { parts.append(artist) }
        if let chip = TrackMetadataChip.formatted(
            bpm: hit.bpm, timeSignature: hit.timeSignature)
        {
            parts.append(chip)
        }
        return parts.joined(separator: " · ")
    }

    /// Header summary line — "Salsa, 180–220 BPM, 4/4 · 27 results".
    /// Reads the filter record + result count so users can confirm
    /// what they searched for at a glance.
    private var filtersSummary: String {
        var parts: [String] = []
        if let q = filters.query, !q.isEmpty { parts.append("\u{201C}\(q)\u{201D}") }
        switch (filters.bpmMin, filters.bpmMax) {
        case let (l?, h?): parts.append("\(l)\u{2013}\(h) BPM")
        case let (l?, nil): parts.append("≥ \(l) BPM")
        case let (nil, h?): parts.append("≤ \(h) BPM")
        case (nil, nil): break
        }
        if let ts = filters.timeSignature, !ts.isEmpty { parts.append(ts) }
        let header = parts.joined(separator: " · ")
        let count = hits.count
        let suffix = "\(count) \(count == 1 ? "track" : "tracks")"
        return header.isEmpty ? suffix : "\(header) · \(suffix)"
    }

    private var playableHits: [ApiTrackSearchHit] {
        hits.filter { $0.playable }
    }

    // MARK: - Playback

    private func play(startIndex: Int, shuffled: Bool) {
        let pool = playableHits
        guard !pool.isEmpty else { return }
        let queue = shuffled ? pool.shuffled() : pool
        // If the user shuffled, startIndex is meaningless; otherwise
        // map the unshuffled index onto the playable subset.
        let safeStart: Int
        if shuffled {
            safeStart = 0
        } else {
            // Find the position within the playable subset that
            // corresponds to the tapped row in the full list.
            let target = hits[startIndex]
            safeStart = queue.firstIndex(where: { $0.trackId == target.trackId }) ?? 0
        }
        let queued = queue.map(makeQueuedTrack)
        audio.play(tracks: queued, startingAt: safeStart)
    }

    private func makeQueuedTrack(_ hit: ApiTrackSearchHit) -> QueuedTrack {
        QueuedTrack(
            id: hit.trackId,
            titleId: hit.titleId,
            title: hit.name,
            albumName: hit.albumName,
            artistName: hit.artistName ?? "",
            // Track number + disc number aren't on TrackSearchHit; the
            // queue only uses them for the lock-screen "track 3 of 12"
            // chrome which doesn't apply to a search-curated queue.
            trackNumber: 0,
            discNumber: 0,
            durationSeconds: hit.durationSeconds.map(Double.init))
    }

    // MARK: - Load

    private func load() async {
        loading = true
        do {
            hits = try await dataModel.searchTracks(filters: filters)
        } catch {
            log.warning("searchTracks failed: \(error.localizedDescription)")
            hits = []
        }
        loading = false
    }

    private func formatDuration(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
        return String(format: "%d:%02d", m, s)
    }
}
