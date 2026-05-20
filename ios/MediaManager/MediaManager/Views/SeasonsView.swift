import SwiftUI

struct PlaybackRoute: Hashable, Identifiable {
    var id: TranscodeID { transcodeId }
    let transcodeId: TranscodeID
    let titleName: String
    let episodeName: String?
    var hasSubtitles: Bool = false
    var nextEpisode: NextEpisode? = nil
    var seasonNumber: Int? = nil
    var episodeNumber: Int? = nil
}

struct NextEpisode: Hashable {
    let transcodeId: TranscodeID
    let episodeName: String
    let hasSubtitles: Bool
}

struct TvShowRoute: Hashable {
    let titleId: TitleID
    let titleName: String
}

struct SeasonRoute: Hashable {
    let titleId: TitleID
    let titleName: String
    let season: ApiSeason
}

struct SeasonsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let route: TvShowRoute
    @State private var seasons: [ApiSeason] = []
    @State private var loading = true
    /// True while the bulk-download action is fanning out per-season
    /// episode fetches. Brief — typically resolves in <2s — but the
    /// row shows a spinner during it so the user knows their tap
    /// took effect before the first download appears.
    @State private var preparingBulkDownload = false

    private var isOffline: Bool { !dataModel.isOnline }

    private func isSeasonFullyDownloaded(_ season: ApiSeason) -> Bool {
        dataModel.downloads.isSeasonFullyDownloaded(
            titleId: route.titleId.protoValue,
            season: Int32(season.seasonNumber),
            expectedEpisodeCount: season.episodeCount)
    }

    private var isSeriesFullyOffline: Bool {
        !seasons.isEmpty && seasons.allSatisfy(isSeasonFullyDownloaded)
    }

    private var totalEpisodeCount: Int {
        seasons.reduce(0) { $0 + $1.episodeCount }
    }

    private var showBulkDownload: Bool {
        dataModel.capabilities.contains("downloads")
            && !seasons.isEmpty
            && !isSeriesFullyOffline
    }

    private var bulkDownloadStatus: BulkDownloadStatus {
        dataModel.downloads
            .bulkStatus(forShowId: route.titleId.protoValue, expectedTotal: totalEpisodeCount)
            .asBulkDownloadStatus
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if seasons.isEmpty {
                ContentUnavailableView("No seasons", systemImage: "tv")
            } else {
                List {
                    if isSeriesFullyOffline {
                        Section {
                            Label("Entire series available offline",
                                  systemImage: "arrow.down.circle.fill")
                                .foregroundStyle(.green)
                                .font(.subheadline)
                        }
                    } else if showBulkDownload {
                        Section {
                            if preparingBulkDownload {
                                HStack(spacing: 8) {
                                    ProgressView().controlSize(.small)
                                    Text("Preparing download…")
                                        .foregroundStyle(.secondary)
                                }
                            } else {
                                BulkDownloadActionRow(
                                    status: bulkDownloadStatus,
                                    noun: "episode",
                                    action: { Task { await startBulkSeriesDownload() } })
                            }
                        }
                    }
                    Section {
                        ForEach(seasons, id: \.seasonNumber) { season in
                            NavigationLink(value: SeasonRoute(titleId: route.titleId, titleName: route.titleName, season: season)) {
                                HStack {
                                    Text(season.name ?? "Season \(season.seasonNumber)")
                                        .fontWeight(.medium)
                                    Spacer()
                                    if isSeasonFullyDownloaded(season) {
                                        Image(systemName: "arrow.down.circle.fill")
                                            .foregroundStyle(.green)
                                            .accessibilityLabel("Available offline")
                                    }
                                    Text("\(season.episodeCount) episodes")
                                        .foregroundStyle(.secondary)
                                        .font(.subheadline)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(route.titleName)
        .task {
            await loadSeasons()
        }
    }

    private func loadSeasons() async {
        loading = true
        seasons = (try? await dataModel.seasons(titleId: route.titleId)) ?? []
        loading = false
    }

    /// "Download entire series" handler. SeasonsView only carries
    /// season summaries, so we fan out per-season episode fetches
    /// to discover transcode IDs and which episodes are actually
    /// downloadable, then kick off each. Concurrency-capped at 3
    /// seasons in flight so a 10-season series doesn't fire 10
    /// parallel `Episodes` RPCs.
    private func startBulkSeriesDownload() async {
        preparingBulkDownload = true
        defer { preparingBulkDownload = false }

        await withTaskGroup(of: [ApiEpisode].self) { group in
            var iterator = seasons.makeIterator()
            // Worker that returns the season's episode list.
            func worker(_ season: ApiSeason) async -> [ApiEpisode] {
                (try? await dataModel.episodes(titleId: route.titleId, season: season.seasonNumber)) ?? []
            }
            for _ in 0..<3 {
                guard let next = iterator.next() else { break }
                group.addTask { await worker(next) }
            }
            for await episodes in group {
                startDownloadsForEpisodes(episodes)
                if let next = iterator.next() {
                    group.addTask { await worker(next) }
                }
            }
        }
    }

    private func startDownloadsForEpisodes(_ episodes: [ApiEpisode]) {
        for ep in episodes {
            guard ep.forMobileAvailable == true, let tcId = ep.transcodeId else { continue }
            guard dataModel.downloads.state(for: tcId.protoValue) == .unknown else { continue }
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
                episodeTitle: ep.name ?? "")
        }
    }
}
