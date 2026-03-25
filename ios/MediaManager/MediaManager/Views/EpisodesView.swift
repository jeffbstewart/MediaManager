import SwiftUI

struct EpisodesView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let route: SeasonRoute
    @State private var episodes: [ApiEpisode] = []
    @State private var loading = true

    private var isOffline: Bool { !dataModel.isOnline }

    private var showDownloads: Bool {
        dataModel.capabilities.contains("downloads") && !isOffline
    }

    /// Episodes in this season that have ForMobile available but aren't downloaded yet.
    private var downloadableEpisodes: [ApiEpisode] {
        episodes.filter { ep in
            guard ep.forMobileAvailable == true, let tcId = ep.transcodeId else { return false }
            return dataModel.downloads.state(for: tcId.protoValue) == .unknown
        }
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if episodes.isEmpty {
                ContentUnavailableView("No episodes", systemImage: "tv")
            } else {
                List {
                    // Download Season button
                    if showDownloads && !downloadableEpisodes.isEmpty {
                        Section {
                            Button {
                                downloadSeason()
                            } label: {
                                Label(
                                    "Download Season (\(downloadableEpisodes.count) episodes)",
                                    systemImage: "arrow.down.circle"
                                )
                            }
                        }
                    }

                    Section {
                        ForEach(Array(episodes.enumerated()), id: \.element.episodeId) { index, episode in
                            let isPlayable = isOffline
                                ? (episode.transcodeId.flatMap { dataModel.downloads.localVideoURL(for: $0.protoValue) } != nil)
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
                                    EpisodeRow(
                                        episode: episode,
                                        titleId: route.titleId,
                                        titleName: route.titleName,
                                        posterUrl: route.posterUrl,
                                        isOfflineUnavailable: isOffline && !isPlayable
                                    )
                                }
                            } else {
                                EpisodeRow(
                                    episode: episode,
                                    titleId: route.titleId,
                                    titleName: route.titleName,
                                    posterUrl: route.posterUrl,
                                    isOfflineUnavailable: isOffline && !isPlayable
                                )
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(route.season.name ?? "Season \(route.season.seasonNumber)")
        .task {
            await loadEpisodes()
        }
    }

    private func downloadSeason() {
        for ep in downloadableEpisodes {
            guard let tcId = ep.transcodeId else { continue }
            dataModel.downloads.startDownload(
                transcodeId: tcId.protoValue,
                titleId: route.titleId.protoValue,
                titleName: route.titleName,
                quality: .unknown,
                year: 0,
                mediaType: .tv,
                contentRating: .unknown,
                seasonNumber: Int32(ep.seasonNumber),
                episodeNumber: Int32(ep.episodeNumber),
                episodeTitle: ep.name ?? ""
            )
        }
    }

    private func findNextPlayable(after index: Int) -> NextEpisode? {
        for i in (index + 1)..<episodes.count {
            let ep = episodes[i]
            guard let tcId = ep.transcodeId else { continue }

            let playable = isOffline
                ? (dataModel.downloads.localVideoURL(for: tcId.protoValue) != nil)
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
    @Environment(OnlineDataModel.self) private var dataModel
    let episode: ApiEpisode
    let titleId: TitleID
    let titleName: String
    let posterUrl: String?
    var isOfflineUnavailable: Bool = false
    @State private var mobileRequested: Bool

    init(episode: ApiEpisode, titleId: TitleID, titleName: String, posterUrl: String?,
         isOfflineUnavailable: Bool = false) {
        self.episode = episode
        self.titleId = titleId
        self.titleName = titleName
        self.posterUrl = posterUrl
        self.isOfflineUnavailable = isOfflineUnavailable
        self._mobileRequested = State(initialValue: episode.forMobileRequested ?? false)
    }

    private var showDownloads: Bool {
        dataModel.capabilities.contains("downloads") && dataModel.isOnline
    }

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

            // Per-episode download status
            if showDownloads, let tcId = episode.transcodeId {
                episodeDownloadIndicator(transcodeId: tcId)
            }

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

    @ViewBuilder
    private func episodeDownloadIndicator(transcodeId: TranscodeID) -> some View {
        let dlState = dataModel.downloads.state(for: transcodeId.protoValue)

        if dlState == .completed {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .font(.body)
        } else if dlState == .downloading || dlState == .fetchingMetadata {
            ProgressView()
                .controlSize(.small)
        } else if dlState == .failed {
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundStyle(.red)
                .font(.body)
        } else if dlState == .paused {
            Image(systemName: "pause.circle.fill")
                .foregroundStyle(.orange)
                .font(.body)
        } else if episode.forMobileAvailable == true {
            Button {
                dataModel.downloads.startDownload(
                    transcodeId: transcodeId.protoValue,
                    titleId: titleId.protoValue,
                    titleName: titleName,
                    quality: .unknown,
                    year: 0,
                    mediaType: .tv,
                    contentRating: .unknown,
                    seasonNumber: Int32(episode.seasonNumber),
                    episodeNumber: Int32(episode.episodeNumber),
                    episodeTitle: episode.name ?? ""
                )
            } label: {
                Image(systemName: "arrow.down.circle")
                    .foregroundStyle(.secondary)
                    .font(.body)
            }
            .buttonStyle(.plain)
        } else if mobileRequested {
            Image(systemName: "clock.arrow.circlepath")
                .foregroundStyle(.orange)
                .font(.caption)
        }
    }
}
