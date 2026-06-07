import SwiftUI

enum Tab: Hashable {
    case home, movies, tvShows, books, music, collections, tags, family, cameras, liveTv, search, wishList, downloads, profile, about
    // Admin tabs
    case adminScan, adminStatus, adminCameras, adminUsers, adminPurchaseWishes, adminDataQuality
    case adminTags, adminSettings, adminTranscodes, adminUnmatched, adminAddTitle
    case adminAmazonImport, adminExpand
    case adminValuation, adminReport, adminFamilyMembers, adminLiveTvSettings
}

struct ContentView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(BookCacheManager.self) private var bookCache
    @Environment(AudioCacheManager.self) private var audioCache
    @State private var selectedTab: Tab? = .home
    @State private var playbackRoute: PlaybackRoute?
    @State private var navigationPath = NavigationPath()

    /// Downloads capability is available if the server reports it OR we've seen it before (cached).
    private var hasDownloadsCapability: Bool {
        dataModel.capabilities.contains("downloads")
        || authManager.cachedCapabilities.contains("downloads")
    }

    private var isOffline: Bool { !dataModel.isOnline }

    var body: some View {
        NavigationSplitView {
            List(selection: $selectedTab) {
                // Offline-mode toggle pinned to the top of the sidebar.
                // Lives here (not in ProfileView) because reaching
                // Profile fires gRPCs that fail when actually offline,
                // making the toggle unreachable just when you need it
                // most. No tag → tapping the row only flips the toggle,
                // it does not change `selectedTab`.
                Section {
                    Toggle(isOn: Binding(
                        get: { dataModel.downloads.isOfflineMode },
                        set: { dataModel.downloads.isOfflineMode = $0 }
                    )) {
                        Label("Offline Mode", systemImage: "airplane")
                    }
                }

                // Home + the catalog tabs (Movies / TV / Books / Music /
                // Collections / Tags) are visible online AND offline —
                // OfflineDataModel surfaces just the downloaded subset
                // when the server isn't reachable. Family / Cameras /
                // Live TV stay online-only because they have no offline
                // mode (personal videos rarely cached; cameras + Live
                // TV are streaming-only).
                // Search at the top of the sidebar — it's the most
                // frequent jump-anywhere entry point and was buried
                // below the catalog tabs. Online-only; offline mode
                // hides it entirely.
                if !isOffline {
                    Label("Search", systemImage: "magnifyingglass")
                        .tag(Tab.search)
                }

                Label("Home", systemImage: "house")
                    .tag(Tab.home)

                Section("Content") {
                    Label("Movies", systemImage: "film")
                        .tag(Tab.movies)
                    Label("TV Shows", systemImage: "tv")
                        .tag(Tab.tvShows)
                    Label("Books", systemImage: "books.vertical")
                        .tag(Tab.books)
                    Label("Music", systemImage: "music.note")
                        .tag(Tab.music)
                    if !isOffline {
                        // Collections + Tags rely on the server-side
                        // list endpoints (CollectionsListView /
                        // TagsListView) which throw .offline in
                        // OfflineDataModel. The per-id detail views
                        // do work offline, but reaching them from
                        // the sidebar would dead-end on the listing,
                        // so the tabs hide entirely when offline.
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
                }

                if !isOffline {
                    if dataModel.userInfo?.isAdmin == true {
                        Section("Catalog") {
                            Label("Add Title", systemImage: "plus.rectangle")
                                .tag(Tab.adminAddTitle)
                            Label("Scan Barcode", systemImage: "barcode.viewfinder")
                                .tag(Tab.adminScan)
                            Label("Amazon Import", systemImage: "shippingbox")
                                .tag(Tab.adminAmazonImport)
                            Label("Expand Packs", systemImage: "rectangle.expand.vertical")
                                .tag(Tab.adminExpand)
                            Label("Data Quality", systemImage: "exclamationmark.triangle")
                                .tag(Tab.adminDataQuality)
                            Label("Tags", systemImage: "tag")
                                .tag(Tab.adminTags)
                        }
                        Section("Transcodes") {
                            Label("Transcode Status", systemImage: "gearshape.2")
                                .tag(Tab.adminStatus)
                            Label("Linked Files", systemImage: "film.stack")
                                .tag(Tab.adminTranscodes)
                            Label("Unmatched Files", systemImage: "questionmark.folder")
                                .tag(Tab.adminUnmatched)
                        }
                        Section("Purchases") {
                            Label("Purchase Wishes", systemImage: "cart")
                                .tag(Tab.adminPurchaseWishes)
                            Label("Valuation", systemImage: "dollarsign.circle")
                                .tag(Tab.adminValuation)
                            Label("Inventory Report", systemImage: "doc.text")
                                .tag(Tab.adminReport)
                        }
                        Section("System") {
                            Label("Users", systemImage: "person.2")
                                .tag(Tab.adminUsers)
                            Label("Family Members", systemImage: "figure.2.and.child.holdinghands")
                                .tag(Tab.adminFamilyMembers)
                            Label("Cameras", systemImage: "web.camera")
                                .tag(Tab.adminCameras)
                            Label("Live TV Settings", systemImage: "antenna.radiowaves.left.and.right")
                                .tag(Tab.adminLiveTvSettings)
                            Label("Settings", systemImage: "gear")
                                .tag(Tab.adminSettings)
                        }
                    }
                }

                Section {
                    if !isOffline {
                        HStack {
                            Label("Wish List", systemImage: "heart")
                            if let count = dataModel.userInfo?.fulfilledWishCount, count > 0 {
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

                    if hasDownloadsCapability || dataModel.downloads.hasCompletedDownloads {
                        HStack {
                            Label("Downloads", systemImage: "arrow.down.circle")
                            if dataModel.downloads.activeDownloadCount > 0 {
                                Spacer()
                                Text("\(dataModel.downloads.activeDownloadCount)")
                                    .font(.caption2)
                                    .fontWeight(.bold)
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(.blue)
                                    .clipShape(Capsule())
                            }
                        }
                        .tag(Tab.downloads)
                    }
                }

                Section {
                    Label("Profile", systemImage: "person.circle")
                        .tag(Tab.profile)
                    Label("About", systemImage: "info.circle")
                        .tag(Tab.about)
                }
            }
            .navigationTitle("Household Disc Keeper")
            .toolbar {
                ToolbarItem(placement: .bottomBar) {
                    // Status-only row. The build/version number
                    // moved to AboutView — it didn't earn the
                    // prominent footer slot. The toolbar item is
                    // only present when there's a status to show
                    // so SwiftUI doesn't reserve empty chrome.
                    if isOffline && !dataModel.downloads.isOfflineMode {
                        Label("Server unreachable", systemImage: "exclamationmark.icloud")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    } else if dataModel.downloads.isOfflineMode {
                        Label("Browsing downloads only", systemImage: "arrow.down.circle.fill")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            // The bar lives inside the sidebar's List too — so on
            // iPhone compact mode (where the sidebar is the visible
            // column) the inset reaches the List that needs it.
            // MiniPlayerBar's `.frame(height: 52)` locks the layout
            // so the dual placement doesn't flex during transitions.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                MiniPlayerBar()
            }
        } detail: {
            NavigationStack(path: $navigationPath) {
                Group {
                    switch selectedTab {
                    case .home:
                        HomeView()
                    case .movies:
                        CatalogView(typeFilter: .movie, navigationTitle: "Movies")
                    case .tvShows:
                        CatalogView(typeFilter: .tv, navigationTitle: "TV Shows")
                    case .books:
                        AuthorsView()
                    case .music:
                        MusicView()
                    case .collections:
                        CollectionsListView()
                    case .tags:
                        TagsListView()
                    case .family:
                        CatalogView(typeFilter: .personal, navigationTitle: "Family")
                    case .cameras:
                        CamerasView()
                    case .liveTv:
                        LiveTvView()
                    case .profile:
                        ProfileView()
                    case .about:
                        AboutView()
                    case .adminAddTitle:
                        AddTitleView()
                    case .adminAmazonImport:
                        AmazonImportView()
                    case .adminExpand:
                        ExpandView()
                    case .adminScan:
                        BarcodeScanView()
                    case .adminStatus:
                        AdminView()
                    case .adminCameras:
                        AdminCamerasView()
                    case .adminUsers:
                        AdminUsersView()
                    case .adminPurchaseWishes:
                        AdminPurchaseWishesView()
                    case .adminDataQuality:
                        AdminDataQualityView()
                    case .adminTags:
                        AdminTagsView()
                    case .adminTranscodes:
                        AdminTranscodesView()
                    case .adminValuation:
                        ValuationView()
                    case .adminReport:
                        InventoryReportView()
                    case .adminFamilyMembers:
                        FamilyMembersView()
                    case .adminLiveTvSettings:
                        LiveTvSettingsView()
                    case .adminUnmatched:
                        AdminUnmatchedView()
                    case .adminSettings:
                        AdminSettingsView()
                    case .search:
                        SearchView()
                    case .wishList:
                        WishListView()
                    case .downloads:
                        DownloadsView()
                    case nil:
                        HomeView()
                    }
                }
                // Pin the destination's outer size to fill the
                // column regardless of which case is rendered.
                // Tab views whose body switches between a tiny
                // ProgressView (during initial load) and a full-
                // screen ScrollView (once loaded) used to cause the
                // mini-player's safeAreaInset to briefly inflate to
                // ~half the screen during the body-size transition.
                // A constant outer size keeps the column's insets
                // stable. Same idea applied per-view in AuthorsView,
                // generalized here so every tab gets it.
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .navigationDestination(for: ApiTitle.self) { title in
                    // Books get a dedicated detail page; the movie-centric
                    // TitleDetailView (Play / Seasons & Episodes / transcode
                    // controls) doesn't fit. Routing on ApiTitle.isBook
                    // keeps a single navigation surface from the catalog,
                    // search, author detail, and series detail.
                    if title.isBook {
                        BookDetailView(titleId: title.id)
                    } else if title.isAlbum {
                        AlbumDetailView(titleId: title.id)
                    } else {
                        TitleDetailView(titleId: title.id)
                    }
                }
                .navigationDestination(for: AuthorRoute.self) { route in
                    AuthorDetailView(route: route)
                }
                .navigationDestination(for: ArtistRoute.self) { route in
                    ArtistDetailView(route: route)
                }
                .navigationDestination(for: BrowseArtistsRoute.self) { _ in
                    ArtistsView()
                }
                .navigationDestination(for: SmartPlaylistRoute.self) { route in
                    SmartPlaylistDetailView(route: route)
                }
                .navigationDestination(for: PlaylistRoute.self) { route in
                    PlaylistDetailView(route: route)
                }
                .navigationDestination(for: AllPlaylistsRoute.self) { _ in
                    PlaylistsView()
                }
                .navigationDestination(for: BookSeriesRoute.self) { route in
                    BookSeriesDetailView(route: route)
                }
                .navigationDestination(for: BookReaderRoute.self) { route in
                    BookReaderView(route: route)
                }
                .navigationDestination(for: TvShowRoute.self) { route in
                    SeasonsView(route: route)
                }
                .navigationDestination(for: SeasonRoute.self) { route in
                    EpisodesView(route: route)
                }
                .navigationDestination(for: PlaybackRoute.self) { route in
                    Color.clear.onAppear {
                        playbackRoute = route
                        if !navigationPath.isEmpty {
                            navigationPath.removeLast()
                        }
                    }
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
            // Pin the mini-player inside the detail's NavigationStack
            // so its safeAreaInset propagates into the destination
            // view's ScrollView contentInsets. Outer-level placement
            // on NavigationSplitView visually positioned the bar but
            // didn't shorten the column's ScrollView, leaving tall
            // pages (MusicView's Browse Artists row, AuthorsView's
            // grid + search bar) with their last items covered by
            // the bar. Stable bar height comes from MiniPlayerBar's
            // explicit .frame(height: 52).
            .safeAreaInset(edge: .bottom, spacing: 0) {
                MiniPlayerBar()
            }
        }
        .onChange(of: dataModel.downloads.isOfflineMode) { _, newValue in
            // Flipping OFF: force a token refresh now so the next
            // gRPC call lands with a fresh access token instead of an
            // expired one. The restoreSession() offline fast path
            // schedules a 12-minute periodic refresh, which is too
            // long to wait when the user explicitly says "I'm back
            // online".
            if !newValue {
                Task { await authManager.refreshTokenNow() }
                return
            }
            // Reset to Home when offline mode flips ON, but only if
            // the user is on a tab that genuinely has no offline
            // counterpart (Family, Cameras, Live TV, Search, Wish
            // List, anything admin). Movies / TV / Books / Music /
            // Collections / Tags / Profile all work offline so the
            // user doesn't need to be evicted from them. Without
            // this reset, picking Live TV while online and then
            // flipping offline leaves the user staring at empty
            // chrome.
            let onlineOnlyTabs: Set<Tab> = [
                .collections, .tags,
                .family, .cameras, .liveTv, .search, .wishList,
                .adminAddTitle, .adminAmazonImport, .adminExpand,
                .adminValuation, .adminReport, .adminFamilyMembers,
                .adminLiveTvSettings, .adminScan, .adminStatus, .adminCameras,
                .adminUsers, .adminPurchaseWishes,
                .adminDataQuality, .adminTags, .adminTranscodes,
                .adminUnmatched, .adminSettings
            ]
            if let tab = selectedTab, onlineOnlyTabs.contains(tab) {
                selectedTab = .home
            }
            navigationPath = NavigationPath()
        }
        .fullScreenCover(item: $playbackRoute) { route in
            CustomPlayerView(
                transcodeId: route.transcodeId,
                titleName: route.titleName,
                episodeName: route.episodeName,
                hasSubtitles: route.hasSubtitles,
                nextEpisode: route.nextEpisode,
                seasonNumber: route.seasonNumber,
                episodeNumber: route.episodeNumber
            )
        }
        // Mini-player is pinned inside each column (sidebar List +
        // detail NavigationStack) — see the comments at each
        // `.safeAreaInset` call for why pinning at the outer
        // NavigationSplitView level didn't propagate the inset into
        // the column's ScrollView.
    }
}

#Preview {
    ContentView()
        .environment(AuthManager())
        .environment(OnlineDataModel(authManager: AuthManager(), downloadManager: DownloadManager()))
}
