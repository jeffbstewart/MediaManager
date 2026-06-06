import SwiftUI

struct CatalogView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    var typeFilter: MediaType? = nil
    var navigationTitle: String = "Catalog"

    @State private var titles: [ApiTitle] = []
    /// Total title count from the server. `nil` until the first
    /// page lands — so the navigation bar shows just "Movies" while
    /// loading instead of "Movies (0)" before the count is known.
    @State private var total: Int?
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
        .navigationTitle(total.map { "\(navigationTitle) (\($0))" } ?? navigationTitle)
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
                // Rapid tab nav cancels + re-fires .task; suppress
                // the cancellation-induced failure so we don't
                // briefly drop the loading spinner for a moot
                // error.
                if Task.isCancelled || error is CancellationError { return }
                // Real errors still silently retried via the
                // user-visible refresh path for now.
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
