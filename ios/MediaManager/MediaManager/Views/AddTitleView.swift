import SwiftUI
import MediaManagerCore
import MediaManagerProtos

/// Admin "Add Title" surface. Routes to either TMDB (movies / TV) or
/// Open Library (books) based on the segmented picker; OL hits go
/// onto the user's book-wishlist via AddBookWish, while TMDB hits
/// land in the catalog via the AddTitle RPC. Two-mode setup keeps the
/// surface close to the web app's single Add Title affordance.
struct AddTitleView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var searchQuery = ""
    @State private var mode: AddMode = .movieOrTv
    @State private var mediaType: MMMediaType = .movie
    @State private var tmdbResults: [MMTmdbResult] = []
    @State private var olResults: [MMOpenLibraryHit] = []
    @State private var searching = false
    @State private var selectedTmdbResult: MMTmdbResult?
    @State private var selectedOlResult: MMOpenLibraryHit?
    @State private var mediaFormat: MMMediaFormat = .bluray
    @State private var seasons = ""
    @State private var adding = false
    @State private var statusMessage: String?
    @State private var statusIsError = false

    /// Distinguishes the two remote search backends so the input
    /// field, result rendering, and add-action all stay in lockstep.
    enum AddMode: String, CaseIterable, Identifiable {
        case movieOrTv
        case book
        var id: String { rawValue }
        var label: String {
            switch self {
            case .movieOrTv: return "Movie / TV"
            case .book: return "Book"
            }
        }
        var searchPrompt: String {
            switch self {
            case .movieOrTv: return "Search TMDB"
            case .book: return "Search Open Library"
            }
        }
    }

    var body: some View {
        List {
            Section {
                Picker("Source", selection: $mode) {
                    ForEach(AddMode.allCases) { m in
                        Text(m.label).tag(m)
                    }
                }
                .pickerStyle(.segmented)
                .onChange(of: mode) { _, _ in resetSearchState() }

                TextField(mode.searchPrompt, text: $searchQuery)
                    .autocorrectionDisabled()
                    .onSubmit { search() }

                if mode == .movieOrTv {
                    Picker("Type", selection: $mediaType) {
                        Text("Movie").tag(MMMediaType.movie)
                        Text("TV Show").tag(MMMediaType.tv)
                    }
                    .pickerStyle(.segmented)
                }

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

            switch mode {
            case .movieOrTv:
                tmdbResultsSection()
                tmdbAddSection()
            case .book:
                olResultsSection()
                olAddSection()
            }

            if let statusMessage {
                Section {
                    Text(statusMessage)
                        .font(.callout)
                        .foregroundStyle(statusIsError ? .red : .green)
                }
            }
        }
        .navigationTitle("Add Title")
    }

    // MARK: - TMDB (movies / TV)

    @ViewBuilder
    private func tmdbResultsSection() -> some View {
        if !tmdbResults.isEmpty {
            Section("Results") {
                ForEach(tmdbResults, id: \.tmdbID) { result in
                    Button {
                        selectedTmdbResult = result
                        mediaType = result.mediaType
                    } label: {
                        HStack {
                            TmdbResultRow(result: result)
                            if selectedTmdbResult?.tmdbID == result.tmdbID {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.blue)
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func tmdbAddSection() -> some View {
        if let selected = selectedTmdbResult {
            Section("Add \"\(selected.title)\"") {
                Picker("Format", selection: $mediaFormat) {
                    Text("Blu-ray").tag(MMMediaFormat.bluray)
                    Text("UHD Blu-ray").tag(MMMediaFormat.uhdBluray)
                    Text("DVD").tag(MMMediaFormat.dvd)
                    Text("HD DVD").tag(MMMediaFormat.hdDvd)
                }

                if selected.mediaType == .tv {
                    TextField("Seasons (e.g. 1-3 or 1,2,3)", text: $seasons)
                        .keyboardType(.numbersAndPunctuation)
                }

                Button {
                    addTmdb(selected)
                } label: {
                    HStack {
                        Text("Add to Catalog")
                        Spacer()
                        if adding { ProgressView() }
                    }
                }
                .disabled(adding)
            }
        }
    }

    // MARK: - Open Library (books)

    @ViewBuilder
    private func olResultsSection() -> some View {
        if !olResults.isEmpty {
            Section("Results") {
                ForEach(olResults, id: \.openlibraryWorkID) { hit in
                    Button {
                        selectedOlResult = hit
                    } label: {
                        HStack {
                            OpenLibraryHitRow(hit: hit)
                            if selectedOlResult?.openlibraryWorkID == hit.openlibraryWorkID {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.blue)
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func olAddSection() -> some View {
        if let selected = selectedOlResult {
            Section("Add \"\(selected.title)\"") {
                // Books in the catalog are wishlist-first: an entry on
                // the wishlist is the "I want this in my library"
                // signal, and acquiring a physical / digital edition
                // later (via scan or NAS file ingest) creates the
                // owned MediaItem. Same flow the web app uses for OL
                // search results.
                Text("This book will be added to your wishlist. Scan an ISBN or import an ebook file later to mark it owned.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Button {
                    addBook(selected)
                } label: {
                    HStack {
                        Text("Add to Wishlist")
                        Spacer()
                        if adding { ProgressView() }
                    }
                }
                .disabled(adding)
            }
        }
    }

    // MARK: - Actions

    private func resetSearchState() {
        tmdbResults = []
        olResults = []
        selectedTmdbResult = nil
        selectedOlResult = nil
        statusMessage = nil
        seasons = ""
    }

    private func search() {
        searching = true
        resetSearchState()
        Task {
            do {
                switch mode {
                case .movieOrTv:
                    let response = try await dataModel.searchTmdb(query: searchQuery, type: mediaType)
                    tmdbResults = response.results
                case .book:
                    let response = try await authManager.grpcClient.adminSearchOpenLibrary(
                        query: searchQuery, limit: 20)
                    olResults = response.hits
                }
            } catch {
                statusMessage = "Search failed: \(error.localizedDescription)"
                statusIsError = true
            }
            searching = false
        }
    }

    private func addTmdb(_ result: MMTmdbResult) {
        adding = true
        statusMessage = nil
        Task {
            do {
                let response = try await authManager.grpcClient.adminAddTitle(
                    tmdbId: result.tmdbID,
                    mediaType: result.mediaType,
                    mediaFormat: mediaFormat,
                    seasons: result.mediaType == .tv && !seasons.isEmpty ? seasons : nil
                )
                let suffix = response.alreadyExisted ? " (already in catalog)" : ""
                statusMessage = "Added: \(response.titleName)\(suffix)"
                statusIsError = false
                selectedTmdbResult = nil
                seasons = ""
            } catch {
                statusMessage = "Failed: \(error.localizedDescription)"
                statusIsError = true
            }
            adding = false
        }
    }

    private func addBook(_ hit: MMOpenLibraryHit) {
        adding = true
        statusMessage = nil
        Task {
            do {
                try await dataModel.addBookWish(
                    olWorkId: hit.openlibraryWorkID,
                    title: hit.title,
                    author: hit.hasAuthorName ? hit.authorName : nil)
                statusMessage = "Added to wishlist: \(hit.title)"
                statusIsError = false
                selectedOlResult = nil
            } catch {
                statusMessage = "Failed: \(error.localizedDescription)"
                statusIsError = true
            }
            adding = false
        }
    }
}

/// Result row for an Open Library search hit. Cover via ImageService
/// (work id → covers[0] resolution lives server-side in the same
/// path the bibliography and missing-volumes views use).
private struct OpenLibraryHitRow: View {
    let hit: MMOpenLibraryHit

    var body: some View {
        HStack(spacing: 12) {
            BookCoverView(
                ref: .openlibraryCover(workId: hit.openlibraryWorkID),
                seed: hit.title)
                .frame(width: 50, height: 75)
            VStack(alignment: .leading, spacing: 2) {
                Text(hit.title)
                    .font(.body)
                    .lineLimit(2)
                if hit.hasAuthorName {
                    Text(hit.authorName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                if hit.hasFirstPublishYear {
                    Text(String(hit.firstPublishYear))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
    }
}
