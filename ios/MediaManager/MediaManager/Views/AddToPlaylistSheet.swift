import SwiftUI
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "AddToPlaylistSheet")

/// Modal that lets the user pick an existing playlist (or create a
/// fresh one) to receive the supplied track ids. Surface goal: the
/// "Add to Playlist…" entry on every track row's context menu.
///
/// On pick → AddTracksToPlaylist RPC → dismiss. The parent caller
/// doesn't need to do anything beyond presenting the sheet; success /
/// failure are surfaced via brief banner messages inside the sheet
/// before it dismisses.
struct AddToPlaylistSheet: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss
    let trackIds: [Int64]
    /// Optional human-readable name shown in the header so the
    /// user knows what they're adding. e.g. "Add 'Time' to a
    /// playlist".
    let trackHeaderName: String?

    @State private var playlists: [ApiPlaylistSummary] = []
    @State private var loading = true
    @State private var pendingPlaylistId: Int64?
    @State private var status: String?
    @State private var statusIsError = false
    @State private var showCreate = false

    var body: some View {
        NavigationStack {
            Group {
                if loading && playlists.isEmpty {
                    ProgressView("Loading...")
                } else {
                    List {
                        Section {
                            Button {
                                showCreate = true
                            } label: {
                                Label("New Playlist", systemImage: "plus.circle.fill")
                            }
                        }
                        if !playlists.isEmpty {
                            Section("Your Playlists") {
                                ForEach(playlists) { p in
                                    Button {
                                        Task { await add(to: p) }
                                    } label: {
                                        HStack(spacing: 12) {
                                            Group {
                                                if let titleId = p.heroTitleId {
                                                    CachedImage(ref: .posterThumbnail(titleId: titleId.protoValue), cornerRadius: 4)
                                                } else {
                                                    LinearGradient(
                                                        colors: [.indigo, .teal],
                                                        startPoint: .topLeading,
                                                        endPoint: .bottomTrailing)
                                                        .clipShape(RoundedRectangle(cornerRadius: 4))
                                                }
                                            }
                                            .frame(width: 40, height: 40)
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(p.name)
                                                    .foregroundStyle(.primary)
                                                if let desc = p.description, !desc.isEmpty {
                                                    Text(desc)
                                                        .font(.caption)
                                                        .foregroundStyle(.secondary)
                                                        .lineLimit(1)
                                                }
                                            }
                                            Spacer()
                                            if pendingPlaylistId == p.id {
                                                ProgressView()
                                            }
                                        }
                                    }
                                    .disabled(pendingPlaylistId != nil)
                                }
                            }
                        }
                        if let status {
                            Section {
                                Text(status)
                                    .font(.callout)
                                    .foregroundStyle(statusIsError ? .red : .green)
                            }
                        }
                    }
                }
            }
            .navigationTitle(trackHeaderName.map { "Add \"\($0)\"" } ?? "Add to Playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .sheet(isPresented: $showCreate) {
                CreatePlaylistSheet { name, description in
                    await createAndAdd(name: name, description: description)
                }
            }
            .task { await load() }
        }
    }

    private func load() async {
        loading = true
        do {
            playlists = try await dataModel.playlists(scope: .mine)
        } catch {
            log.warning("playlists failed: \(error.localizedDescription)")
        }
        loading = false
    }

    private func add(to playlist: ApiPlaylistSummary) async {
        pendingPlaylistId = playlist.id
        defer { pendingPlaylistId = nil }
        do {
            try await dataModel.addTracksToPlaylist(id: playlist.id, trackIds: trackIds)
            status = "Added to \(playlist.name)"
            statusIsError = false
            try? await Task.sleep(for: .milliseconds(800))
            dismiss()
        } catch {
            status = "Couldn't add: \(error.localizedDescription)"
            statusIsError = true
            log.warning("addTracksToPlaylist failed: \(error.localizedDescription)")
        }
    }

    private func createAndAdd(name: String, description: String?) async {
        do {
            let created = try await dataModel.createPlaylist(name: name, description: description)
            try await dataModel.addTracksToPlaylist(id: created.id, trackIds: trackIds)
            status = "Added to \(created.name)"
            statusIsError = false
            try? await Task.sleep(for: .milliseconds(800))
            dismiss()
        } catch {
            status = "Couldn't add: \(error.localizedDescription)"
            statusIsError = true
            log.warning("createAndAdd failed: \(error.localizedDescription)")
        }
    }
}
