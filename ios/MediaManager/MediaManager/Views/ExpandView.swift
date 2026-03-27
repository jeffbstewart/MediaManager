import SwiftUI

struct ExpandView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var items: [MMPendingExpansionItem] = []
    @State private var loading = true
    @State private var selectedItem: MMPendingExpansionItem?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if items.isEmpty {
                ContentUnavailableView("No Multi-Packs",
                    systemImage: "tray",
                    description: Text("All multi-pack items have been expanded."))
            } else {
                List(items, id: \.mediaItemID) { item in
                    Button {
                        selectedItem = item
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.productName)
                                .font(.subheadline)
                                .foregroundStyle(.primary)
                                .lineLimit(2)
                            HStack(spacing: 8) {
                                if item.hasUpc {
                                    Text(item.upc)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Text(formatName(item.mediaFormat))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Text("~\(item.estimatedTitleCount) titles")
                                    .font(.caption)
                                    .foregroundStyle(.orange)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
        }
        .navigationTitle("Expand Multi-Packs")
        .task { await load() }
        .sheet(item: $selectedItem) { item in
            ExpandDetailSheet(mediaItemId: item.mediaItemID, productName: item.productName) {
                Task { await load() }
            }
        }
    }

    private func load() async {
        loading = true
        do {
            let response = try await authManager.grpcClient.adminListPendingExpansions()
            items = response.items
        } catch {}
        loading = false
    }

    private func formatName(_ format: MMMediaFormat) -> String {
        switch format {
        case .bluray: return "Blu-ray"
        case .uhdBluray: return "UHD"
        case .dvd: return "DVD"
        case .hdDvd: return "HD DVD"
        default: return "Unknown"
        }
    }
}

extension MMPendingExpansionItem: Identifiable {
    public var id: Int64 { mediaItemID }
}

struct ExpandDetailSheet: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss

    let mediaItemId: Int64
    let productName: String
    let onDone: () -> Void

    @State private var detail: MMExpansionDetailResponse?
    @State private var searchQuery = ""
    @State private var mediaType: MMMediaType = .movie
    @State private var searchResults: [MMTmdbResult] = []
    @State private var searching = false
    @State private var statusMessage: String?
    @State private var statusIsError = false

    var body: some View {
        NavigationStack {
            List {
                if let detail {
                    if !detail.linkedTitles.isEmpty {
                        Section("Linked Titles (\(detail.linkedTitles.count)/\(detail.estimatedTitleCount))") {
                            ForEach(detail.linkedTitles, id: \.titleID) { linked in
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(linked.name)
                                            .font(.subheadline)
                                        if linked.hasReleaseYear {
                                            Text(String(linked.releaseYear))
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                    Text("Disc \(linked.discNumber)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    Button(role: .destructive) {
                                        Task { await removeTitle(linked.titleID) }
                                    } label: {
                                        Image(systemName: "trash")
                                            .font(.caption)
                                    }
                                }
                            }
                        }
                    }

                    Section("Add Title") {
                        TextField("Search TMDB", text: $searchQuery)
                            .autocorrectionDisabled()
                            .onSubmit { search() }

                        Picker("Type", selection: $mediaType) {
                            Text("Movie").tag(MMMediaType.movie)
                            Text("TV").tag(MMMediaType.tv)
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

                    if !searchResults.isEmpty {
                        Section("Results") {
                            ForEach(searchResults, id: \.tmdbID) { result in
                                Button {
                                    Task { await addTitle(result) }
                                } label: {
                                    TmdbResultRow(result: result)
                                }
                            }
                        }
                    }

                    Section {
                        Button("Mark Expanded") {
                            Task { await markExpanded() }
                        }
                        .disabled(detail.linkedTitles.isEmpty)

                        Button("Not a Multi-Pack") {
                            Task { await markNotMultiPack() }
                        }
                    }
                }

                if let statusMessage {
                    Section {
                        Text(statusMessage)
                            .font(.callout)
                            .foregroundStyle(statusIsError ? .red : .green)
                    }
                }
            }
            .navigationTitle(productName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") {
                        onDone()
                        dismiss()
                    }
                }
            }
            .task { await loadDetail() }
        }
    }

    private func loadDetail() async {
        do {
            detail = try await authManager.grpcClient.adminGetExpansionDetail(mediaItemId: mediaItemId)
        } catch {
            statusMessage = "Failed to load detail"
            statusIsError = true
        }
    }

    private func search() {
        searching = true
        searchResults = []
        Task {
            do {
                let response = try await dataModel.searchTmdb(query: searchQuery, type: mediaType)
                searchResults = response.results
            } catch {
                statusMessage = "Search failed"
                statusIsError = true
            }
            searching = false
        }
    }

    private func addTitle(_ result: MMTmdbResult) async {
        do {
            let response = try await authManager.grpcClient.adminAddTitleToExpansion(
                mediaItemId: mediaItemId, tmdbId: result.tmdbID, mediaType: result.mediaType)
            let suffix = response.alreadyExisted ? " (existing)" : ""
            statusMessage = "Added: \(response.titleName) as disc \(response.discNumber)\(suffix)"
            statusIsError = false
            await loadDetail()
        } catch {
            statusMessage = "Failed to add title"
            statusIsError = true
        }
    }

    private func removeTitle(_ titleId: Int64) async {
        do {
            try await authManager.grpcClient.adminRemoveTitleFromExpansion(
                mediaItemId: mediaItemId, titleId: titleId)
            await loadDetail()
        } catch {
            statusMessage = "Failed to remove"
            statusIsError = true
        }
    }

    private func markExpanded() async {
        do {
            try await authManager.grpcClient.adminMarkExpanded(mediaItemId: mediaItemId)
            onDone()
            dismiss()
        } catch {
            statusMessage = "Failed to mark expanded"
            statusIsError = true
        }
    }

    private func markNotMultiPack() async {
        do {
            try await authManager.grpcClient.adminMarkNotMultiPack(mediaItemId: mediaItemId)
            onDone()
            dismiss()
        } catch {
            statusMessage = "Failed"
            statusIsError = true
        }
    }
}
