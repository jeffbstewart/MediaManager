import CarPlay
import UIKit

private let logger = MMLogger(category: "CarPlayBrowse")

/// Builds and maintains the CarPlay browse hierarchy. Owned by
/// CarPlaySceneDelegate for the lifetime of the connection.
///
/// Hierarchy:
///   Tabs (3, max 5 allowed by CarPlay HIG):
///     Albums          → recently-added albums → tracklist → tap to play
///     Playlists       → user playlists       → tracklist → tap to play
///     Smart Playlists → server-curated lists → tracklist → tap to play
///
/// Now Playing surfaces automatically when audio is playing — CarPlay
/// renders the system Now Playing template on top of whatever screen
/// is current, driven by MPNowPlayingInfoCenter (which
/// AudioPlayerManager already populates). No work needed here for
/// that.
@MainActor
final class CarPlayBrowseController {

    private weak var interfaceController: CPInterfaceController?

    /// Root tab bar. Templates inside are mutated in place via
    /// `updateSections(_:)` once async data arrives.
    private let albumsTab: CPListTemplate
    private let playlistsTab: CPListTemplate
    private let smartTab: CPListTemplate

    init(interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController

        albumsTab = CPListTemplate(title: "Albums", sections: [Self.loadingSection])
        albumsTab.tabTitle = "Albums"
        albumsTab.tabImage = UIImage(systemName: "square.stack")

        playlistsTab = CPListTemplate(title: "Playlists", sections: [Self.loadingSection])
        playlistsTab.tabTitle = "Playlists"
        playlistsTab.tabImage = UIImage(systemName: "music.note.list")

        smartTab = CPListTemplate(title: "Smart Playlists", sections: [Self.loadingSection])
        smartTab.tabTitle = "Smart"
        smartTab.tabImage = UIImage(systemName: "sparkles")
    }

    /// Mounts the tab bar as the root template and kicks off the
    /// initial data loads. Each tab's data loads independently so
    /// one slow RPC doesn't block the other tabs.
    func install() {
        let tabBar = CPTabBarTemplate(templates: [albumsTab, playlistsTab, smartTab])
        interfaceController?.setRootTemplate(tabBar, animated: true)

        Task { await loadAlbums() }
        Task { await loadPlaylists() }
        Task { await loadSmartPlaylists() }
    }

    // MARK: - Albums

    private func loadAlbums() async {
        guard let model = AppServices.shared.dataModel else { return }
        do {
            // Recently-added albums via homeFeed — same source the
            // Music tab's landing surface uses. The MediaType enum
            // doesn't model audio, so a generic listTitles isn't
            // available; for the driver's use case ("what did I add
            // this week, play it") recently-added is the right
            // affordance anyway.
            let feed = try await model.homeFeed()
            let titles = feed.recentlyAddedAlbums
            let items = titles.map { title -> CPListItem in
                let item = CPListItem(text: title.name, detailText: title.year.map(String.init))
                item.accessoryType = .disclosureIndicator
                item.handler = { [weak self] _, completion in
                    self?.openAlbum(title: title)
                    completion()
                }
                return item
            }
            let section = CPListSection(items: items)
            albumsTab.updateSections([section])
        } catch {
            logger.warning("loadAlbums failed: \(error.localizedDescription)")
            albumsTab.updateSections([Self.errorSection(error: error)])
        }
    }

