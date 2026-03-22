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
    var posterUrl: String? = nil
}

struct SeasonRoute: Hashable {
    let titleId: TitleID
    let titleName: String
    let season: ApiSeason
    var posterUrl: String? = nil
}

struct SeasonsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let route: TvShowRoute
    @State private var seasons: [ApiSeason] = []
    @State private var loading = true

    private var isOffline: Bool { !dataModel.isOnline }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if seasons.isEmpty {
                ContentUnavailableView("No seasons", systemImage: "tv")
            } else {
                List(seasons, id: \.seasonNumber) { season in
                    NavigationLink(value: SeasonRoute(titleId: route.titleId, titleName: route.titleName, season: season, posterUrl: route.posterUrl)) {
                        HStack {
                            Text(season.name ?? "Season \(season.seasonNumber)")
                                .fontWeight(.medium)
                            Spacer()
                            Text("\(season.episodeCount) episodes")
                                .foregroundStyle(.secondary)
                                .font(.subheadline)
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
}
