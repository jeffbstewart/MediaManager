import SwiftUI

struct AdminPurchaseWishesView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var wishes: [AdminPurchaseWish] = []
    @State private var loading = true

    private let statuses: [AcquisitionStatus] = [.ordered, .needsAssistance, .notAvailable, .rejected, .owned]

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
                            Group {
                                if let titleId = wish.titleId {
                                    CachedImage(ref: .posterThumbnail(titleId: titleId.protoValue))
                                } else {
                                    CachedImage(ref: .tmdbPoster(
                                        tmdbId: wish.tmdbId.protoValue,
                                        mediaType: wish.mediaType == .tv ? .tv : .movie
                                    ))
                                }
                            }
                            .frame(width: 50, height: 75)
                            .clipShape(RoundedRectangle(cornerRadius: 4))

                            VStack(alignment: .leading, spacing: 4) {
                                Text(wish.title)
                                    .fontWeight(.medium)
                                    .lineLimit(2)

                                HStack(spacing: 6) {
                                    Text(wish.mediaType.rawValue)
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

                                if let lifecycle = wish.lifecycleStage {
                                    Text(lifecycle.displayLabel)
                                        .font(.caption)
                                        .fontWeight(.medium)
                                        .foregroundStyle(statusColor(lifecycle))
                                }
                            }

                            Spacer()

                            Menu {
                                ForEach(statuses, id: \.self) { status in
                                    Button(status.displayLabel) {
                                        Task { await updateStatus(wish, status: status) }
                                    }
                                }
                            } label: {
                                Text(wish.acquisitionStatus?.displayLabel ?? "Wished for")
                                    .font(.caption)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(decisionColor(wish.acquisitionStatus))
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
        let response = try? await dataModel.purchaseWishes()
        wishes = response?.wishes ?? []
        loading = false
    }

    private func updateStatus(_ wish: AdminPurchaseWish, status: AcquisitionStatus) async {
        try? await dataModel.updatePurchaseWishStatus(
            tmdbId: wish.tmdbId,
            mediaType: wish.mediaType,
            seasonNumber: wish.seasonNumber,
            status: status
        )
        await loadWishes()
    }

    private func decisionColor(_ status: AcquisitionStatus?) -> Color {
        switch status {
        case .ordered: .blue
        case .needsAssistance: .orange
        case .owned: .green
        case .notAvailable: .gray
        case .rejected: .red
        default: .gray
        }
    }

    private func statusColor(_ stage: WishLifecycleStage) -> Color {
        switch stage {
        case .readyToWatch: .green
        case .onNasPendingDesktop, .ordered: .blue
        case .inHousePendingNas: .teal
        case .needsAssistance: .orange
        case .notFeasible, .wontOrder: .red
        case .wishedFor: .secondary
        }
    }
}
