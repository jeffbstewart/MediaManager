import SwiftUI

/// Sibling of TmdbSearchSheet for the scan-detail "no match" gate.
/// Used when a UPC scan turned out to be a music CD and the TMDB
/// pipeline understandably came up empty. The user types (or
/// accepts the prefilled) "Artist - Album" query, the server hits
/// MusicBrainz, and a picked candidate's release-group MBID is
/// attached to the title via AssignExternalIdentifier — same path
/// the unmatched-audio admin flow uses.
///
/// `initialBarcode` ships with the request so MB's barcode lookup
/// is tried first; many CDs have their EAN/UPC registered on
/// MusicBrainz even when the free-text search misses.
struct MusicBrainzSearchSheet: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss

    let initialQuery: String
    let initialBarcode: String?
    let titleId: Int64
    let onAssigned: () -> Void

    @State private var searchQuery = ""
    @State private var candidates: [MMMusicBrainzReleaseCandidate] = []
    @State private var searching = false
    @State private var assigning = false
    @State private var statusMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("Album or \"Artist - Album\"", text: $searchQuery)
                        .onSubmit { search() }
                        .autocorrectionDisabled()
                    Button {
                        search()
                    } label: {
                        HStack {
                            Text("Search MusicBrainz")
                            Spacer()
                            if searching { ProgressView() }
                        }
                    }
                    .disabled(searchQuery.trimmingCharacters(in: .whitespaces).isEmpty || searching)
                } footer: {
                    Text("Format \"Artist - Album\" gives the best precision. The original UPC barcode is also tried — many CDs are registered on MusicBrainz by barcode.")
                        .font(.caption2)
                }

                if !candidates.isEmpty {
                    Section("Candidates") {
                        ForEach(candidates, id: \.releaseMbid) { candidate in
                            Button {
                                assignCandidate(candidate)
                            } label: {
                                MusicBrainzCandidateRow(candidate: candidate)
                            }
                            .disabled(assigning)
                        }
                    }
                }

                if let statusMessage {
                    Section {
                        Text(statusMessage)
                            .font(.caption)
                            .foregroundStyle(.green)
                    }
                }
            }
            .navigationTitle("Search MusicBrainz")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear {
                searchQuery = initialQuery
                // Auto-fire the search on appear so the barcode
                // lookup runs without the user having to tap. If
                // results are slow they can edit and re-search.
                if !searchQuery.isEmpty || initialBarcode != nil {
                    search()
                }
            }
        }
    }

    private func search() {
        searching = true
        candidates = []
        statusMessage = nil
        Task {
            do {
                let response = try await dataModel.searchMusicBrainz(
                    query: searchQuery,
                    barcode: initialBarcode)
                candidates = response.candidates
                if candidates.isEmpty {
                    statusMessage = "No matches found."
                }
            } catch {
                statusMessage = "Search failed"
            }
            searching = false
        }
    }

    private func assignCandidate(_ candidate: MMMusicBrainzReleaseCandidate) {
        // We pass the release MBID (specific pressing). Server
        // looks it up to populate title name / poster / tracks /
        // artists in the same call — picking by release-group
        // alone left the title an empty shell.
        assigning = true
        Task {
            do {
                try await dataModel.assignMusicBrainzRelease(
                    titleId: titleId,
                    releaseMbid: candidate.releaseMbid)
                statusMessage = "MusicBrainz match set — title populated"
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

private struct MusicBrainzCandidateRow: View {
    let candidate: MMMusicBrainzReleaseCandidate

    var body: some View {
        HStack(spacing: 12) {
            // Cover-art preview when MB has a release-group cover
            // via Cover Art Archive. CachedImage falls through to
            // the placeholder when the lookup misses.
            if candidate.hasReleaseGroupMbid {
                CachedImage(
                    ref: .coverArtArchiveReleaseGroup(releaseGroupId: candidate.releaseGroupMbid),
                    cornerRadius: 4)
                    .frame(width: 46, height: 46)
            } else {
                RoundedRectangle(cornerRadius: 4)
                    .fill(.fill.quaternary)
                    .frame(width: 46, height: 46)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(candidate.title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(2)
                Text(candidate.artistCredit)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    if candidate.hasYear { Text(String(candidate.year)) }
                    Text("\(candidate.trackCount) track\(candidate.trackCount == 1 ? "" : "s")")
                    if candidate.discCount > 1 {
                        Text("\(candidate.discCount) discs")
                    }
                    if candidate.hasLabel { Text(candidate.label) }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .contentShape(Rectangle())
    }
}
