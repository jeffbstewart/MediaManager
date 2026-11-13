import SwiftUI
import MediaManagerCore
import MediaManagerProtos

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
                    // Same icon frame, spacing and padding as SidebarRow
                    // so the airplane lines up with the nav icons; the
                    // toggle keeps its label for accessibility only.
                    HStack(spacing: 12) {
                        Image(systemName: "airplane")
                            .font(.system(size: 17, weight: .semibold))
                            .frame(width: MarqueeTheme.Sizes.navIcon, height: MarqueeTheme.Sizes.navIcon)
                            .foregroundStyle(MarqueeTheme.Colors.text)
                        Text("Offline Mode")
                            .font(MarqueeTheme.Fonts.control)
                            .foregroundStyle(MarqueeTheme.Colors.text)
                        Spacer(minLength: 0)
                        Toggle("Offline Mode", isOn: Binding(
                            get: { dataModel.downloads.isOfflineMode },
                            set: { dataModel.downloads.isOfflineMode = $0 }
                        ))
                        .labelsHidden()
                    }
                    .padding(.horizontal, 12)
                    .frame(minHeight: MarqueeTheme.Sizes.pillHeight)
                    .sidebarRowChrome()
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
                    SidebarRow("Search", icon: "magnifyingglass", selected: selectedTab == .search)
                        .tag(Tab.search)
                }

                SidebarRow("Home", icon: "house", selected: selectedTab == .home)
                    .tag(Tab.home)

                Section(header: MMSectionHeader("Content")) { 
                    SidebarRow("Movies", icon: "film", selected: selectedTab == .movies)
                        .tag(Tab.movies)
                    SidebarRow("TV Shows", icon: "tv", selected: selectedTab == .tvShows)
                        .tag(Tab.tvShows)
                    SidebarRow("Books", icon: "books.vertical", selected: selectedTab == .books)
                        .tag(Tab.books)
                    SidebarRow("Music", icon: "music.note", selected: selectedTab == .music)
                        .tag(Tab.music)
                    if !isOffline {
                        // Collections + Tags rely on the server-side
                        // list endpoints (CollectionsListView /
                        // TagsListView) which throw .offline in
                        // OfflineDataModel. The per-id detail views
                        // do work offline, but reaching them from
                        // the sidebar would dead-end on the listing,
                        // so the tabs hide entirely when offline.
                        SidebarRow("Collections", icon: "square.stack", selected: selectedTab == .collections)
                            .tag(Tab.collections)
                        SidebarRow("Tags", icon: "tag", selected: selectedTab == .tags)
                            .tag(Tab.tags)
                        SidebarRow("Family", icon: "video", selected: selectedTab == .family)
                            .tag(Tab.family)
                        SidebarRow("Cameras", icon: "web.camera", selected: selectedTab == .cameras)
                            .tag(Tab.cameras)
                        SidebarRow("Live TV", icon: "antenna.radiowaves.left.and.right", selected: selectedTab == .liveTv)
                            .tag(Tab.liveTv)
                    }
                }

                if !isOffline {
                    if dataModel.userInfo?.isAdmin == true {
                        Section(header: MMSectionHeader("Catalog")) { 
                            SidebarRow("Add Title", icon: "plus.rectangle", selected: selectedTab == .adminAddTitle)
                                .tag(Tab.adminAddTitle)
                            SidebarRow("Scan Barcode", icon: "barcode.viewfinder", selected: selectedTab == .adminScan)
                                .tag(Tab.adminScan)
                            SidebarRow("Amazon Import", icon: "shippingbox", selected: selectedTab == .adminAmazonImport)
                                .tag(Tab.adminAmazonImport)
                            SidebarRow("Expand Packs", icon: "rectangle.expand.vertical", selected: selectedTab == .adminExpand)
                                .tag(Tab.adminExpand)
                            SidebarRow("Data Quality", icon: "exclamationmark.triangle", selected: selectedTab == .adminDataQuality)
                                .tag(Tab.adminDataQuality)
                            SidebarRow("Tags", icon: "tag", selected: selectedTab == .adminTags)
                                .tag(Tab.adminTags)
                        }
                        Section(header: MMSectionHeader("Transcodes")) { 
                            SidebarRow("Transcode Status", icon: "gearshape.2", selected: selectedTab == .adminStatus)
                                .tag(Tab.adminStatus)
                            SidebarRow("Linked Files", icon: "film.stack", selected: selectedTab == .adminTranscodes)
                                .tag(Tab.adminTranscodes)
                            SidebarRow("Unmatched Files", icon: "questionmark.folder", selected: selectedTab == .adminUnmatched)
                                .tag(Tab.adminUnmatched)
                        }
                        Section(header: MMSectionHeader("Purchases")) { 
                            SidebarRow("Purchase Wishes", icon: "cart", selected: selectedTab == .adminPurchaseWishes)
                                .tag(Tab.adminPurchaseWishes)
                            SidebarRow("Valuation", icon: "dollarsign.circle", selected: selectedTab == .adminValuation)
                                .tag(Tab.adminValuation)
                            SidebarRow("Inventory Report", icon: "doc.text", selected: selectedTab == .adminReport)
                                .tag(Tab.adminReport)
                        }
                        Section(header: MMSectionHeader("System")) { 
                            SidebarRow("Users", icon: "person.2", selected: selectedTab == .adminUsers)
                                .tag(Tab.adminUsers)
                            SidebarRow("Family Members", icon: "figure.2.and.child.holdinghands", selected: selectedTab == .adminFamilyMembers)
                                .tag(Tab.adminFamilyMembers)
                            SidebarRow("Cameras", icon: "web.camera", selected: selectedTab == .adminCameras)
                                .tag(Tab.adminCameras)
                            SidebarRow("Live TV Settings", icon: "antenna.radiowaves.left.and.right", selected: selectedTab == .adminLiveTvSettings)
                                .tag(Tab.adminLiveTvSettings)
                            SidebarRow("Settings", icon: "gear", selected: selectedTab == .adminSettings)
                                .tag(Tab.adminSettings)
                        }
                    }
                }

                Section {
                    if !isOffline {
                        SidebarRow("Wish List", icon: "heart", selected: selectedTab == .wishList,
                                   count: dataModel.userInfo?.fulfilledWishCount)
                            .tag(Tab.wishList)
                    }

                    if hasDownloadsCapability || dataModel.downloads.hasCompletedDownloads {
                        SidebarRow("Downloads", icon: "arrow.down.circle", selected: selectedTab == .downloads,
                                   count: dataModel.downloads.activeDownloadCount)
                            .tag(Tab.downloads)
                    }
                }

                Section {
                    SidebarRow("Profile", icon: "person.circle", selected: selectedTab == .profile)
                        .tag(Tab.profile)
                    SidebarRow("About", icon: "info.circle", selected: selectedTab == .about)
                        .tag(Tab.about)
                }

                // Connection status as the list's footer row (spec
                // footer band). A bottom-bar toolbar item would become
                // an icon-only glass button on iOS 26. Only present
                // when there's a status, so no empty row is reserved.
                if isOffline && !dataModel.downloads.isOfflineMode {
                    Section {
                        SidebarStatusRow("Server unreachable", icon: "exclamationmark.icloud",
                                         tint: MarqueeTheme.Colors.orange)
                    }
                } else if dataModel.downloads.isOfflineMode {
                    Section {
                        SidebarStatusRow("Browsing downloads only", icon: "arrow.down.circle.fill",
                                         tint: MarqueeTheme.Colors.text3)
                    }
                }
            }
            .listStyle(.sidebar)
            .environment(\.defaultMinListRowHeight, MarqueeTheme.Sizes.pillHeight)
            .listSectionSpacing(16)
            .scrollContentBackground(.hidden)
            .mmGlowPage()
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // The brand wordmark stands in for the title (web
                // shell `.brand`): display face, slash in the accent.
                ToolbarItem(placement: .principal) {
                    MMBrandWordmark(size: 22)
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
                .navigationDestination(for: BrowseRoute.self) { route in
                    // "Browse Your Library" chips on Home open the
                    // same views the sidebar tabs show, pushed.
                    Group {
                        switch route {
                        case .movies: CatalogView(typeFilter: .movie, navigationTitle: "Movies")
                        case .tvShows: CatalogView(typeFilter: .tv, navigationTitle: "TV Shows")
                        case .books: AuthorsView()
                        case .music: MusicView()
                        case .collections: CollectionsListView()
                        case .liveTv: LiveTvView()
                        }
                    }
                    .mmMeasureWidth()
                }
                .navigationDestination(for: ApiTitle.self) { title in
                    // Books get a dedicated detail page; the movie-centric
                    // TitleDetailView (Play / Seasons & Episodes / transcode
                    // controls) doesn't fit. Routing on ApiTitle.isBook
                    // keeps a single navigation surface from the catalog,
                    // search, author detail, and series detail.
                    Group {
                        if title.isBook {
                            BookDetailView(titleId: title.id)
                        } else if title.isAlbum {
                            AlbumDetailView(titleId: title.id)
                        } else {
                            TitleDetailView(titleId: title.id)
                        }
                    }
                    .mmMeasureWidth()
                }
                .navigationDestination(for: AuthorRoute.self) { route in
                    AuthorDetailView(route: route)
                        .mmMeasureWidth()
                }
                .navigationDestination(for: ArtistRoute.self) { route in
                    ArtistDetailView(route: route)
                        .mmMeasureWidth()
                }
                .navigationDestination(for: BrowseArtistsRoute.self) { _ in
                    ArtistsView()
                        .mmMeasureWidth()
                }
                .navigationDestination(for: SmartPlaylistRoute.self) { route in
                    SmartPlaylistDetailView(route: route)
                        .mmMeasureWidth()
                }
                .navigationDestination(for: PlaylistRoute.self) { route in
                    PlaylistDetailView(route: route)
                        .mmMeasureWidth()
                }
                .navigationDestination(for: AllPlaylistsRoute.self) { _ in
                    PlaylistsView()
                        .mmMeasureWidth()
                }
                .navigationDestination(for: BookSeriesRoute.self) { route in
                    BookSeriesDetailView(route: route)
                        .mmMeasureWidth()
                }
                .navigationDestination(for: BookReaderRoute.self) { route in
                    BookReaderView(route: route)
                        .mmMeasureWidth()
                }
                .navigationDestination(for: TvShowRoute.self) { route in
                    SeasonsView(route: route)
                        .mmMeasureWidth()
                }
                .navigationDestination(for: SeasonRoute.self) { route in
                    EpisodesView(route: route)
                        .mmMeasureWidth()
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
                        .mmMeasureWidth()
                }
                .navigationDestination(for: CollectionRoute.self) { route in
                    CollectionDetailView(route: route)
                        .mmMeasureWidth()
                }
                .navigationDestination(for: TagRoute.self) { route in
                    TagDetailView(route: route)
                        .mmMeasureWidth()
                }
                .navigationDestination(for: GenreRoute.self) { route in
                    GenreDetailView(route: route)
                        .mmMeasureWidth()
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
            // Column width for the poster grids (`MarqueeLayout.gridCellWidth`).
            .mmMeasureWidth()
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
        // SiriIntentHandler hands video playback off to us by setting
        // VideoPlaybackCoordinator.shared.pendingRoute from outside
        // the view hierarchy. Mirror it into our local @State so
        // .fullScreenCover fires.
        .onChange(of: VideoPlaybackCoordinator.shared.pendingRoute) { _, newRoute in
            if let newRoute {
                playbackRoute = newRoute
                VideoPlaybackCoordinator.shared.pendingRoute = nil
            }
        }
        // Mini-player is pinned inside each column (sidebar List +
        // detail NavigationStack) — see the comments at each
        // `.safeAreaInset` call for why pinning at the outer
        // NavigationSplitView level didn't propagate the inset into
        // the column's ScrollView.
    }
}

