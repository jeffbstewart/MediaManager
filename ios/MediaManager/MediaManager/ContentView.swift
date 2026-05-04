import SwiftUI

enum Tab: Hashable {
    case home, movies, tvShows, books, collections, tags, family, cameras, liveTv, search, wishList, downloads, offlineLibrary, profile
    // Admin tabs
    case adminScan, adminStatus, adminCameras, adminUsers, adminPurchaseWishes, adminDataQuality
    case adminTags, adminSettings, adminTranscodes, adminUnmatched, adminAddTitle
    case adminAmazonImport, adminExpand
    case adminValuation, adminReport, adminFamilyMembers, adminLiveTvSettings
}

struct ContentView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var selectedTab: Tab? = .home
    @State private var playbackRoute: PlaybackRoute?
    @State private var navigationPath = NavigationPath()
    @State private var showLogoutConfirmation = false

    /// Downloads capability is available if the server reports it OR we've seen it before (cached).
    private var hasDownloadsCapability: Bool {
        dataModel.capabilities.contains("downloads")
        || authManager.cachedCapabilities.contains("downloads")
    }

    private var isOffline: Bool { !dataModel.isOnline }

    var body: some View {
        NavigationSplitView {
            List(selection: $selectedTab) {
                if !isOffline {
                    Label("Home", systemImage: "house")
                        .tag(Tab.home)

                    Section("Content") {
                        Label("Movies", systemImage: "film")
                            .tag(Tab.movies)
                        Label("TV Shows", systemImage: "tv")
                            .tag(Tab.tvShows)
                        Label("Books", systemImage: "books.vertical")
                            .tag(Tab.books)
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
                        Label("Search", systemImage: "magnifyingglass")
                            .tag(Tab.search)
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

                    if isOffline && dataModel.downloads.hasCompletedDownloads {
                    Label("Library", systemImage: "books.vertical")
                        .tag(Tab.offlineLibrary)
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

                if !isOffline {
                    Section {
                        Label("Profile", systemImage: "person.circle")
                            .tag(Tab.profile)
                    }
                }
            }
            .navigationTitle("Media Manager")
            .toolbar {
                ToolbarItem(placement: .bottomBar) {
                    VStack(spacing: 4) {
                        Button {
                            dataModel.downloads.isOfflineMode.toggle()
                            if dataModel.downloads.isOfflineMode {
                                let onlineOnlyTabs: Set<Tab> = [
                                    .home, .movies, .tvShows, .collections, .tags, .family,
                                    .cameras, .liveTv, .search, .wishList, .profile,
                                    .adminAddTitle, .adminAmazonImport, .adminExpand,
                                    .adminValuation, .adminReport, .adminFamilyMembers,
                                    .adminLiveTvSettings, .adminScan, .adminStatus, .adminCameras,
                                    .adminUsers, .adminPurchaseWishes,
                                    .adminDataQuality, .adminTags, .adminTranscodes,
                                    .adminUnmatched, .adminSettings
                                ]
                                if let tab = selectedTab, onlineOnlyTabs.contains(tab) {
                                    selectedTab = .downloads
                                }
                                navigationPath = NavigationPath()
                            }
                        } label: {
                            Label(
                                dataModel.downloads.isEffectivelyOffline ? "Go Online" : "Go Offline",
                                systemImage: dataModel.downloads.isEffectivelyOffline
                                    ? "wifi.slash" : "wifi"
                            )
                            .font(.callout)
                            .foregroundStyle(dataModel.downloads.isEffectivelyOffline ? .orange : .primary)
                        }

                        if isOffline && !dataModel.downloads.isOfflineMode {
                            Text("No connection")
                                .font(.caption2)
                                .foregroundStyle(.orange)
                        } else if isOffline {
                            Text("Offline mode")
                                .font(.caption2)
                                .foregroundStyle(.orange)
                        }

                        HStack(spacing: 16) {
                            Button("Sign Out") {
                                showLogoutConfirmation = true
                            }
                            .font(.callout)
                        }

                        Text("v\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?") (\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"))")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
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
                    case .offlineLibrary:
                        OfflineLibraryView()
                    case nil:
                        if isOffline && dataModel.downloads.hasCompletedDownloads {
                            DownloadsView()
                        } else {
                            HomeView()
                        }
                    }
                }
                .navigationDestination(for: ApiTitle.self) { title in
                    // Books get a dedicated detail page; the movie-centric
                    // TitleDetailView (Play / Seasons & Episodes / transcode
                    // controls) doesn't fit. Routing on ApiTitle.isBook
                    // keeps a single navigation surface from the catalog,
                    // search, author detail, and series detail.
                    if title.isBook {
                        BookDetailView(titleId: title.id)
                    } else {
                        TitleDetailView(titleId: title.id)
                    }
                }
                .navigationDestination(for: AuthorRoute.self) { route in
                    AuthorDetailView(route: route)
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
        }
        .alert("Sign Out", isPresented: $showLogoutConfirmation) {
            Button("Sign Out", role: .destructive) {
                Task { await authManager.logout() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to sign out?")
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
    }
}

#Preview {
    ContentView()
        .environment(AuthManager())
        .environment(OnlineDataModel(authManager: AuthManager(), downloadManager: DownloadManager()))
}
