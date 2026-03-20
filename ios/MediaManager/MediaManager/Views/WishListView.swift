import SwiftUI

struct WishListView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var wishes: [ApiWish] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading && wishes.isEmpty {
                ProgressView("Loading...")
            } else if wishes.isEmpty {
                ContentUnavailableView("No wishes yet", systemImage: "heart",
                    description: Text("Wishes added by household members appear here."))
            } else {
                List(wishes) { wish in
                    WishRow(wish: wish, apiClient: authManager.apiClient) {
                        await toggleVote(wish)
                    }
                }
            }
        }
        .navigationTitle("Wish List")
        .task {
            await loadWishes()
        }
        .refreshable {
            await loadWishes()
        }
    }

    private func loadWishes() async {
        loading = wishes.isEmpty
        let response: ApiWishListResponse? = try? await authManager.apiClient.get("wishlist")
        wishes = response?.wishes ?? []
        loading = false
    }

    private func toggleVote(_ wish: ApiWish) async {
        if wish.voted, let wishId = wish.wishId {
            try? await authManager.apiClient.delete("wishlist/\(wishId)/vote")
        } else if let wishId = wish.wishId {
            try? await authManager.apiClient.post("wishlist/\(wishId)/vote", body: [:])
        }
        await loadWishes()
    }
}

struct WishRow: View {
    let wish: ApiWish
    let apiClient: APIClient
    let onToggleVote: () async -> Void

    @State private var voting = false

    var body: some View {
        HStack(spacing: 12) {
            // Poster — TMDB URLs, no auth needed
            if let posterUrl = wish.posterUrl, let url = URL(string: posterUrl) {
                AsyncImage(url: url) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Rectangle().fill(.quaternary)
                        .overlay { Image(systemName: "film").foregroundStyle(.secondary) }
                }
                .frame(width: 50, height: 75)
                .clipShape(RoundedRectangle(cornerRadius: 6))
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(wish.title)
                    .fontWeight(.medium)

                HStack(spacing: 6) {
                    if let type = wish.mediaType {
                        Text(type == "TV" ? "TV Series" : "Movie")
                            .foregroundStyle(.secondary)
                    }
                    if let year = wish.releaseYear {
                        Text("(\(String(year)))")
                            .foregroundStyle(.secondary)
                    }
                    if let season = wish.seasonNumber {
                        Text("S\(season)")
                            .foregroundStyle(.secondary)
                    }
                }
                .font(.caption)

                if !wish.voters.isEmpty {
                    Text(wish.voters.joined(separator: ", "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let status = wish.acquisitionStatus, status != "none" {
                    Text(acquisitionLabel(status))
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundStyle(.green)
                }
            }

            Spacer()

            // Vote button
            Button {
                voting = true
                Task {
                    await onToggleVote()
                    voting = false
                }
            } label: {
                VStack(spacing: 2) {
                    Image(systemName: wish.voted ? "heart.fill" : "heart")
                        .foregroundStyle(wish.voted ? .red : .secondary)
                    Text("\(wish.voteCount)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)
            .disabled(voting)
        }
        .padding(.vertical, 4)
    }

    private func acquisitionLabel(_ status: String) -> String {
        switch status {
        case "ordered": "Ordered"
        case "shipped": "Shipped"
        case "arrived": "Arrived"
        default: status.capitalized
        }
    }
}