// MARK: - Sidebar rows (spec 2: `a.nav-item`, 40pt pill, 20pt icon,
// 14/600 label; active: 1px white outline, icon in the accent)

private struct SidebarRow: View {
    let label: String
    let icon: String
    let selected: Bool
    var count: Int? = nil

    init(_ label: String, icon: String, selected: Bool, count: Int? = nil) {
        self.label = label
        self.icon = icon
        self.selected = selected
        self.count = count
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .semibold))
                .frame(width: MarqueeTheme.Sizes.navIcon, height: MarqueeTheme.Sizes.navIcon)
                .foregroundStyle(selected ? MarqueeTheme.Colors.accent : MarqueeTheme.Colors.text)
            Text(label)
                .font(MarqueeTheme.Fonts.control)
                .foregroundStyle(MarqueeTheme.Colors.text)
                .lineLimit(1)
            Spacer(minLength: 0)
            if let count, count > 0 {
                Text("\(count)")
                    .font(MarqueeTheme.Fonts.badge)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(MarqueeTheme.Colors.live, in: Capsule())
            }
        }
        .padding(.horizontal, 12)
        .frame(height: MarqueeTheme.Sizes.pillHeight)
        .background(selected ? MarqueeTheme.Colors.surface : .clear, in: Capsule())
        .overlay(
            Capsule().strokeBorder(
                selected ? MarqueeTheme.Colors.navOutline : .clear, lineWidth: 1))
        .contentShape(Capsule())
        .sidebarRowChrome()
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Status line at the foot of the sidebar, on the nav rows' columns.
private struct SidebarStatusRow: View {
    let text: String
    let icon: String
    let tint: Color

    init(_ text: String, icon: String, tint: Color) {
        self.text = text
        self.icon = icon
        self.tint = tint
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .frame(width: MarqueeTheme.Sizes.navIcon, height: MarqueeTheme.Sizes.navIcon)
            Text(text)
                .font(MarqueeTheme.Fonts.meta)
            Spacer(minLength: 0)
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 12)
        .frame(minHeight: MarqueeTheme.Sizes.pillHeight)
        .sidebarRowChrome()
    }
}

private extension View {
    /// Row chrome for the sidebar: no system background or separator,
    /// so the pill drawn by the row is the only highlight.
    func sidebarRowChrome() -> some View {
        listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 2, leading: 8, bottom: 2, trailing: 8))
    }
}

#Preview {
    ContentView()
        .environment(AuthManager())
        .environment(OnlineDataModel(authManager: AuthManager(), downloadManager: DownloadManager()))
}
