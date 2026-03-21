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
                            if carousel.name == "Resume Playing" {
                                CarouselView(
                                    carousel: carousel,
                                    apiClient: authManager.apiClient,
                                    dismissable: true,
                                    onDismiss: { titleId in
                                        await dismissContinueWatching(titleId)
                                    }
                                )
                            } else {
                                CarouselView(carousel: carousel, apiClient: authManager.apiClient)
                            }
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

    private func dismissContinueWatching(_ titleId: Int) async {
        try? await authManager.apiClient.delete("catalog/home/continue-watching/\(titleId)")
        await loadFeed()
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
    var dismissable: Bool = false
    var onDismiss: ((Int) async -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(carousel.name)
                .font(.title2)
                .fontWeight(.bold)
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(carousel.items) { title in
                        ZStack(alignment: .topTrailing) {
                            NavigationLink(value: title) {
                                PosterCard(title: title, apiClient: apiClient)
                            }
                            .buttonStyle(.plain)

                            if dismissable {
                                Button {
                                    Task { await onDismiss?(title.id) }
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.system(size: 18))
                                        .symbolRenderingMode(.palette)
                                        .foregroundStyle(.white, .black.opacity(0.5))
                                }
                                .offset(x: 4, y: -4)
                            }
                        }
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
