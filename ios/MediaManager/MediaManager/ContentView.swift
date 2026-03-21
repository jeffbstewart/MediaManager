import SwiftUI

enum Tab: Hashable {
    case home, movies, tvShows, collections, tags, family, cameras, liveTv, search, wishList, admin
}

struct ContentView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var selectedTab: Tab? = .home
    @State private var playbackRoute: PlaybackRoute?

    var body: some View {
        NavigationSplitView {
            List(selection: $selectedTab) {
                Label("Home", systemImage: "house")
                    .tag(Tab.home)

                Section("Content") {
                    Label("Movies", systemImage: "film")
                        .tag(Tab.movies)
                    Label("TV Shows", systemImage: "tv")
                        .tag(Tab.tvShows)
                    Label("Collections", systemImage: "square.stack")
                        .tag(Tab.collections)
                    Label("Tags", systemImage: "tag")
                        .tag(Tab.tags)
                    Label("Family", systemImage: "video")
                        .tag(Tab.family)
                    Label("Cameras", systemImage: "web.camera")
                        .tag(Tab.cameras)
                    Label("Live TV", systemImage: "antenna.radiowaves.left.and.right")
                        .tag(Tab.liveTv)
                }

                if authManager.serverInfo?.user?.isAdmin == true {
                    Section("Admin") {
                        Label("Transcode Status", systemImage: "gearshape.2")
                            .tag(Tab.admin)
                    }
                }

                Section {
                    Label("Search", systemImage: "magnifyingglass")
                        .tag(Tab.search)
                    HStack {
                        Label("Wish List", systemImage: "heart")
                        if let count = authManager.serverInfo?.user?.fulfilledWishCount, count > 0 {
                            Spacer()
                            Text("\(count)")
                                .font(.caption2)
                                .fontWeight(.bold)
                                .foregroundStyle(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(.green)
                                .clipShape(Capsule())
                        }
                    }
                    .tag(Tab.wishList)
                }
            }
            .navigationTitle("Media Manager")
            .toolbar {
                ToolbarItem(placement: .bottomBar) {
                    Button("Sign Out") {
                        Task { await authManager.logout() }
                    }
                    .font(.callout)
                }
            }
        } detail: {
            NavigationStack {
                Group {
                    switch selectedTab {
                    case .home:
                        HomeView()
                    case .movies:
                        CatalogView(typeFilter: "MOVIE", navigationTitle: "Movies")
                    case .tvShows:
                        CatalogView(typeFilter: "TV", navigationTitle: "TV Shows")
                    case .collections:
                        CollectionsListView()
                    case .tags:
                        TagsListView()
                    case .family:
                        CatalogView(typeFilter: "PERSONAL", navigationTitle: "Family")
                    case .cameras:
                        CamerasView()
                    case .liveTv:
                        LiveTvView()
                    case .admin:
                        AdminView()
                    case .search:
                        SearchView()
                    case .wishList:
                        WishListView()
                    case nil:
                        HomeView()
                    }
                }
                .navigationDestination(for: ApiTitle.self) { title in
                    TitleDetailView(titleId: title.id)
                }
                .navigationDestination(for: TvShowRoute.self) { route in
                    SeasonsView(route: route)
                }
                .navigationDestination(for: SeasonRoute.self) { route in
                    EpisodesView(route: route)
                }
                .navigationDestination(for: PlaybackRoute.self) { route in
                    Color.clear.onAppear { playbackRoute = route }
                }
                .navigationDestination(for: ActorRoute.self) { route in
                    ActorView(route: route)
                }
                .navigationDestination(for: CollectionRoute.self) { route in
                    CollectionDetailView(route: route)
                }
                .navigationDestination(for: TagRoute.self) { route in
                    TagDetailView(route: route)
                }
                .navigationDestination(for: GenreRoute.self) { route in
                    GenreDetailView(route: route)
                }
            }
        }
        .fullScreenCover(item: $playbackRoute) { route in
            VideoPlayerView(
                transcodeId: route.transcodeId,
                titleName: route.titleName,
                episodeName: route.episodeName,
                hasSubtitles: route.hasSubtitles,
                nextEpisode: route.nextEpisode
            )
        }
    }
}

#Preview {
    ContentView()
        .environment(AuthManager())
}
