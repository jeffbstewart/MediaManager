import SwiftUI

struct CatalogView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    var typeFilter: MediaType? = nil
    var navigationTitle: String = "Catalog"

    @State private var titles: [ApiTitle] = []
    @State private var total = 0
    @State private var page = 1
    @State private var totalPages = 0
    @State private var loading = true
    @State private var sort = "popularity"
    /// Re-entrancy guard for the infinite-scroll trigger — see
    /// ArtistsView for the rationale.
    @State private var isLoadingMore = false

    private let columns = [
        GridItem(.adaptive(minimum: 110), spacing: 12)
    ]

    var body: some View {
        Group {
            if loading && titles.isEmpty {
                ProgressView("Loading...")
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(titles) { title in
                            NavigationLink(value: title) {
                                PosterCard(title: title)
                            }
                            .buttonStyle(.plain)
                        }

                        if page < totalPages {
                            ProgressView()
                                .onAppear {
                                    guard !isLoadingMore else { return }
                                    Task { await loadMore() }
                                }
                        }
                    }
                    .padding()
                }
            }
        }
        .navigationTitle("\(navigationTitle) (\(total))")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Sort", selection: $sort) {
                        Text("Popularity").tag("popularity")
                        Text("Name").tag("name")
                        Text("Year").tag("year")
                        Text("Recently Added").tag("recent")
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
            }
        }
        .task(id: sort) {
            titles = []
            page = 1
            await loadPage()
        }
        .refreshable {
            titles = []
            page = 1
            await loadPage()
        }
    }

    private func loadPage() async {
        loading = true
        if let typeFilter {
            do {
                let result = try await dataModel.titles(type: typeFilter, page: page, sort: sort)
                titles.append(contentsOf: result.titles)
                total = result.total
                totalPages = result.totalPages
            } catch {
                // silently handle for now
            }
        }
        loading = false
    }

    private func loadMore() async {
        guard !isLoadingMore else { return }
        isLoadingMore = true
        defer { isLoadingMore = false }
        page += 1
        await loadPage()
    }
}
