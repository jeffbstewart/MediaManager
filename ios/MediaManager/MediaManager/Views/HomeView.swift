import SwiftUI

struct HomeView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var feed: ApiHomeFeed?
    @State private var error: String?
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let error {
                ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
            } else if let feed, !feed.carousels.isEmpty {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 24) {
                        ForEach(feed.carousels, id: \.name) { carousel in
                            CarouselView(carousel: carousel, apiClient: authManager.apiClient)
                        }
                    }
                    .padding(.vertical)
                }
            } else {
                ContentUnavailableView("No content yet", systemImage: "film.stack",
                    description: Text("Add titles to your catalog to see them here."))
            }
        }
        .navigationTitle("Home")
        .task {
            await loadFeed()
        }
        .refreshable {
            await loadFeed()
        }
    }

    private func loadFeed() async {
        loading = feed == nil
        do {
            feed = try await authManager.apiClient.get("catalog/home")
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }
}

struct CarouselView: View {
    let carousel: ApiCarousel
    let apiClient: APIClient

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(carousel.name)
                .font(.title2)
                .fontWeight(.bold)
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(carousel.items) { title in
                        NavigationLink(value: title) {
                            PosterCard(title: title, apiClient: apiClient)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }
}

struct PosterCard: View {
    let title: ApiTitle
    let apiClient: APIClient

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            AuthenticatedImage(path: title.posterUrl, apiClient: apiClient)
                .frame(width: 120, height: 180)
                .clipped()

            Text(title.name)
                .font(.caption)
                .lineLimit(2)

            if let year = title.year {
                Text(String(year))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if let members = title.familyMembers, !members.isEmpty {
                Text(members.joined(separator: ", "))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(width: 120, alignment: .topLeading)
        .contentShape(Rectangle())
    }
}
