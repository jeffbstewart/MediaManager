import SwiftUI

struct AddTitleView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var searchQuery = ""
    @State private var mediaType: MMMediaType = .movie
    @State private var results: [MMTmdbResult] = []
    @State private var searching = false
    @State private var selectedResult: MMTmdbResult?
    @State private var mediaFormat: MMMediaFormat = .bluray
    @State private var seasons = ""
    @State private var adding = false
    @State private var statusMessage: String?
    @State private var statusIsError = false

    var body: some View {
        List {
            Section {
                TextField("Search TMDB", text: $searchQuery)
                    .autocorrectionDisabled()
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

            if !results.isEmpty {
                Section("Results") {
                    ForEach(results, id: \.tmdbID) { result in
                        Button {
                            selectedResult = result
                            mediaType = result.mediaType
                        } label: {
                            HStack {
                                TmdbResultRow(result: result)
                                if selectedResult?.tmdbID == result.tmdbID {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.blue)
                                }
                            }
                        }
                    }
                }
            }

            if let selected = selectedResult {
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
                        addTitle(selected)
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

    private func search() {
        searching = true
        results = []
        selectedResult = nil
        statusMessage = nil
        Task {
            do {
                let response = try await dataModel.searchTmdb(query: searchQuery, type: mediaType)
                results = response.results
            } catch {
                statusMessage = "Search failed: \(error.localizedDescription)"
                statusIsError = true
            }
            searching = false
        }
    }

    private func addTitle(_ result: MMTmdbResult) {
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
                selectedResult = nil
                seasons = ""
            } catch {
                statusMessage = "Failed: \(error.localizedDescription)"
                statusIsError = true
            }
            adding = false
        }
    }
}
