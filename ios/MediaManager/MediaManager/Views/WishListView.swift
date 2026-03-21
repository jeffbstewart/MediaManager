import SwiftUI

struct WishListView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var wishes: [ApiWish] = []
    @State private var loading = true
    @State private var showingSearch = false

    var body: some View {
        Group {
            if loading && wishes.isEmpty {
                ProgressView("Loading...")
            } else if wishes.isEmpty {
                ContentUnavailableView("No wishes yet", systemImage: "heart",
                    description: Text("Tap + to search for movies and TV shows to add."))
            } else {
                List {
                    let fulfilled = wishes.filter { $0.isFulfilled }
                    let active = wishes.filter { !$0.isFulfilled }

                    if !fulfilled.isEmpty {
                        Section {
                            ForEach(fulfilled) { wish in
                                FulfilledWishRow(wish: wish, apiClient: authManager.apiClient) {
                                    await dismissWish(wish)
                                }
                            }
                        } header: {
                            Label("Ready to Watch", systemImage: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                        }
                    }

                    if !active.isEmpty {
                        Section(fulfilled.isEmpty ? "" : "Wishes") {
                            ForEach(active) { wish in
                                WishRow(wish: wish, apiClient: authManager.apiClient) {
                                    await toggleVote(wish)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Wish List")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showingSearch = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showingSearch) {
            WishSearchView {
                await loadWishes()
            }
        }
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

    private func dismissWish(_ wish: ApiWish) async {
        guard let wishId = wish.wishId else { return }
        try? await authManager.apiClient.post("wishlist/\(wishId)/dismiss", body: [:])
        await loadWishes()
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
                    if wish.voteCount > 1 {
                        Text("\(wish.voteCount)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
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

struct FulfilledWishRow: View {
    let wish: ApiWish
    let apiClient: APIClient
    let onDismiss: () async -> Void

    var body: some View {
        HStack(spacing: 12) {
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

                Text("Now in your collection!")
                    .font(.caption)
                    .fontWeight(.medium)
                    .foregroundStyle(.green)
            }

            Spacer()

            Button {
                Task { await onDismiss() }
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 4)
    }
}

struct WishSearchView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [TmdbSearchItem] = []
    @State private var addedIds: Set<String> = []
    @State private var searching = false
    @State private var hasSearched = false
    @State private var searchTask: Task<Void, Never>?
    let onDismiss: () async -> Void

    var body: some View {
        NavigationStack {
            List {
                if searching {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                } else if results.isEmpty && hasSearched {
                    ContentUnavailableView.search(text: query)
                } else {
                    ForEach(results) { item in
                        TmdbSearchRow(item: item, added: addedIds.contains(item.id)) {
                            await addToWishList(item)
                        }
                    }
                }
            }
            .navigationTitle("Add to Wish List")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "Search movies and TV shows...")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") {
                        Task {
                            await onDismiss()
                            dismiss()
                        }
                    }
                }
            }
            .onChange(of: query) { _, newValue in
                searchTask?.cancel()
                hasSearched = false
                searching = !newValue.trimmingCharacters(in: .whitespaces).isEmpty
                searchTask = Task {
                    try? await Task.sleep(for: .milliseconds(400))
                    guard !Task.isCancelled else { return }
                    await performSearch(newValue)
                }
            }
        }
    }

    private func performSearch(_ query: String) async {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else {
            results = []
            hasSearched = false
            return
        }
        searching = true
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        let response: TmdbSearchResponse? = try? await authManager.apiClient.get("tmdb/search?q=\(encoded)")
        results = response?.results ?? []
        searching = false
        hasSearched = true
    }

    private func addToWishList(_ item: TmdbSearchItem) async {
        guard let tmdbId = item.tmdbId,
              let mediaType = item.mediaType,
              let title = item.title else { return }

        var body: [String: Any] = [
            "tmdb_id": tmdbId,
            "media_type": mediaType,
            "title": title,
            "popularity": item.popularity ?? 0
        ]
        if let posterPath = item.posterPath {
            body["poster_path"] = posterPath
        }
        if let year = item.releaseYear {
            body["release_year"] = year
        }
        try? await authManager.apiClient.post("wishlist", body: body)
        addedIds.insert(item.id)
    }
}

struct TmdbSearchRow: View {
    let item: TmdbSearchItem
    let added: Bool
    let onAdd: () async -> Void

    var body: some View {
        HStack(spacing: 12) {
            if let posterUrl = item.posterUrl, let url = URL(string: posterUrl) {
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
                Text(item.title ?? "Unknown")
                    .fontWeight(.medium)

                HStack(spacing: 6) {
                    Text(item.mediaType == "TV" ? "TV Series" : "Movie")
                        .foregroundStyle(.secondary)
                    if let year = item.releaseYear {
                        Text("(\(String(year)))")
                            .foregroundStyle(.secondary)
                    }
                }
                .font(.caption)

                if let overview = item.overview, !overview.isEmpty {
                    Text(overview)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }

            Spacer()

            if added {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            } else {
                Button {
                    Task { await onAdd() }
                } label: {
                    Image(systemName: "heart.circle")
                        .font(.title2)
                        .foregroundStyle(.red)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 4)
    }
}
