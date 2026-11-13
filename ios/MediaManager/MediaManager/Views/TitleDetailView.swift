import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct TitleDetailView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.marquee) private var layout
    let titleId: TitleID
    @State private var detail: ApiTitleDetail?
    @State private var loading = true
    @State private var isFavorite = false
    @State private var isHidden = false
    @State private var mobileRequested = false
    @State private var showEditTags = false
    /// Fetched lazily for TV titles so we can show a series-level
    /// "available offline" indicator next to the Seasons & Episodes
    /// button when every episode of every season has a completed
    /// download. Empty for movies / books / albums — the indicator
    /// only renders for mediaType == .tv with a non-empty list.
    @State private var seasons: [ApiSeason] = []

    private var isOffline: Bool { !dataModel.isOnline }
    private var isAdmin: Bool { dataModel.userInfo?.isAdmin == true }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmPage()
            } else if let detail {
                detailContent(detail)
            } else {
                ContentUnavailableView("Title not found", systemImage: "film")
                    .mmPage()
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadDetail() }
        .sheet(isPresented: $showEditTags) {
            if let detail {
                EditTagsSheet(titleId: detail.id.protoValue, currentTags: detail.tags) {
                    Task { await loadDetail() }
                }
            }
        }
    }

    @ViewBuilder
    private func detailContent(_ detail: ApiTitleDetail) -> some View {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        heroImage(detail: detail)

                        VStack(alignment: .leading, spacing: 12) {
                            // Title + metadata
                            Text(detail.name)
                                .font(layout.hero)
                                .foregroundStyle(MarqueeTheme.Colors.text)

                            HStack(spacing: 10) {
                                if let rating = detail.contentRating {
                                    MMRatingBox(rating: rating)
                                }
                                if let year = detail.year {
                                    Text(String(year))
                                }
                                if let quality = detail.quality {
                                    Text(quality)
                                }
                                Text(detail.mediaType == .tv ? "TV Series" : "Movie")
                            }
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.text2)

                            // Non-playable info banner (movies/personal only — TV shows have Seasons button)
                            if !detail.playable && detail.mediaType != .tv && !isOffline {
                                if detail.transcodes.isEmpty {
                                    // No file on NAS at all — disc not ripped or not released
                                    MMNotice(
                                        text: detail.wished == true
                                            ? "This title is on the wish list"
                                            : "This title isn't available yet",
                                        systemImage: detail.wished == true ? "heart.circle" : "info.circle",
                                        tint: detail.wished == true ? MarqueeTheme.Colors.pink : MarqueeTheme.Colors.accent)
                                } else {
                                    // File exists on NAS but hasn't been transcoded yet
                                    MMNotice(text: "This title hasn't been transcoded yet")

                                    Button {
                                        Task { await requestRetranscode(detail.id) }
                                    } label: {
                                        Label("Request Transcode", systemImage: "arrow.clockwise")
                                            .frame(maxWidth: .infinity)
                                    }
                                    .buttonStyle(.mmGhost)
                                }
                            }

                            // Play button for movies
                            if detail.playable, detail.mediaType != .tv, let tcId = detail.transcodeId {
                                let tcHasSubs = detail.transcodes.first(where: { $0.id == tcId })?.hasSubtitles ?? false
                                // In offline mode, only show play if we have the local file
                                if !isOffline || dataModel.downloads.localVideoURL(for: tcId.protoValue) != nil {
                                    NavigationLink(value: PlaybackRoute(
                                        transcodeId: tcId, titleName: detail.name, episodeName: nil,
                                        hasSubtitles: tcHasSubs
                                    )) {
                                        Label("Play", systemImage: "play.fill")
                                            .frame(maxWidth: .infinity)
                                    }
                                    .buttonStyle(.mmPrimary)
                                }
                            }

                            // Seasons button for TV
                            if detail.mediaType == .tv {
                                NavigationLink(value: TvShowRoute(titleId: detail.id, titleName: detail.name)) {
                                    Label("Seasons & Episodes", systemImage: "list.number")
                                        .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.mmPrimary)

                                // Surfaced when every episode of every
                                // season has a completed download —
                                // mirrors the per-season indicator in
                                // SeasonsView so the same fact reads
                                // the same in both places.
                                if isSeriesFullyOffline {
                                    Label("Available offline",
                                          systemImage: "arrow.down.circle.fill")
                                        .foregroundStyle(MarqueeTheme.Colors.green)
                                        .font(MarqueeTheme.Fonts.meta)
                                }
                            }

                            // Action row: favorite, hide, re-transcode, download
                            if !isOffline {
                                // Pills wrap onto further lines rather than
                                // scrolling off the page.
                                FlowLayout(spacing: 8) {
                                    Button {
                                        Task { await toggleFavorite(detail.id) }
                                    } label: {
                                        Label(
                                            isFavorite ? "Favorited" : "Favorite",
                                            systemImage: isFavorite ? "star.fill" : "star"
                                        )
                                        .foregroundStyle(isFavorite ? MarqueeTheme.Colors.accent : MarqueeTheme.Colors.text)
                                    }
                                    .buttonStyle(.mmGhost)

                                    Button {
                                        Task { await toggleHidden(detail.id) }
                                    } label: {
                                        Label(
                                            isHidden ? "Hidden" : "Hide",
                                            systemImage: isHidden ? "eye.slash.fill" : "eye.slash"
                                        )
                                        .foregroundStyle(isHidden ? MarqueeTheme.Colors.pink : MarqueeTheme.Colors.text)
                                    }
                                    .buttonStyle(.mmGhost)

                                    if detail.playable {
                                        Button {
                                            Task { await requestRetranscode(detail.id) }
                                        } label: {
                                            Label("Re-transcode", systemImage: "arrow.clockwise")
                                        }
                                        .buttonStyle(.mmGhost)
                                    }

                                    // Download area (movies only, when downloads capability available)
                                    if dataModel.capabilities.contains("downloads"),
                                       detail.mediaType != .tv,
                                       let tcId = detail.transcodeId {
                                        if detail.forMobileAvailable == true {
                                            downloadButton(detail: detail, transcodeId: tcId)
                                        } else if mobileRequested {
                                            MMChip(label: "Download Requested", systemImage: "clock.arrow.circlepath")
                                        } else {
                                            Button {
                                                Task { await requestMobileTranscode(detail.id) }
                                            } label: {
                                                Label("Prepare Download", systemImage: "gearshape.arrow.triangle.2.circlepath")
                                            }
                                            .buttonStyle(.mmGhost)
                                        }
                                    }
                                }
                            }

                            // Genres
                            if !detail.genres.isEmpty {
                                FlowLayout(spacing: 6) {
                                    ForEach(detail.genres) { genre in
                                        MMTag(label: genre.name)
                                    }
                                }
                            }

                            // Collection info (display only, no link when offline)
                            if let collName = detail.tmdbCollectionName {
                                if isOffline {
                                    Label(collName, systemImage: "square.stack")
                                        .font(MarqueeTheme.Fonts.control)
                                        .foregroundStyle(MarqueeTheme.Colors.text3)
                                } else if let collId = detail.tmdbCollectionId {
                                    NavigationLink(value: CollectionRoute(tmdbCollectionId: collId, name: collName)) {
                                        Label(collName, systemImage: "square.stack")
                                            .font(MarqueeTheme.Fonts.control)
                                            .foregroundStyle(MarqueeTheme.Colors.accent)
                                    }
                                }
                            }

                            // Description
                            if let description = detail.description {
                                Text(description)
                                    .font(MarqueeTheme.Fonts.body)
                                    .foregroundStyle(MarqueeTheme.Colors.text2)
                            }

                            // Tags
                            if !detail.tags.isEmpty || isAdmin {
                                HStack {
                                    FlowLayout(spacing: 6) {
                                        ForEach(detail.tags) { tag in
                                            TagChip(name: tag.name, hexColor: tag.color)
                                        }
                                    }
                                    if isAdmin {
                                        Spacer()
                                        Button {
                                            showEditTags = true
                                        } label: {
                                            Image(systemName: "pencil.circle")
                                                .font(.title3)
                                        }
                                    }
                                }
                            }

                            // Family members
                            if let members = detail.familyMembers, !members.isEmpty {
                                Text("People")
                                    .mmSectionTitle()
                                    .padding(.top, 8)
                                FlowLayout(spacing: 6) {
                                    ForEach(members, id: \.self) { name in
                                        MMTag(label: name, systemImage: "person")
                                    }
                                }
                            }

                            // Cast
                            if !detail.cast.isEmpty {
                                Text("Cast")
                                    .mmSectionTitle()
                                    .padding(.top, 8)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    LazyHStack(alignment: .top, spacing: 16) {
                                        ForEach(detail.cast.prefix(20)) { member in
                                            let castContent = VStack(spacing: 6) {
                                                ZStack {
                                                    MMPlaceholderArt(name: member.name, systemImage: "person.fill")
                                                    CachedImage(
                                                        ref: .headshot(tmdbPersonId: member.tmdbPersonId.protoValue),
                                                        cornerRadius: 0,
                                                        transparentPlaceholder: true
                                                    )
                                                }
                                                .frame(width: 80, height: 80)
                                                .clipShape(Circle())
                                                .overlay(Circle().strokeBorder(MarqueeTheme.Colors.hairline, lineWidth: 1))

                                                Text(member.name)
                                                    .font(MarqueeTheme.Fonts.cardTitle)
                                                    .foregroundStyle(MarqueeTheme.Colors.text)
                                                    .lineLimit(2)
                                                    .multilineTextAlignment(.center)
                                                if let character = member.characterName {
                                                    Text(character)
                                                        .font(MarqueeTheme.Fonts.meta)
                                                        .foregroundStyle(MarqueeTheme.Colors.text3)
                                                        .lineLimit(2)
                                                        .multilineTextAlignment(.center)
                                                }
                                            }
                                            .frame(width: 90)

                                            if isOffline {
                                                // No navigation to actor page when offline
                                                castContent
                                            } else {
                                                NavigationLink(value: ActorRoute(tmdbPersonId: member.tmdbPersonId, name: member.name)) {
                                                    castContent
                                                }
                                                .buttonStyle(.plain)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        .padding(.horizontal, layout.pageX)
                        .padding(.bottom, 48)
                        // Overlap the backdrop's fade so the title sits
                        // on the image's tail instead of under a band of
                        // page colour (spec 3.4).
                        .padding(.top, detail.backdropUrl != nil ? -72 : 0)
                    }
                }
                // With a backdrop the page starts under the (transparent)
                // navigation bar so the image runs to the top edge, as the
                // spec's hero does; the poster layout keeps the inset.
                .ignoresSafeArea(edges: detail.backdropUrl != nil ? .top : [])
                .mmPage()
    }

    private func loadDetail() async {
        loading = true
        detail = try? await dataModel.titleDetail(id: titleId)
        isFavorite = detail?.isFavorite ?? false
        isHidden = detail?.isHidden ?? false
        mobileRequested = detail?.transcodes.contains(where: { $0.forMobileRequested == true }) ?? false
        // Seasons aren't carried on TitleDetail — fetch them
        // separately for TV titles so the "Available offline"
        // indicator can render. Movies / books / albums skip this.
        if detail?.mediaType == .tv {
            seasons = (try? await dataModel.seasons(titleId: titleId)) ?? []
        } else {
            seasons = []
        }
        loading = false
    }

    private var isSeriesFullyOffline: Bool {
        guard !seasons.isEmpty else { return false }
        return seasons.allSatisfy { season in
            dataModel.downloads.isSeasonFullyDownloaded(
                titleId: titleId.protoValue,
                season: Int32(season.seasonNumber),
                expectedEpisodeCount: season.episodeCount)
        }
    }

    private func toggleFavorite(_ id: TitleID) async {
        isFavorite.toggle()
        do {
            try await dataModel.setFavorite(titleId: id, favorite: isFavorite)
        } catch {
            isFavorite.toggle()
        }
    }

    private func toggleHidden(_ id: TitleID) async {
        isHidden.toggle()
        do {
            try await dataModel.setHidden(titleId: id, hidden: isHidden)
        } catch {
            isHidden.toggle()
        }
    }

    private func requestRetranscode(_ id: TitleID) async {
        try? await dataModel.requestRetranscode(titleId: id)
        mobileRequested = true
    }

    private func requestMobileTranscode(_ id: TitleID) async {
        do {
            try await dataModel.requestMobileTranscode(titleId: id)
            mobileRequested = true
        } catch {
            // Already requested or already available — treat as requested
            mobileRequested = true
        }
    }

    @ViewBuilder
    private func downloadButton(detail: ApiTitleDetail, transcodeId: TranscodeID) -> some View {
        let dlState = dataModel.downloads.state(for: transcodeId.protoValue)

        switch dlState {
        case .completed:
            MMChip(label: "Downloaded", systemImage: "checkmark.circle.fill")
        case .downloading, .fetchingMetadata:
            if let entry = dataModel.downloads.entries.first(where: { $0.transcodeID == transcodeId.protoValue }) {
                HStack(spacing: 8) {
                    ProgressView(value: entry.progress)
                        .tint(MarqueeTheme.Colors.accent)
                        .frame(width: 48)
                    Text("\(Int(entry.progress * 100))%")
                }
                .font(MarqueeTheme.Fonts.control)
                .padding(.horizontal, 16)
                .frame(height: MarqueeTheme.Sizes.pillHeight)
                .background(MarqueeTheme.Colors.surface, in: Capsule())
            }
        case .paused:
            Button {
                dataModel.downloads.resumeDownload(transcodeId: transcodeId.protoValue)
            } label: {
                Label("Resume Download", systemImage: "arrow.down.circle")
            }
            .buttonStyle(.mmGhost)
        case .failed:
            Button {
                dataModel.downloads.resumeDownload(transcodeId: transcodeId.protoValue)
            } label: {
                Label("Retry Download", systemImage: "arrow.clockwise.circle")
                    .foregroundStyle(MarqueeTheme.Colors.pink)
            }
            .buttonStyle(.mmGhost)
        case .unknown, .queued, .UNRECOGNIZED:
            Button {
                dataModel.downloads.startDownload(
                    transcodeId: transcodeId.protoValue,
                    titleId: detail.id.protoValue,
                    titleName: detail.name,
                    quality: detail.proto.title.quality.downloadQuality,
                    year: Int32(detail.year ?? 0),
                    mediaType: detail.proto.title.mediaType,
                    contentRating: detail.proto.title.contentRating
                )
            } label: {
                Label("Download", systemImage: "arrow.down.circle")
            }
            .buttonStyle(.mmGhost)
        }
    }

    /// The backdrop bleeds under the navigation bar and fades into
    /// the page colour at the bottom (spec 3.4's hero fade), so the
    /// title block sits on the image's tail rather than under a hard
    /// edge. Without a backdrop the full poster stands in, framed.
    @ViewBuilder
    private func heroImage(detail: ApiTitleDetail) -> some View {
        if detail.backdropUrl != nil {
            // A clear base sized to the page, with the image as an
            // overlay: a resizable image in `.fill` mode would report
            // its own 16:9 width and widen the whole column.
            Color.clear
                .frame(height: layout.isWide ? 400 : 300)
                .frame(maxWidth: .infinity)
                .overlay {
                    ZStack {
                        MMPlaceholderArt(name: detail.name, systemImage: "film")
                        CachedImage(ref: .backdrop(titleId: detail.id.protoValue), cornerRadius: 0, transparentPlaceholder: true)
                    }
                }
                .clipped()
            // A long fade so light backdrops don't end in a visible
            // cut; the title block below overlaps its tail.
            .overlay(
                LinearGradient(
                    stops: [
                        .init(color: .clear, location: 0.2),
                        .init(color: MarqueeTheme.Colors.page.opacity(0.45), location: 0.6),
                        .init(color: MarqueeTheme.Colors.page.opacity(0.9), location: 0.85),
                        .init(color: MarqueeTheme.Colors.page, location: 1),
                    ],
                    startPoint: .top, endPoint: .bottom))
        } else {
            HStack {
                Spacer()
                MMPosterTile(
                    name: detail.name,
                    ref: .posterFull(titleId: detail.id.protoValue),
                    width: layout.isWide ? 200 : 150)
                Spacer()
            }
            .padding(.vertical, 24)
        }
    }

    // MARK: - Offline Image Helpers

}

struct TagChip: View {
    let name: String
    let hexColor: String

    var body: some View {
        let tagColor: Color = Color(hex: hexColor) ?? MarqueeTheme.Colors.blue
        MMTag(label: name, tint: tagColor)
    }
}

/// Simple flow layout for tags/genres that wraps to the next line.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let result = layout(proposal: proposal, subviews: subviews)
        return result.size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = layout(proposal: proposal, subviews: subviews)
        for (index, position) in result.positions.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + position.x, y: bounds.minY + position.y),
                                  proposal: .unspecified)
        }
    }

    private func layout(proposal: ProposedViewSize, subviews: Subviews) -> (size: CGSize, positions: [CGPoint]) {
        let maxWidth = proposal.width ?? .infinity
        var positions: [CGPoint] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth && x > 0 {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            positions.append(CGPoint(x: x, y: y))
            rowHeight = max(rowHeight, size.height)
            x += size.width + spacing
        }

        return (CGSize(width: maxWidth, height: y + rowHeight), positions)
    }
}
