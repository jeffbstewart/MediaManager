import SwiftUI

struct PlaybackRoute: Hashable, Identifiable {
    var id: Int { transcodeId }
    let transcodeId: Int
    let titleName: String
    let episodeName: String?
    var hasSubtitles: Bool = false
}

struct TvShowRoute: Hashable {
    let titleId: Int
    let titleName: String
}

struct SeasonRoute: Hashable {
    let titleId: Int
    let titleName: String
    let season: ApiSeason
}

struct SeasonsView: View {
    @Environment(AuthManager.self) private var authManager
    let route: TvShowRoute
    @State private var seasons: [ApiSeason] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if seasons.isEmpty {
                ContentUnavailableView("No seasons", systemImage: "tv")
            } else {
                List(seasons, id: \.seasonNumber) { season in
                    NavigationLink(value: SeasonRoute(titleId: route.titleId, titleName: route.titleName, season: season)) {
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
        seasons = (try? await authManager.apiClient.get("catalog/titles/\(route.titleId)/seasons")) ?? []
        loading = false
    }
}
