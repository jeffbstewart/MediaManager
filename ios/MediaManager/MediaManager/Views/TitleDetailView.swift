import SwiftUI

struct TitleDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let titleId: TitleID
    @State private var detail: ApiTitleDetail?
    @State private var loading = true
    @State private var isFavorite = false
    @State private var isHidden = false
    @State private var mobileRequested = false

    private var isOffline: Bool { !dataModel.isOnline }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // Hero image — use backdrop, or poster for personal videos
                        if isOffline {
                            offlineImage(titleId: detail.id, name: "backdrop.jpg", fallback: "poster.jpg")
                                .frame(height: 220)
                                .frame(maxWidth: .infinity)
                        } else {
                            AuthenticatedImage(
                                path: detail.backdropUrl ?? detail.posterUrl,
                                apiClient: dataModel.apiClient,
                                cornerRadius: 0
                            )
                            .frame(height: 220)
                            .frame(maxWidth: .infinity)
                        }

                        VStack(alignment: .leading, spacing: 12) {
                            // Title + metadata
                            Text(detail.name)
                                .font(.title)
                                .fontWeight(.bold)

                            HStack(spacing: 12) {
                                if let year = detail.year {
                                    Text(String(year))
                                }
                                if let rating = detail.contentRating {
                                    Text(rating)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 4)
                                                .stroke(.secondary, lineWidth: 1)
                                        )
                                }
                                if let quality = detail.quality {
                                    Text(quality)
                                        .fontWeight(.semibold)
                                }
                                Text(detail.mediaType == .tv ? "TV Series" : "Movie")
                                    .foregroundStyle(.secondary)
                            }
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                            // Non-playable info banner (movies/personal only — TV shows have Seasons button)
                            if !detail.playable && detail.mediaType != .tv && !isOffline {
                                if detail.transcodes.isEmpty {
                                    // No file on NAS at all — disc not ripped or not released
                                    HStack(spacing: 8) {
                                        Image(systemName: detail.wished == true ? "heart.circle" : "info.circle")
                                            .foregroundStyle(detail.wished == true ? .red : .orange)
                                        Text(detail.wished == true
                                            ? "This title is on the wish list"
                                            : "This title isn't available yet")
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    }
                                    .padding(12)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .background((detail.wished == true ? Color.red : .orange).opacity(0.1))
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                } else {
                                    // File exists on NAS but hasn't been transcoded yet
                                    HStack(spacing: 8) {
                                        Image(systemName: "info.circle")
                                            .foregroundStyle(.orange)
                                        Text("This title hasn't been transcoded yet")
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    }
                                    .padding(12)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .background(.orange.opacity(0.1))
                                    .clipShape(RoundedRectangle(cornerRadius: 8))

                                    Button {
                                        Task { await requestRetranscode(detail.id) }
                                    } label: {
                                        Label("Request Transcode", systemImage: "arrow.clockwise")
                                            .frame(maxWidth: .infinity)
                                    }
                                    .buttonStyle(.bordered)
                                }
                            }

                            // Play button for movies
                            if detail.playable, detail.mediaType != .tv, let tcId = detail.transcodeId {
                                let tcHasSubs = detail.transcodes.first(where: { $0.id == tcId })?.hasSubtitles ?? false
                                // In offline mode, only show play if we have the local file
                                if !isOffline || dataModel.downloads.localFileURL(for: tcId) != nil {
                                    NavigationLink(value: PlaybackRoute(
                                        transcodeId: tcId, titleName: detail.name, episodeName: nil,
                                        hasSubtitles: tcHasSubs
                                    )) {
                                        Label("Play", systemImage: "play.fill")
                                            .frame(maxWidth: .infinity)
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .controlSize(.large)
                                }
                            }

                            // Seasons button for TV
                            if detail.mediaType == .tv {
                                NavigationLink(value: TvShowRoute(titleId: detail.id, titleName: detail.name, posterUrl: detail.posterUrl)) {
                                    Label("Seasons & Episodes", systemImage: "list.number")
                                        .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.large)
                            }

                            // Action row: favorite, hide, re-transcode, download
                            if !isOffline {
                                HStack(spacing: 20) {
                                    Button {
                                        Task { await toggleFavorite(detail.id) }
                                    } label: {
                                        Label(
                                            isFavorite ? "Favorited" : "Favorite",
                                            systemImage: isFavorite ? "star.fill" : "star"
                                        )
                                        .foregroundStyle(isFavorite ? .yellow : .secondary)
                                    }

                                    Button {
                                        Task { await toggleHidden(detail.id) }
                                    } label: {
                                        Label(
                                            isHidden ? "Hidden" : "Hide",
                                            systemImage: isHidden ? "eye.slash.fill" : "eye.slash"
                                        )
                                        .foregroundStyle(isHidden ? .red : .secondary)
                                    }

                                    if detail.playable {
                                        Button {
                                            Task { await requestRetranscode(detail.id) }
                                        } label: {
                                            Label("Re-transcode", systemImage: "arrow.clockwise")
                                                .foregroundStyle(.secondary)
                                        }
                                    }

                                    // Download area (movies only, when downloads capability available)
                                    if dataModel.capabilities.contains("downloads"),
                                       detail.mediaType != .tv,
                                       let tcId = detail.transcodeId {
                                        if detail.forMobileAvailable == true {
                                            downloadButton(detail: detail, transcodeId: tcId)
                                        } else if mobileRequested {
                                            Label("Download Requested", systemImage: "clock.arrow.circlepath")
                                                .foregroundStyle(.orange)
                                        } else {
                                            Button {
                                                Task { await requestMobileTranscode(detail.id) }
                                            } label: {
                                                Label("Request Download", systemImage: "arrow.down.circle")
                                                    .foregroundStyle(.secondary)
                                            }
                                        }
                                    }
                                }
                                .font(.subheadline)
                            }

                            // Genres
                            if !detail.genres.isEmpty {
                                FlowLayout(spacing: 6) {
                                    ForEach(detail.genres) { genre in
                                        Text(genre.name)
                                            .font(.caption)
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 4)
                                            .background(.fill.tertiary)
                                            .clipShape(Capsule())
                                    }
                                }
                            }

                            // Collection info (display only, no link when offline)
                            if let collName = detail.tmdbCollectionName {
                                if isOffline {
                                    Label(collName, systemImage: "square.stack")
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                } else if let collId = detail.tmdbCollectionId {
                                    NavigationLink(value: CollectionRoute(tmdbCollectionId: collId, name: collName)) {
                                        Label(collName, systemImage: "square.stack")
                                            .font(.subheadline)
                                    }
                                }
                            }

                            // Description
                            if let description = detail.description {
                                Text(description)
                                    .font(.body)
                            }

                            // Tags (display only)
                            if !detail.tags.isEmpty {
                                FlowLayout(spacing: 6) {
                                    ForEach(detail.tags) { tag in
                                        Text(tag.name)
                                            .font(.caption)
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 4)
                                            .background(.tint.opacity(0.15))
                                            .clipShape(Capsule())
                                    }
                                }
                            }

                            // Family members
                            if let members = detail.familyMembers, !members.isEmpty {
                                Text("People")
                                    .font(.headline)
                                    .padding(.top, 8)
                                FlowLayout(spacing: 6) {
                                    ForEach(members, id: \.self) { name in
                                        Label(name, systemImage: "person")
                                            .font(.caption)
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 4)
                                            .background(.fill.tertiary)
                                            .clipShape(Capsule())
                                    }
                                }
                            }

                            // Cast
                            if !detail.cast.isEmpty {
                                Text("Cast")
                                    .font(.headline)
                                    .padding(.top, 8)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    LazyHStack(alignment: .top, spacing: 16) {
                                        ForEach(detail.cast.prefix(20)) { member in
                                            let castContent = VStack(spacing: 6) {
                                                if isOffline {
                                                    offlineHeadshot(titleId: detail.id, personId: member.tmdbPersonId)
                                                        .frame(width: 80, height: 80)
                                                } else {
                                                    AuthenticatedImage(
                                                        path: member.headshotUrl,
                                                        apiClient: dataModel.apiClient,
                                                        cornerRadius: 40,
                                                        contentMode: .fit
                                                    )
                                                    .frame(width: 80, height: 80)
                                                }

                                                Text(member.name)
                                                    .font(.caption)
                                                    .fontWeight(.medium)
                                                    .lineLimit(2)
                                                    .multilineTextAlignment(.center)
                                                if let character = member.characterName {
                                                    Text(character)
                                                        .font(.caption2)
                                                        .foregroundStyle(.secondary)
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
                        .padding(.horizontal)
                    }
                }
            } else {
                ContentUnavailableView("Not found", systemImage: "questionmark.circle")
            }
        }
        .navigationTitle(detail?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadDetail()
        }
    }

    private func loadDetail() async {
        loading = true
        detail = try? await dataModel.titleDetail(id: titleId)
        isFavorite = detail?.isFavorite ?? false
        isHidden = detail?.isHidden ?? false
        mobileRequested = detail?.transcodes.contains(where: { $0.forMobileRequested == true }) ?? false
        loading = false
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
        let dlState = dataModel.downloads.state(for: transcodeId)

        switch dlState {
        case .completed:
            Label("Downloaded", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .downloading, .fetchingMetadata:
            if let item = dataModel.downloads.item(for: transcodeId) {
                HStack(spacing: 4) {
                    ProgressView(value: item.progress)
                        .frame(width: 40)
                    Text("\(Int(item.progress * 100))%")
                        .font(.caption2)
                }
                .foregroundStyle(.blue)
            }
        case .paused:
            Button {
                dataModel.downloads.resumeDownload(transcodeId: transcodeId)
            } label: {
                Label("Resume", systemImage: "arrow.down.circle")
                    .foregroundStyle(.orange)
            }
        case .failed:
            Button {
                dataModel.downloads.resumeDownload(transcodeId: transcodeId)
            } label: {
                Label("Retry", systemImage: "arrow.clockwise.circle")
                    .foregroundStyle(.red)
            }
        case nil:
            Button {
                dataModel.downloads.startDownload(
                    transcodeId: transcodeId,
                    titleId: detail.id,
                    titleName: detail.name,
                    posterUrl: detail.posterUrl,
                    quality: detail.quality,
                    year: detail.year
                )
            } label: {
                Label("Download", systemImage: "arrow.down.circle")
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Offline Image Helpers

    @ViewBuilder
    private func offlineImage(titleId: TitleID, name: String, fallback: String? = nil) -> some View {
        if let data = dataModel.downloads.loadCachedImage(for: titleId, name: name),
           let uiImage = UIImage(data: data) {
            Image(uiImage: uiImage)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .clipped()
        } else if let fallback,
                  let data = dataModel.downloads.loadCachedImage(for: titleId, name: fallback),
                  let uiImage = UIImage(data: data) {
            Image(uiImage: uiImage)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .clipped()
        } else {
            Rectangle()
                .fill(.quaternary)
                .overlay {
                    Image(systemName: "film")
                        .foregroundStyle(.secondary)
                }
        }
    }

    @ViewBuilder
    private func offlineHeadshot(titleId: TitleID, personId: TmdbPersonID) -> some View {
        if let data = dataModel.downloads.loadCachedImage(for: titleId, name: "headshots/\(personId.rawValue).jpg"),
           let uiImage = UIImage(data: data) {
            Image(uiImage: uiImage)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .clipShape(Circle())
        } else {
            Circle()
                .fill(.quaternary)
                .overlay {
                    Image(systemName: "person")
                        .foregroundStyle(.secondary)
                }
        }
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
