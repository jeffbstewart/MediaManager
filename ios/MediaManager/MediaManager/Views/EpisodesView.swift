import SwiftUI

struct EpisodesView: View {
    @Environment(AuthManager.self) private var authManager
    let route: SeasonRoute
    @State private var episodes: [ApiEpisode] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if episodes.isEmpty {
                ContentUnavailableView("No episodes", systemImage: "tv")
            } else {
                List(episodes, id: \.episodeId) { episode in
                    if episode.playable, let tcId = episode.transcodeId {
                        NavigationLink(value: PlaybackRoute(
                            transcodeId: tcId,
                            titleName: route.titleName,
                            episodeName: episode.name ?? "S\(episode.seasonNumber)E\(episode.episodeNumber)",
                            hasSubtitles: episode.hasSubtitles
                        )) {
                            EpisodeRow(episode: episode)
                        }
                    } else {
                        EpisodeRow(episode: episode)
                    }
                }
            }
        }
        .navigationTitle(route.season.name ?? "Season \(route.season.seasonNumber)")
        .task {
            await loadEpisodes()
        }
    }

    private func loadEpisodes() async {
        loading = true
        episodes = (try? await authManager.apiClient.get(
            "catalog/titles/\(route.titleId)/seasons/\(route.season.seasonNumber)/episodes"
        )) ?? []
        loading = false
    }
}

struct EpisodeRow: View {
    let episode: ApiEpisode

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
                    if !episode.playable {
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
