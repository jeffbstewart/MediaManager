import SwiftUI

struct EpisodesView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let route: SeasonRoute
    @State private var episodes: [ApiEpisode] = []
    @State private var loading = true

    private var isOffline: Bool { !dataModel.isOnline }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if episodes.isEmpty {
                ContentUnavailableView("No episodes", systemImage: "tv")
            } else {
                List(Array(episodes.enumerated()), id: \.element.episodeId) { index, episode in
                    let isPlayable = isOffline
                        ? (episode.transcodeId.flatMap { dataModel.downloads.localFileURL(for: $0) } != nil)
                        : (episode.playable && episode.transcodeId != nil)

                    if isPlayable, let tcId = episode.transcodeId {
                        let nextEp = findNextPlayable(after: index)
                        NavigationLink(value: PlaybackRoute(
                            transcodeId: tcId,
                            titleName: route.titleName,
                            episodeName: episode.name ?? "S\(episode.seasonNumber)E\(episode.episodeNumber)",
                            hasSubtitles: episode.hasSubtitles,
                            nextEpisode: nextEp,
                            seasonNumber: episode.seasonNumber,
                            episodeNumber: episode.episodeNumber
                        )) {
                            EpisodeRow(episode: episode, isOfflineUnavailable: isOffline && !isPlayable)
                        }
                    } else {
                        EpisodeRow(episode: episode, isOfflineUnavailable: isOffline && !isPlayable)
                    }
                }
            }
        }
        .navigationTitle(route.season.name ?? "Season \(route.season.seasonNumber)")
        .task {
            await loadEpisodes()
        }
    }

    private func findNextPlayable(after index: Int) -> NextEpisode? {
        for i in (index + 1)..<episodes.count {
            let ep = episodes[i]
            guard let tcId = ep.transcodeId else { continue }

            let playable = isOffline
                ? (dataModel.downloads.localFileURL(for: tcId) != nil)
                : ep.playable

            if playable {
                return NextEpisode(
                    transcodeId: tcId,
                    episodeName: ep.name ?? "S\(ep.seasonNumber)E\(ep.episodeNumber)",
                    hasSubtitles: ep.hasSubtitles
                )
            }
        }
        return nil
    }

    private func loadEpisodes() async {
        loading = true
        episodes = (try? await dataModel.episodes(titleId: route.titleId, season: route.season.seasonNumber)) ?? []
        loading = false
    }
}

struct EpisodeRow: View {
    let episode: ApiEpisode
    var isOfflineUnavailable: Bool = false

    var body: some View {
        HStack(spacing: 12) {
            // Episode number badge
            Text("\(episode.episodeNumber)")
                .font(.headline)
                .foregroundStyle(.secondary)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 4) {
                Text(episode.name ?? "Episode \(episode.episodeNumber)")
                    .fontWeight(.medium)

                HStack(spacing: 8) {
                    if let quality = episode.quality {
                        Text(quality)
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundStyle(.secondary)
                    }
                    if episode.hasSubtitles {
                        Label("CC", systemImage: "captions.bubble")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if isOfflineUnavailable {
                        Text("Not downloaded")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    } else if !episode.playable {
                        Text("Unavailable")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            }

            Spacer()

            // Watch progress indicator
            if episode.watchedPercent > 0 {
                ZStack {
                    Circle()
                        .stroke(.quaternary, lineWidth: 3)
                    Circle()
                        .trim(from: 0, to: Double(episode.watchedPercent) / 100.0)
                        .stroke(.tint, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    if episode.watchedPercent >= 90 {
                        Image(systemName: "checkmark")
                            .font(.caption2)
                            .fontWeight(.bold)
                    }
                }
                .frame(width: 24, height: 24)
            }
        }
        .padding(.vertical, 4)
    }
}
