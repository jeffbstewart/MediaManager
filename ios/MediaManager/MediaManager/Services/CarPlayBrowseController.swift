import CarPlay
import GRPCCore
import UIKit
import MediaManagerCore
import MediaManagerProtos

private let logger = MMLogger(category: "CarPlayBrowse")

/// Turns a gRPC failure into a user-friendly string for CarPlay display.
/// Auth failures get a specific message telling the user to sign in on the phone;
/// other errors are kept generic to avoid technical jargon.
private func userFriendlyErrorMessage(_ error: Error) -> String {
    if let rpc = error as? RPCError {
        if rpc.code == .unauthenticated {
            return "Please sign in on your phone to continue"
        }
        // Network / other transient errors — generic message
        return "Couldn't connect. Check your phone's network connection."
    }
    return "Couldn't load. Please try again."
}

/// Turns a gRPC failure into a detailed string for Binnacle logging.
/// The default `localizedDescription` for an RPCError collapses everything to
/// "The operation couldn't be completed. (GRPCCore.RPCError error 1.)",
/// which is useless for triage — it tells you nothing about which RPC failed,
/// what code came back, or what the server said. This pulls out `.code` and
/// `.message` so Binnacle shows "UNAUTHENTICATED: Missing or invalid credentials"
/// (auth path) vs "UNAVAILABLE: dropped connection" (network path).
private func describeErrorForLogging(_ error: Error) -> String {
    if let rpc = error as? RPCError {
        let msg = rpc.message.isEmpty ? "(no message)" : rpc.message
        return "\(rpc.code): \(msg)"
    }
    return error.localizedDescription
}

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
    ///
    /// No search surface — CPSearchTemplate isn't accessible to
    /// `com.apple.developer.carplay-audio` apps. The CarPlay
    /// framework explicitly rejects CPSearchTemplate both inside
    /// CPTabBarTemplate.templates (NSException on validate, seen
    /// in build 4) AND as a pushTemplate target (NSException with
    /// allowed-classes list = {TabBar, List, Alert, VoiceControl,
    /// Grid, ActionSheet, NowPlaying} — seen in build 5). Apple
    /// Music's search tab uses a private CarPlay capability we
    /// don't get. Future option: INPlayMediaIntent + an Intents
    /// extension to route "Hey Siri, play X" voice queries. Until
    /// that lands, browse is the only access path.
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
        interfaceController?.setRootTemplate(tabBar, animated: true, completion: nil)

        Task { await loadAlbums() }
        Task { await loadPlaylists() }
        Task { await loadSmartPlaylists() }
    }

    // MARK: - Albums

    private func loadAlbums() async {
        guard let model = AppServices.shared.dataModel else {
            logger.error("loadAlbums: dataModel is nil — AppServices not populated yet?")
            albumsTab.updateSections([Self.errorSection(message: "App not ready")])
            return
        }
        logger.info("loadAlbums: calling homeFeed")
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
                Self.attachThumbnail(item, titleId: title.id.protoValue)
                item.handler = { [weak self] _, completion in
                    self?.openAlbum(title: title)
                    completion()
                }
                return item
            }
            albumsTab.updateSections([CPListSection(items: items)])
            logger.info("loadAlbums: homeFeed returned \(titles.count) albums")
        } catch {
            logger.error("loadAlbums: homeFeed failed: \(describeErrorForLogging(error))")
            albumsTab.updateSections([Self.errorSection(message: userFriendlyErrorMessage(error))])
        }
    }

    fileprivate func openAlbum(titleId: TitleID, name: String) {
        openAlbum(title: makeAlbumStub(titleId: titleId, name: name))
    }

    /// Build a minimal ApiTitle stub for openAlbum — search results
    /// don't carry full ApiTitle metadata, just titleId + name. The
    /// stub gets replaced by the full proto when titleDetail
    /// resolves.
    private func makeAlbumStub(titleId: TitleID, name: String) -> ApiTitle {
        var proto = MMTitle()
        proto.id = titleId.protoValue
        proto.name = name
        proto.mediaType = .album
        return ApiTitle(proto: proto)
    }

    private func openAlbum(title: ApiTitle) {
        guard let interfaceController else { return }
        // Push a Loading… template immediately so the head unit
        // doesn't sit on a dead tap. We mutate it in place once
        // titleDetail resolves.
        let template = CPListTemplate(title: title.name, sections: [Self.loadingSection])
        interfaceController.pushTemplate(template, animated: true, completion: nil)

        Task {
            guard let model = AppServices.shared.dataModel else {
                logger.error("openAlbum: dataModel is nil for titleId=\(title.id.protoValue)")
                template.updateSections([Self.errorSection(message: "App not ready")])
                return
            }
            logger.info("openAlbum: calling titleDetail for titleId=\(title.id.protoValue)")
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
                logger.error("openAlbum: titleDetail failed for titleId=\(title.id.protoValue): \(describeErrorForLogging(error))")
                template.updateSections([Self.errorSection(message: userFriendlyErrorMessage(error))])
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
        guard let model = AppServices.shared.dataModel else {
            logger.error("loadPlaylists: dataModel is nil")
            playlistsTab.updateSections([Self.errorSection(message: "App not ready")])
            return
        }
        logger.info("loadPlaylists: calling playlists(scope: .mine)")
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
            logger.info("loadPlaylists: playlists returned \(summaries.count) entries")
        } catch {
            logger.error("loadPlaylists: playlists failed: \(describeErrorForLogging(error))")
            playlistsTab.updateSections([Self.errorSection(message: userFriendlyErrorMessage(error))])
        }
    }

    fileprivate func openPlaylist(id: Int64, name: String) {
        guard let interfaceController else { return }
        let template = CPListTemplate(title: name, sections: [Self.loadingSection])
        interfaceController.pushTemplate(template, animated: true, completion: nil)
        Task {
            guard let model = AppServices.shared.dataModel else {
                logger.error("openPlaylist: dataModel is nil for id=\(id)")
                template.updateSections([Self.errorSection(message: "App not ready")])
                return
            }
            logger.info("openPlaylist: calling playlist for id=\(id)")
            do {
                let detail = try await model.playlist(id: id)
                let items = detail.tracks.enumerated().map { (index, entry) -> CPListItem in
                    let item = CPListItem(
                        text: entry.track.name,
                        detailText: subtitle(for: entry))
                    Self.attachThumbnail(item, titleId: entry.albumTitleId)
                    item.handler = { [weak self] _, completion in
                        self?.playPlaylist(entries: detail.tracks, startIndex: index)
                        completion()
                    }
                    return item
                }
                template.updateSections([CPListSection(items: items)])
            } catch {
                logger.error("openPlaylist: playlist(id=\(id)) failed: \(describeErrorForLogging(error))")
                template.updateSections([Self.errorSection(message: userFriendlyErrorMessage(error))])
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
        interfaceController.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
    }

    // MARK: - Smart Playlists

    private func loadSmartPlaylists() async {
        guard let model = AppServices.shared.dataModel else {
            logger.error("loadSmartPlaylists: dataModel is nil")
            smartTab.updateSections([Self.errorSection(message: "App not ready")])
            return
        }
        logger.info("loadSmartPlaylists: calling smartPlaylists")
        do {
            let summaries = try await model.smartPlaylists()
            var items: [CPListItem] = []
            items.append(makeShuffleLibraryItem())
            items.append(contentsOf: summaries.map { summary -> CPListItem in
                let item = CPListItem(text: summary.name, detailText: nil)
                item.accessoryType = .disclosureIndicator
                item.handler = { [weak self] _, completion in
                    self?.openSmartPlaylist(key: summary.key, name: summary.name)
                    completion()
                }
                return item
            })
            smartTab.updateSections([CPListSection(items: items)])
            logger.info("loadSmartPlaylists: returned \(summaries.count) entries (plus Shuffle Library)")
        } catch {
            // Even when the smart-playlist RPC fails, the Shuffle
            // Library row still works (different RPC), so render it
            // alone rather than dropping the driver into a dead-end
            // error screen. The error row sits below as context.
            logger.error("loadSmartPlaylists: smartPlaylists failed: \(describeErrorForLogging(error))")
            smartTab.updateSections([
                CPListSection(items: [makeShuffleLibraryItem()]),
                Self.errorSection(message: userFriendlyErrorMessage(error)),
            ])
        }
    }

    /// Pinned row at the top of the Smart Playlists tab. Tap →
    /// libraryShuffle RPC → AudioPlayerManager.play(...) →
    /// CPNowPlayingTemplate. Driver's one-tap path from "I'm in the
    /// car and want music" to playback without picking an album or
    /// playlist first. Identical effect to the Music tab's Shuffle
    /// Library button on the phone.
    private func makeShuffleLibraryItem() -> CPListItem {
        let item = CPListItem(text: "Shuffle Library", detailText: "Random tracks from your collection")
        item.setImage(UIImage(systemName: "shuffle"))
        item.handler = { [weak self] _, completion in
            self?.shuffleLibrary()
            completion()
        }
        return item
    }

    private func shuffleLibrary() {
        guard let audio = AppServices.shared.audioPlayer,
              let model = AppServices.shared.dataModel else {
            logger.error("shuffleLibrary: services not ready")
            return
        }
        Task {
            logger.info("shuffleLibrary: calling libraryShuffle(limit: 200)")
            do {
                let tracks = try await model.libraryShuffle(limit: 200)
                let queued = tracks.map { Self.makeQueuedTrack(fromShuffleHit: $0) }
                guard !queued.isEmpty else {
                    logger.info("shuffleLibrary: empty result, nothing to play")
                    return
                }
                audio.play(tracks: queued, startingAt: 0)
                presentNowPlaying()
                logger.info("shuffleLibrary: queued \(queued.count) tracks")
            } catch {
                logger.error("shuffleLibrary: libraryShuffle failed: \(describeErrorForLogging(error))")
            }
        }
    }

    private func openSmartPlaylist(key: String, name: String) {
        guard let interfaceController else { return }
        let template = CPListTemplate(title: name, sections: [Self.loadingSection])
        interfaceController.pushTemplate(template, animated: true, completion: nil)
        Task {
            guard let model = AppServices.shared.dataModel else {
                logger.error("openSmartPlaylist: dataModel is nil for key=\(key)")
                template.updateSections([Self.errorSection(message: "App not ready")])
                return
            }
            logger.info("openSmartPlaylist: calling smartPlaylist for key=\(key)")
            do {
                let detail = try await model.smartPlaylist(key: key)
                let items = detail.tracks.enumerated().map { (index, entry) -> CPListItem in
                    let item = CPListItem(
                        text: entry.track.name,
                        detailText: subtitle(for: entry))
                    Self.attachThumbnail(item, titleId: entry.albumTitleId)
                    item.handler = { [weak self] _, completion in
                        self?.playPlaylist(entries: detail.tracks, startIndex: index)
                        completion()
                    }
                    return item
                }
                template.updateSections([CPListSection(items: items)])
            } catch {
                logger.error("openSmartPlaylist: smartPlaylist(key=\(key)) failed: \(describeErrorForLogging(error))")
                template.updateSections([Self.errorSection(message: userFriendlyErrorMessage(error))])
            }
        }
    }

    // MARK: - Artist + single track (search dispatch)

    /// Push a list of the artist's owned albums; tap an album → the
    /// existing openAlbum tracklist path. Used by search ARTIST hits.
    fileprivate func openArtist(artistId: ArtistID, name: String) {
        guard let interfaceController else { return }
        let template = CPListTemplate(title: name, sections: [Self.loadingSection])
        interfaceController.pushTemplate(template, animated: true, completion: nil)
        Task {
            guard let model = AppServices.shared.dataModel else {
                logger.error("openArtist: dataModel is nil for id=\(artistId.protoValue)")
                template.updateSections([Self.errorSection(message: "App not ready")])
                return
            }
            logger.info("openArtist: calling artistDetail for id=\(artistId.protoValue)")
            do {
                let detail = try await model.artistDetail(id: artistId)
                let items = detail.ownedAlbums.map { (album: ApiTitle) -> CPListItem in
                    let item = CPListItem(text: album.name, detailText: album.year.map(String.init))
                    item.accessoryType = .disclosureIndicator
                    Self.attachThumbnail(item, titleId: album.id.protoValue)
                    item.handler = { [weak self] _, completion in
                        self?.openAlbum(titleId: album.id, name: album.name)
                        completion()
                    }
                    return item
                }
                if items.isEmpty {
                    template.updateSections([Self.errorSection(message: "No albums for this artist yet.")])
                } else {
                    template.updateSections([CPListSection(items: items)])
                }
            } catch {
                logger.error("openArtist: artistDetail(id=\(artistId.protoValue)) failed: \(describeErrorForLogging(error))")
                template.updateSections([Self.errorSection(message: userFriendlyErrorMessage(error))])
            }
        }
    }

    /// Play a single track standalone — used when the user picks a
    /// TRACK row from search. Builds a one-item queue with the track's
    /// album context (server populates albumName + artistName on
    /// SearchResult for TRACK hits).
    fileprivate func playSingleTrack(trackId: Int64, titleId: Int64, name: String, albumName: String, artistName: String?) {
        guard let audio = AppServices.shared.audioPlayer else { return }
        let queued = QueuedTrack(
            id: trackId,
            titleId: titleId,
            title: name,
            albumName: albumName,
            artistName: artistName ?? "",
            trackNumber: 0,
            discNumber: 0,
            durationSeconds: nil)
        audio.play(tracks: [queued], startingAt: 0)
        presentNowPlaying()
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

    /// Track conversion for library shuffle. The server populates
    /// albumName + albumArtistName on standalone-surfaced tracks
    /// (libraryShuffle, smart playlist tracks, tag detail, etc.) so
    /// the per-track ApiTrack carries enough chrome metadata on its
    /// own — no parent ApiAlbum required. Mirrors the same shape
    /// MusicView's shuffle button builds.
    static func makeQueuedTrack(fromShuffleHit t: ApiTrack) -> QueuedTrack {
        QueuedTrack(
            id: t.id,
            titleId: t.titleId,
            title: t.name,
            albumName: t.albumName ?? "",
            artistName: t.trackArtistNames.first ?? t.albumArtistName ?? "",
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

    /// Kick an async thumbnail fetch and apply it to the CPListItem
    /// when the bytes arrive. CarPlay's row image slot wants
    /// something small (~60 pt; ~180 px @3x), but we hand iOS the
    /// full poster and let it scale — pre-rendering would just
    /// duplicate the cache we already pay for. ImageProvider
    /// short-circuits to memory/disk cache so already-browsed
    /// albums render instantly; uncached rows pop in once the
    /// gRPC fetch lands. Best-effort: failures are silent because
    /// the row is still usable text-only.
    static func attachThumbnail(_ item: CPListItem, titleId: Int64) {
        Task { @MainActor in
            guard let provider = AppServices.shared.imageProvider else { return }
            let ref = MMImageRef.posterThumbnail(titleId: titleId)
            guard let image = await provider.image(for: ref) else { return }
            item.setImage(image)
        }
    }

    private static var loadingSection: CPListSection {
        CPListSection(items: [CPListItem(text: "Loading…", detailText: nil)])
    }

    /// Error row that surfaces the failing RPC + the gRPC code on the
    /// CarPlay screen. Driver can't read the small detailText while
    /// driving, but the support call ("hey, what does it say?") gets
    /// us straight to the failing RPC and the gRPC status code
    /// instead of "GRPCCore.RPCError error 1".
    private static func errorSection(rpc: String, error: Error) -> CPListSection {
        errorSection(rpc: rpc, message: describeErrorForLogging(error))
    }

    private static func errorSection(rpc: String, message: String) -> CPListSection {
        let item = CPListItem(text: "Couldn't load \(rpc)", detailText: message)
        return CPListSection(items: [item])
    }

    private static func errorSection(message: String) -> CPListSection {
        let item = CPListItem(text: "Couldn't load", detailText: message)
        return CPListSection(items: [item])
    }
}