    private func openAlbum(title: ApiTitle) {
        guard let interfaceController else { return }
        // Push a Loading… template immediately so the head unit
        // doesn't sit on a dead tap. We mutate it in place once
        // titleDetail resolves.
        let template = CPListTemplate(title: title.name, sections: [Self.loadingSection])
        interfaceController.pushTemplate(template, animated: true)

        Task {
            guard let model = AppServices.shared.dataModel else { return }
            do {
                let detail = try await model.titleDetail(id: title.id)
                guard let album = detail.album else {
                    template.updateSections([Self.errorSection(message: "No tracks on this album.")])
                    return
                }
                let items = album.tracks.enumerated().map { (index, track) -> CPListItem in
                    let item = CPListItem(
                        text: track.name,
                        detailText: track.trackArtistNames.first)
                    item.handler = { [weak self] _, completion in
                        self?.playAlbum(detail: detail, album: album, startIndex: index)
                        completion()
                    }
                    return item
                }
                template.updateSections([CPListSection(items: items)])
            } catch {
                logger.warning("openAlbum titleDetail failed: \(error.localizedDescription)")
                template.updateSections([Self.errorSection(error: error)])
            }
        }
    }

    private func playAlbum(detail: ApiTitleDetail, album: ApiAlbum, startIndex: Int) {
        guard let audio = AppServices.shared.audioPlayer else { return }
        let queued = album.tracks.map { Self.makeQueuedTrack($0, albumName: detail.name, album: album) }
        guard !queued.isEmpty else { return }
        let safeStart = max(0, min(startIndex, queued.count - 1))
        audio.play(tracks: queued, startingAt: safeStart)
        presentNowPlaying()
    }

    // MARK: - Playlists

    private func loadPlaylists() async {
        guard let model = AppServices.shared.dataModel else { return }
        do {
            let summaries = try await model.playlists(scope: .mine)
            let items = summaries.map { summary -> CPListItem in
                // ApiPlaylistSummary doesn't expose trackCount (the
                // server only ships it on smart playlists today);
                // surface the description instead — it's typically
                // empty for ad-hoc playlists, in which case the row
                // is just the name.
                let item = CPListItem(text: summary.name, detailText: summary.description)
                item.accessoryType = .disclosureIndicator
                item.handler = { [weak self] _, completion in
                    self?.openPlaylist(id: summary.id, name: summary.name)
                    completion()
                }
                return item
            }
            playlistsTab.updateSections([CPListSection(items: items)])
        } catch {
            logger.warning("loadPlaylists failed: \(error.localizedDescription)")
            playlistsTab.updateSections([Self.errorSection(error: error)])
        }
    }

    private func openPlaylist(id: Int64, name: String) {
        guard let interfaceController else { return }
        let template = CPListTemplate(title: name, sections: [Self.loadingSection])
        interfaceController.pushTemplate(template, animated: true)
        Task {
            guard let model = AppServices.shared.dataModel else { return }
            do {
                let detail = try await model.playlist(id: id)
                let items = detail.tracks.enumerated().map { (index, entry) -> CPListItem in
                    let item = CPListItem(
                        text: entry.track.name,
                        detailText: subtitle(for: entry))
                    item.handler = { [weak self] _, completion in
                        self?.playPlaylist(entries: detail.tracks, startIndex: index)
                        completion()
                    }
                    return item
                }
                template.updateSections([CPListSection(items: items)])
            } catch {
                logger.warning("openPlaylist failed: \(error.localizedDescription)")
                template.updateSections([Self.errorSection(error: error)])
            }
        }
    }

    private func playPlaylist(entries: [ApiPlaylistTrackEntry], startIndex: Int) {
        guard let audio = AppServices.shared.audioPlayer else { return }
        let queued = entries.map { Self.makeQueuedTrack($0) }
        guard !queued.isEmpty else { return }
        let safeStart = max(0, min(startIndex, queued.count - 1))
        audio.play(tracks: queued, startingAt: safeStart)
        presentNowPlaying()
    }

    /// Push the system Now Playing template after kicking playback so
    /// the head unit auto-flips to the playback chrome — without this
    /// the user has to tap the Now Playing button in the upper right
    /// to see what they just started.
    private func presentNowPlaying() {
        guard let interfaceController else { return }
        // Pushing the same singleton twice is a no-op-with-warning,
        // so check the top isn't already Now Playing. Common scenario:
        // user is on the Now Playing screen, swipes back to a list,
        // taps a different track — we'd be re-pushing.
        if interfaceController.topTemplate is CPNowPlayingTemplate { return }
        interfaceController.pushTemplate(CPNowPlayingTemplate.shared, animated: true)
    }

