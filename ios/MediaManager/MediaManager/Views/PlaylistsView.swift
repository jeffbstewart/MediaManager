import SwiftUI
import MediaManagerCore
import MediaManagerProtos

private let log = MMLogger(category: "PlaylistsView")

/// "Your Playlists" surface. Renders a vertical list of the
/// caller's owned playlists with a Create button in the toolbar
/// and an inline empty-state when the user has no playlists yet.
/// Tap a row → PlaylistDetailView for full editing / playback.
struct PlaylistsView: View {
    @Environment(OnlineDataModel.self) private var dataModel

    @State private var playlists: [ApiPlaylistSummary] = []
    @State private var loading = true
    @State private var showCreate = false

    var body: some View {
        Group {
            if loading && playlists.isEmpty {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmPage()
            } else if playlists.isEmpty {
                ContentUnavailableView(
                    "No playlists yet",
                    systemImage: "music.note.list",
                    description: Text("Tap the + button to create your first playlist."))
                    .mmPage()
            } else {
                List {
                    Section {
                        ForEach(playlists) { playlist in
                            NavigationLink(value: PlaylistRoute(id: playlist.id, name: playlist.name)) {
                                PlaylistRow(playlist: playlist)
                            }
                        }
                    }
                    .mmRow()
                }
                .mmListChrome()
            }
        }
        .navigationTitle("Your Playlists")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showCreate = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Create playlist")
            }
        }
        .sheet(isPresented: $showCreate) {
            CreatePlaylistSheet { name, description in
                await create(name: name, description: description)
            }
        }
        .task { await load() }
        .refreshable { await load() }
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

    private func create(name: String, description: String?) async {
        do {
            let created = try await dataModel.createPlaylist(name: name, description: description)
            playlists.insert(created, at: 0)
        } catch {
            log.warning("createPlaylist failed: \(error.localizedDescription)")
        }
    }
}

/// One row in the playlists list. Square 50×50 hero cover (album art
/// aspect) + name + secondary line with description / privacy.
private struct PlaylistRow: View {
    let playlist: ApiPlaylistSummary

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                MMPlaceholderArt(name: playlist.name, systemImage: "music.note.list")
                if let titleId = playlist.heroTitleId {
                    CachedImage(
                        ref: .posterThumbnail(titleId: titleId.protoValue),
                        cornerRadius: 0,
                        transparentPlaceholder: true)
                }
            }
            .frame(width: 50, height: 50)  // square
            .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.badge, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: MarqueeTheme.Radius.badge, style: .continuous)
                    .strokeBorder(MarqueeTheme.Colors.hairline, lineWidth: 1))

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(playlist.name)
                        .font(MarqueeTheme.Fonts.control)
                        .foregroundStyle(MarqueeTheme.Colors.text)
                        .lineLimit(1)
                    if playlist.isPrivate {
                        Image(systemName: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(MarqueeTheme.Colors.text3)
                    }
                }
                if let desc = playlist.description, !desc.isEmpty {
                    Text(desc)
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                        .lineLimit(1)
                }
            }
        }
    }
}

/// Sheet presented from PlaylistsView's "+" button. Two text fields
/// + Save / Cancel; the create RPC fires on Save and the parent
/// inserts the resulting summary at the top of its list.
struct CreatePlaylistSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onCreate: (_ name: String, _ description: String?) async -> Void

    @State private var name = ""
    @State private var description = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section(header: MMSectionHeader("Name")) {
                    TextField("My favourite tracks", text: $name)
                        .textInputAutocapitalization(.words)
                }
                Section(header: MMSectionHeader("Description (optional)")) {
                    TextField("What's this playlist for?", text: $description, axis: .vertical)
                        .lineLimit(2...4)
                }
            }
            .mmListChrome()
            .navigationTitle("New Playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: save) {
                        if saving { ProgressView() } else { Text("Create") }
                    }
                    .disabled(saving || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func save() {
        saving = true
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let trimmedDesc = description.trimmingCharacters(in: .whitespaces)
        Task {
            await onCreate(trimmedName, trimmedDesc.isEmpty ? nil : trimmedDesc)
            saving = false
            dismiss()
        }
    }
}

/// Navigation route into PlaylistDetailView. The name is carried so
/// the destination can paint its title bar before the detail fetch
/// resolves.
struct PlaylistRoute: Hashable {
    let id: Int64
    let name: String
}
