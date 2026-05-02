import SwiftUI

/// Hero image for a wish: prefer the linked title once fulfilled, otherwise
/// the TMDB poster keyed by (tmdb_id, media_type). Returns nil for wishes
/// missing both, in which case CachedImage shows its placeholder.
fileprivate func wishPosterRef(_ wish: ApiWish) -> MMImageRef? {
    if let titleId = wish.titleId {
        return .posterThumbnail(titleId: titleId.protoValue)
    }
    if let tmdbId = wish.tmdbId, let mediaType = wish.mediaType {
        return .tmdbPoster(tmdbId: tmdbId.protoValue, mediaType: mediaType.protoMediaType)
    }
    return nil
}

/// Hero image for a TMDB search result row.
fileprivate func tmdbResultPosterRef(_ item: TmdbSearchItem) -> MMImageRef? {
    guard let tmdbId = item.tmdbId, let mediaType = item.mediaType else { return nil }
    return .tmdbPoster(tmdbId: tmdbId.protoValue, mediaType: mediaType.protoMediaType)
}

struct WishListView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var wishes: [ApiWish] = []
    @State private var transcodeWishes: [ApiTranscodeWish] = []
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
                    let ready = wishes.filter { $0.isReadyToWatch }
                    let active = wishes.filter { !$0.isReadyToWatch }

                    if !ready.isEmpty {
                        Section {
                            ForEach(ready) { wish in
                                FulfilledWishRow(wish: wish) {
                                    await dismissWish(wish)
                                }
                            }
                        } header: {
                            Label("Ready to Watch", systemImage: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                        }
                    }

                    if !active.isEmpty {
                        Section(ready.isEmpty ? "" : "Wishes") {
                            ForEach(active) { wish in
                                WishRow(wish: wish) {
                                    await toggleVote(wish)
                                }
                            }
                        }
                    }

                    if !transcodeWishes.isEmpty {
                        Section("Transcode Requests") {
                            ForEach(transcodeWishes) { wish in
                                HStack(spacing: 12) {
                                    CachedImage(ref: .posterThumbnail(titleId: wish.titleId.protoValue))
                                        .frame(width: 40, height: 60)

                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(wish.titleName)
                                            .fontWeight(.medium)
                                        Text(wish.mediaType == .tv ? "TV Series" : "Movie")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }

                                    Spacer()

                                    Button {
                                        Task { await removeTranscodeWish(wish.titleId) }
                                    } label: {
                                        Image(systemName: "xmark.circle.fill")
                                            .foregroundStyle(.secondary)
                                    }
                                    .buttonStyle(.plain)
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
        async let mediaResponse = try? dataModel.wishList()
        async let transcodeResponse = try? dataModel.transcodeWishList()
        wishes = await mediaResponse?.wishes ?? []
        transcodeWishes = await transcodeResponse?.transcodeWishes ?? []
        loading = false
    }

    private func removeTranscodeWish(_ titleId: TitleID) async {
        try? await dataModel.deleteTranscodeWish(titleId: titleId)
        await loadWishes()
    }

    private func dismissWish(_ wish: ApiWish) async {
        guard let wishId = wish.wishId else { return }
        try? await dataModel.dismissWish(id: wishId)
        await loadWishes()
    }

    private func toggleVote(_ wish: ApiWish) async {
        guard let wishId = wish.wishId else { return }
        try? await dataModel.voteOnWish(id: wishId, vote: !wish.voted)
        await loadWishes()
    }
}

struct WishRow: View {
    let wish: ApiWish
    let onToggleVote: () async -> Void

    @State private var voting = false

    var body: some View {
        HStack(spacing: 12) {
            CachedImage(ref: wishPosterRef(wish), cornerRadius: 6)
                .frame(width: 50, height: 75)

            VStack(alignment: .leading, spacing: 4) {
                Text(wish.title)
                    .fontWeight(.medium)

                HStack(spacing: 6) {
                    if let type = wish.mediaType {
                        Text(type == .tv ? "TV Series" : "Movie")
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

                if let stage = wish.lifecycleStage {
                    Text(stage.displayLabel)
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundStyle(lifecycleColor(stage))
                }
            }

            Spacer()

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

    private func lifecycleColor(_ stage: WishLifecycleStage) -> Color {
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

struct FulfilledWishRow: View {
    let wish: ApiWish
    let onDismiss: () async -> Void

    var body: some View {
        HStack(spacing: 12) {
            CachedImage(ref: wishPosterRef(wish), cornerRadius: 6)
                .frame(width: 50, height: 75)

            VStack(alignment: .leading, spacing: 4) {
                Text(wish.title)
                    .fontWeight(.medium)

                HStack(spacing: 6) {
                    if let type = wish.mediaType {
                        Text(type == .tv ? "TV Series" : "Movie")
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

                Text(wish.lifecycleStage?.displayLabel ?? "Ready to watch")
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
    @Environment(OnlineDataModel.self) private var dataModel
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
        let response = try? await dataModel.searchTmdb(query: query)
        results = response?.results ?? []
        searching = false
        hasSearched = true
    }

    private func addToWishList(_ item: TmdbSearchItem) async {
        guard let tmdbId = item.tmdbId,
              let mediaType = item.mediaType,
              let title = item.title else { return }

        try? await dataModel.addWish(
            tmdbId: tmdbId,
            mediaType: mediaType,
            title: title,
            year: item.releaseYear,
            seasonNumber: nil
        )
        addedIds.insert(item.id)
    }
}

struct TmdbSearchRow: View {
    let item: TmdbSearchItem
    let added: Bool
    let onAdd: () async -> Void

    var body: some View {
        HStack(spacing: 12) {
            CachedImage(ref: tmdbResultPosterRef(item), cornerRadius: 6)
                .frame(width: 50, height: 75)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.title ?? "Unknown")
                    .fontWeight(.medium)

                HStack(spacing: 6) {
                    Text(item.mediaType == .tv ? "TV Series" : "Movie")
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