    // MARK: - Smart Playlists

    private func loadSmartPlaylists() async {
        guard let model = AppServices.shared.dataModel else { return }
        do {
            let summaries = try await model.smartPlaylists()
            let items = summaries.map { summary -> CPListItem in
                let item = CPListItem(text: summary.name, detailText: nil)
                item.accessoryType = .disclosureIndicator
                item.handler = { [weak self] _, completion in
                    self?.openSmartPlaylist(key: summary.key, name: summary.name)
                    completion()
                }
                return item
            }
            smartTab.updateSections([CPListSection(items: items)])
        } catch {
            logger.warning("loadSmartPlaylists failed: \(error.localizedDescription)")
            smartTab.updateSections([Self.errorSection(error: error)])
        }
    }

    private func openSmartPlaylist(key: String, name: String) {
        guard let interfaceController else { return }
        let template = CPListTemplate(title: name, sections: [Self.loadingSection])
        interfaceController.pushTemplate(template, animated: true)
        Task {
            guard let model = AppServices.shared.dataModel else { return }
            do {
                let detail = try await model.smartPlaylist(key: key)
                let items = detail.tracks.enumerated().map { (index, entry) -> CPListItem in
                    let item = CPListItem(
                        text: entry.track.name,
                        detailText: subtitle(for: entry))
                    item.handler = { [weak self] _, completion in
                        self?.playPlaylist(entries: detail.tracks, startIndex: index)
                        completion()
                    }
                    return item
                }
                template.updateSections([CPListSection(items: items)])
            } catch {
                logger.warning("openSmartPlaylist failed: \(error.localizedDescription)")
                template.updateSections([Self.errorSection(error: error)])
            }
        }
    }

    // MARK: - Helpers

    /// Subtitle for a playlist track row: artist · album.
    private func subtitle(for entry: ApiPlaylistTrackEntry) -> String? {
        var parts: [String] = []
        if !entry.primaryArtistName.isEmpty { parts.append(entry.primaryArtistName) }
        if !entry.albumName.isEmpty { parts.append(entry.albumName) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// Track conversion for album playback. Mirrors AlbumDetailView's
    /// makeQueuedTrack so the same album played from CarPlay vs the
    /// phone shows the same chrome on the lock screen.
    static func makeQueuedTrack(_ t: ApiTrack, albumName: String, album: ApiAlbum) -> QueuedTrack {
        let credit = t.trackArtistNames.first
            ?? album.albumArtists.first?.name
            ?? ""
        return QueuedTrack(
            id: t.id,
            titleId: t.titleId,
            title: t.name,
            albumName: albumName,
            artistName: credit,
            trackNumber: t.trackNumber,
            discNumber: t.discNumber,
            durationSeconds: t.durationSeconds)
    }

    /// Track conversion for playlist / smart-playlist playback. Each
    /// row's parent album info is on the entry itself, populated by
    /// the server when the track is surfaced standalone.
    static func makeQueuedTrack(_ entry: ApiPlaylistTrackEntry) -> QueuedTrack {
        QueuedTrack(
            id: entry.track.id,
            titleId: entry.albumTitleId,
            title: entry.track.name,
            albumName: entry.albumName,
            artistName: entry.primaryArtistName,
            trackNumber: entry.track.trackNumber,
            discNumber: entry.track.discNumber,
            durationSeconds: entry.track.durationSeconds)
    }

    private static var loadingSection: CPListSection {
        CPListSection(items: [CPListItem(text: "Loading…", detailText: nil)])
    }

    private static func errorSection(error: Error) -> CPListSection {
        errorSection(message: error.localizedDescription)
    }

    private static func errorSection(message: String) -> CPListSection {
        let item = CPListItem(text: "Couldn't load", detailText: message)
        return CPListSection(items: [item])
    }
}
