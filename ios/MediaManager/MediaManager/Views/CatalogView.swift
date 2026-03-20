import SwiftUI

struct CatalogView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var titles: [ApiTitle] = []
    @State private var total = 0
    @State private var page = 1
    @State private var totalPages = 0
    @State private var loading = true
    @State private var sort = "popularity"

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
                                PosterCard(title: title, apiClient: authManager.apiClient)
                            }
                            .buttonStyle(.plain)
                        }

                        if page < totalPages {
                            ProgressView()
                                .task {
                                    await loadMore()
                                }
                        }
                    }
                    .padding()
                }
            }
        }
        .navigationTitle("Catalog (\(total))")
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
        do {
            let result: ApiTitlePage = try await authManager.apiClient.get("catalog/titles?page=\(page)&limit=25&sort=\(sort)")
            titles.append(contentsOf: result.titles)
            total = result.total
            totalPages = result.totalPages
        } catch {
            // silently handle for now
        }
        loading = false
    }

    private func loadMore() async {
        page += 1
        await loadPage()
    }
}
