import SwiftUI

struct AdminPurchaseWishesView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var wishes: [AdminPurchaseWish] = []
    @State private var loading = true

    private let statuses = ["NONE", "ORDERED", "SHIPPED", "ARRIVED", "RIPPED", "RETURNED"]

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if wishes.isEmpty {
                ContentUnavailableView("No purchase wishes", systemImage: "cart")
            } else {
                List {
                    ForEach(wishes) { wish in
                        HStack(spacing: 12) {
                            AuthenticatedImage(
                                path: wish.posterUrl,
                                apiClient: authManager.apiClient
                            )
                            .frame(width: 50, height: 75)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(wish.title)
                                    .fontWeight(.medium)
                                    .lineLimit(2)

                                HStack(spacing: 6) {
                                    Text(wish.mediaType)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    if let year = wish.releaseYear {
                                        Text(String(year))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    if let season = wish.seasonNumber {
                                        Text("S\(season)")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }

                                HStack(spacing: 4) {
                                    Image(systemName: "hand.thumbsup.fill")
                                        .font(.caption2)
                                    Text("\(wish.voteCount)")
                                        .font(.caption)
                                        .fontWeight(.medium)
                                    Text(wish.voters.joined(separator: ", "))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }

                            Spacer()

                            Menu {
                                ForEach(statuses, id: \.self) { status in
                                    Button(status.capitalized) {
                                        Task { await updateStatus(wish, status: status) }
                                    }
                                }
                            } label: {
                                Text(wish.acquisitionStatus?.capitalized ?? "None")
                                    .font(.caption)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(statusColor(wish.acquisitionStatus))
                                    .foregroundStyle(.white)
                                    .clipShape(Capsule())
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Purchase Wishes")
        .task { await loadWishes() }
        .refreshable { await loadWishes() }
    }

    private func loadWishes() async {
        loading = wishes.isEmpty
        let response: AdminPurchaseWishListResponse? = try? await authManager.apiClient.get("admin/purchase-wishes")
        wishes = response?.wishes ?? []
        loading = false
    }

    private func updateStatus(_ wish: AdminPurchaseWish, status: String) async {
        // Find the first wish item to get its ID for the PUT
        // The API expects a wish ID, but our model has aggregated data.
        // We need to pass the tmdb_id + media_type to identify the wish group.
        try? await authManager.apiClient.put("admin/purchase-wishes/\(wish.tmdbId)/status", body: ["status": status])
        await loadWishes()
    }

    private func statusColor(_ status: String?) -> Color {
        switch status {
        case "ORDERED": .blue
        case "SHIPPED": .purple
        case "ARRIVED": .green
        case "RIPPED": .mint
        case "RETURNED": .red
        default: .gray
        }
    }
}
