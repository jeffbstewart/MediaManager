import SwiftUI

struct AdminDataQualityView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var titles: [AdminDataQualityTitle] = []
    @State private var loading = true
    @State private var page = 1
    @State private var totalPages = 0

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if titles.isEmpty {
                ContentUnavailableView("All titles enriched", systemImage: "checkmark.circle",
                    description: Text("No data quality issues found."))
            } else {
                List {
                    ForEach(titles) { title in
                        HStack(spacing: 12) {
                            AuthenticatedImage(
                                path: title.posterUrl,
                                apiClient: dataModel.apiClient
                            )
                            .frame(width: 50, height: 75)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(title.name)
                                    .fontWeight(.medium)
                                    .lineLimit(2)

                                HStack(spacing: 6) {
                                    if let type = title.mediaType {
                                        Text(type.rawValue)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    if let year = title.releaseYear {
                                        Text(String(year))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }

                                HStack(spacing: 6) {
                                    statusBadge(title.enrichmentStatus)
                                    if title.hidden {
                                        Text("Hidden")
                                            .font(.caption2)
                                            .fontWeight(.bold)
                                            .foregroundStyle(.white)
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 2)
                                            .background(.gray)
                                            .clipShape(Capsule())
                                    }
                                }
                            }

                            Spacer()
                        }
                        .swipeActions(edge: .trailing) {
                            Button("Re-enrich") {
                                Task { await reEnrich(title) }
                            }
                            .tint(.blue)

                            Button("Delete", role: .destructive) {
                                Task { await deleteTitle(title) }
                            }
                        }
                        .contextMenu {
                            Button("Re-enrich from TMDB") {
                                Task { await reEnrich(title) }
                            }
                            Button("Delete Title", role: .destructive) {
                                Task { await deleteTitle(title) }
                            }
                        }
                    }

                    if page < totalPages {
                        Button("Load More") {
                            Task { await loadMore() }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .navigationTitle("Data Quality")
        .task { await loadTitles() }
        .refreshable {
            page = 1
            await loadTitles()
        }
    }

    @ViewBuilder
    private func statusBadge(_ status: String?) -> some View {
        let (label, color): (String, Color) = switch status {
        case "ENRICHED": ("Enriched", .green)
        case "PENDING": ("Pending", .orange)
        case "FAILED": ("Failed", .red)
        case "NOT_FOUND": ("Not Found", .purple)
        default: (status ?? "Unknown", .gray)
        }

        Text(label)
            .font(.caption2)
            .fontWeight(.bold)
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color)
            .clipShape(Capsule())
    }

    private func loadTitles() async {
        loading = titles.isEmpty
        let response = try? await dataModel.dataQuality(page: page)
        titles = response?.titles ?? []
        totalPages = response?.totalPages ?? 0
        loading = false
    }

    private func loadMore() async {
        page += 1
        let response = try? await dataModel.dataQuality(page: page)
        titles += response?.titles ?? []
        totalPages = response?.totalPages ?? 0
    }

    private func reEnrich(_ title: AdminDataQualityTitle) async {
        try? await dataModel.reEnrich(titleId: title.id)
        await loadTitles()
    }

    private func deleteTitle(_ title: AdminDataQualityTitle) async {
        try? await dataModel.deleteTitle(id: title.id)
        titles.removeAll { $0.id == title.id }
    }
}
