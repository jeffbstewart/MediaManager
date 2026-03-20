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
                        // Backdrop
                        AuthenticatedImage(path: detail.backdropUrl, apiClient: authManager.apiClient, cornerRadius: 0)
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

                            // Cast
                            if !detail.cast.isEmpty {
                                Text("Cast")
                                    .font(.headline)
                                    .padding(.top, 8)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    LazyHStack(spacing: 12) {
                                        ForEach(detail.cast.prefix(20)) { member in
                                            VStack(spacing: 4) {
                                                AuthenticatedImage(
                                                    path: member.headshotUrl,
                                                    apiClient: authManager.apiClient,
                                                    cornerRadius: 30
                                                )
                                                .frame(width: 60, height: 60)

                                                Text(member.name)
                                                    .font(.caption2)
                                                    .lineLimit(1)
                                                if let character = member.characterName {
                                                    Text(character)
                                                        .font(.caption2)
                                                        .foregroundStyle(.secondary)
                                                        .lineLimit(1)
                                                }
                                            }
                                            .frame(width: 70)
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
