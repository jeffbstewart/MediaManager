import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct TmdbSearchSheet: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss

    let initialQuery: String
    let titleId: Int64
    let onAssigned: () -> Void

    @State private var searchQuery = ""
    @State private var mediaType: MMMediaType = .movie
    @State private var results: [MMTmdbResult] = []
    @State private var searching = false
    @State private var assigning = false
    @State private var statusMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("Title", text: $searchQuery)
                        .onSubmit { search() }

                    Picker("Type", selection: $mediaType) {
                        Text("Movie").tag(MMMediaType.movie)
                        Text("TV Show").tag(MMMediaType.tv)
                    }
                    .pickerStyle(.segmented)

                    Button {
                        search()
                    } label: {
                        HStack {
                            Text("Search")
                            Spacer()
                            if searching { ProgressView() }
                        }
                    }
                    .disabled(searchQuery.trimmingCharacters(in: .whitespaces).isEmpty || searching)
                }
                .mmRow()

                if !results.isEmpty {
                    Section(header: MMSectionHeader("Results")) {
                        ForEach(results, id: \.tmdbID) { result in
                            Button {
                                assignResult(result)
                            } label: {
                                TmdbResultRow(result: result)
                            }
                            .disabled(assigning)
                        }
                    }
                    .mmRow()
                }

                if let statusMessage {
                    Section {
                        Text(statusMessage)
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.green)
                    }
                    .mmRow()
                }
            }
            .mmListChrome()
            .navigationTitle("Search TMDB")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear {
                searchQuery = initialQuery
            }
        }
    }

    private func search() {
        searching = true
        results = []
        Task {
            do {
                let response = try await dataModel.searchTmdb(query: searchQuery, type: mediaType)
                results = response.results
            } catch {
                statusMessage = "Search failed"
            }
            searching = false
        }
    }

    private func assignResult(_ result: MMTmdbResult) {
        assigning = true
        Task {
            do {
                let response = try await dataModel.assignTmdb(
                    titleId: titleId,
                    tmdbId: result.tmdbID,
                    mediaType: result.mediaType
                )
                if response.merged {
                    statusMessage = "Merged into: \(response.mergedTitleName)"
                } else {
                    statusMessage = "TMDB match set — re-enrichment queued"
                }
                assigning = false
                onAssigned()
                try? await Task.sleep(for: .seconds(1))
                dismiss()
            } catch {
                statusMessage = "Assignment failed"
                assigning = false
            }
        }
    }
}

// MARK: - TMDB Result Row

struct TmdbResultRow: View {
    let result: MMTmdbResult

    var body: some View {
        HStack(spacing: 12) {
            MMArtFrame {
                CachedImage(
                    ref: .tmdbPoster(tmdbId: result.tmdbID, mediaType: result.mediaType),
                    cornerRadius: 0)
            }
            .frame(width: 46, height: 69)

            VStack(alignment: .leading, spacing: 4) {
                Text(result.title)
                    .font(MarqueeTheme.Fonts.cardTitle)
                    .fontWeight(.medium)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .lineLimit(2)

                HStack(spacing: 6) {
                    if result.hasReleaseYear {
                        Text(String(result.releaseYear))
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.text3)
                    }
                    Text(result.mediaType == .tv ? "TV" : "Movie")
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                    if result.owned {
                        Text("Owned")
                            .font(MarqueeTheme.Fonts.meta)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(MarqueeTheme.Colors.green.opacity(0.2))
                            .foregroundStyle(MarqueeTheme.Colors.green)
                            .clipShape(Capsule())
                    }
                }
            }

            Spacer()
        }
        .contentShape(Rectangle())
    }
}
