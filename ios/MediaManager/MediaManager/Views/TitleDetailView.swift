import SwiftUI

struct TitleDetailView: View {
    @Environment(AuthManager.self) private var authManager
    let titleId: Int
    @State private var detail: ApiTitleDetail?
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // Hero image — use backdrop, or poster for personal videos
                        AuthenticatedImage(
                            path: detail.backdropUrl ?? detail.posterUrl,
                            apiClient: authManager.apiClient,
                            cornerRadius: 0
                        )
                        .frame(height: 220)
                        .frame(maxWidth: .infinity)

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

                            // Seasons button for TV
                            if detail.mediaType == "TV" {
                                NavigationLink(value: TvShowRoute(titleId: detail.id, titleName: detail.name)) {
                                    Label("Seasons & Episodes", systemImage: "list.number")
                                        .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.large)
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

                            // Collection link
                            if let collId = detail.tmdbCollectionId, let collName = detail.tmdbCollectionName {
                                NavigationLink(value: CollectionRoute(tmdbCollectionId: collId, name: collName)) {
                                    Label(collName, systemImage: "square.stack")
                                        .font(.subheadline)
                                }
                            }

                            // Description
                            if let description = detail.description {
                                Text(description)
                                    .font(.body)
                            }

                            // Tags
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
                                            NavigationLink(value: ActorRoute(tmdbPersonId: member.tmdbPersonId, name: member.name)) {
                                                VStack(spacing: 6) {
                                                    AuthenticatedImage(
                                                        path: member.headshotUrl,
                                                        apiClient: authManager.apiClient,
                                                        cornerRadius: 40,
                                                        contentMode: .fit
                                                    )
                                                    .frame(width: 80, height: 80)

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
                                            }
                                            .buttonStyle(.plain)
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
        detail = try? await authManager.apiClient.get("catalog/titles/\(titleId)")
        loading = false
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
