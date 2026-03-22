import SwiftUI

struct TitleDetailView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(DownloadManager.self) private var downloadManager
    let titleId: Int
    @State private var detail: ApiTitleDetail?
    @State private var loading = true
    @State private var isFavorite = false
    @State private var isHidden = false

    private var isOffline: Bool { downloadManager.isEffectivelyOffline }

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
                                apiClient: authManager.apiClient,
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
                                Text(detail.mediaType == "TV" ? "TV Series" : "Movie")
                                    .foregroundStyle(.secondary)
                            }
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                            // Play button for movies
                            if detail.playable, detail.mediaType != "TV", let tcId = detail.transcodeId {
                                let tcHasSubs = detail.transcodes.first(where: { $0.id == tcId })?.hasSubtitles ?? false
                                // In offline mode, only show play if we have the local file
                                if !isOffline || downloadManager.localFileURL(for: tcId) != nil {
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
                            if detail.mediaType == "TV" {
                                NavigationLink(value: TvShowRoute(titleId: detail.id, titleName: detail.name)) {
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

                                    // Download button (movies only, when downloads capability available)
                                    if authManager.serverInfo?.capabilities.contains("downloads") == true,
                                       detail.mediaType != "TV",
                                       let tcId = detail.transcodeId {
                                        downloadButton(detail: detail, transcodeId: tcId)
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
                                                        apiClient: authManager.apiClient,
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
        if isOffline {
            // Load from cache
            detail = downloadManager.loadCachedTitleDetail(for: titleId)
        } else {
            detail = try? await authManager.apiClient.get("catalog/titles/\(titleId)")
        }
        isFavorite = detail?.isFavorite ?? false
        isHidden = detail?.isHidden ?? false
        loading = false
    }

    private func toggleFavorite(_ id: Int) async {
        isFavorite.toggle()
        do {
            if isFavorite {
                try await authManager.apiClient.put("catalog/titles/\(id)/favorite")
            } else {
                try await authManager.apiClient.delete("catalog/titles/\(id)/favorite")
            }
        } catch {
            isFavorite.toggle()
        }
    }

    private func toggleHidden(_ id: Int) async {
        isHidden.toggle()
        do {
            if isHidden {
                try await authManager.apiClient.put("catalog/titles/\(id)/hidden")
            } else {
                try await authManager.apiClient.delete("catalog/titles/\(id)/hidden")
            }
        } catch {
            isHidden.toggle()
        }
    }

    private func requestRetranscode(_ id: Int) async {
        try? await authManager.apiClient.post("catalog/titles/\(id)/request-retranscode", body: [:])
    }

    @ViewBuilder
    private func downloadButton(detail: ApiTitleDetail, transcodeId: Int) -> some View {
        let dlState = downloadManager.state(for: transcodeId)

        switch dlState {
        case .completed:
            Label("Downloaded", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .downloading, .fetchingMetadata:
            if let item = downloadManager.item(for: transcodeId) {
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
                downloadManager.resumeDownload(transcodeId: transcodeId)
            } label: {
                Label("Resume", systemImage: "arrow.down.circle")
                    .foregroundStyle(.orange)
            }
        case .failed:
            Button {
                downloadManager.resumeDownload(transcodeId: transcodeId)
            } label: {
                Label("Retry", systemImage: "arrow.clockwise.circle")
                    .foregroundStyle(.red)
            }
        case nil:
            Button {
                downloadManager.startDownload(
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
    private func offlineImage(titleId: Int, name: String, fallback: String? = nil) -> some View {
        if let data = downloadManager.loadCachedImage(for: titleId, name: name),
           let uiImage = UIImage(data: data) {
            Image(uiImage: uiImage)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .clipped()
        } else if let fallback,
                  let data = downloadManager.loadCachedImage(for: titleId, name: fallback),
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
    private func offlineHeadshot(titleId: Int, personId: Int) -> some View {
        if let data = downloadManager.loadCachedImage(for: titleId, name: "headshots/\(personId).jpg"),
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
