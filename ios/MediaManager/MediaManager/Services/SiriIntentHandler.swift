import Foundation
@preconcurrency import Intents

private let logger = MMLogger(category: "SiriIntent")

/// Swift 6 flags the Intents framework's escaping `completion`
/// closures as non-Sendable, but the framework only invokes them
/// once and the work that runs before invocation is single-threaded.
/// Wrap each closure in an @unchecked-Sendable box to ferry it
/// across the MainActor hop without the compiler complaining.
private struct ResolveCompletionBox: @unchecked Sendable {
    let call: ([INPlayMediaMediaItemResolutionResult]) -> Void
}

private struct HandleCompletionBox: @unchecked Sendable {
    let call: (INPlayMediaIntentResponse) -> Void
}

/// Handles `INPlayMediaIntent` — the voice path for "Hey Siri, play X
/// on Household Disc Keeper" (also reachable from the CarPlay voice
/// button). Resolves the spoken query against the server's
/// searchMusicOnly RPC and starts playback via the same
/// AudioPlayerManager that powers the in-app + CarPlay surfaces.
///
/// Two protocol entry points run:
///
///  1. `resolveMediaItems(for:with:)` — Siri's parsed query lands
///     in `intent.mediaSearch`. We turn its name fields into a
///     search query, take the top hit, and hand back an
///     `INMediaItem` whose identifier encodes both the result
///     type and the server-side ID (`track:123`, `album:456`,
///     `playlist:789`, `artist:42`). Encoding the type lets
///     `handle(intent:)` know whether to play a single track, an
///     ordered album, a playlist, or shuffle an artist's catalogue
///     without re-searching.
///  2. `handle(intent:completion:)` — Siri's "go ahead and play"
///     call. We parse the resolved item's identifier, fetch the
///     full content via the appropriate RPC, build QueuedTracks,
///     and call `audio.play(tracks:startingAt:)`. Reuses the same
///     dispatch paths CarPlayBrowseController calls for album /
///     playlist / artist taps, so behaviour is identical regardless
///     of whether the user tapped a row or spoke the query.
///
/// Background-launch safe: Siri can wake the app to handle the
/// intent before SwiftUI's RootView has appeared and AppServices
/// has been populated. Both methods route through `waitForServices`
/// so the work is deferred until the audio player + data model are
/// wired up — the same `whenReady` pattern the CarPlay scene uses.
///
/// The class is `@unchecked Sendable` because the Intents framework
/// guarantees a single in-flight intent at a time per handler and
/// our own state is read-only after init.
final class SiriIntentHandler: NSObject, INPlayMediaIntentHandling, @unchecked Sendable {

    // MARK: - Resolution

    func resolveMediaItems(
        for intent: INPlayMediaIntent,
        with completion: @escaping ([INPlayMediaMediaItemResolutionResult]) -> Void
    ) {
        // Extract the search strings synchronously off-actor.
        // They're plain Strings (Sendable); INPlayMediaIntent
        // itself isn't, so we don't drag the intent across the
        // MainActor hop.
        let search = intent.mediaSearch
        let mediaName = search?.mediaName
        let artistName = search?.artistName
        let albumName = search?.albumName
        let mediaTypeRaw = search?.mediaType.rawValue ?? -1
        let box = ResolveCompletionBox(call: completion)
        Task { @MainActor in
            let query = composeQuery(media: mediaName, artist: artistName, album: albumName)
            guard !query.isEmpty else {
                logger.warning("resolveMediaItems: empty mediaSearch — cannot resolve")
                box.call([.unsupported(forReason: .unsupportedMediaType)])
                return
            }
            logger.info("resolveMediaItems: '\(query)' (mediaType=\(mediaTypeRaw))")
            await self.waitForServices()
            await self.performResolve(query: query, completionBox: box)
        }
    }

    @MainActor
    private func performResolve(
        query: String,
        completionBox: ResolveCompletionBox
    ) async {
        guard let model = AppServices.shared.dataModel else {
            logger.error("resolveMediaItems: dataModel not ready")
            completionBox.call([.unsupported(forReason: .serviceUnavailable)])
            return
        }
        do {
            let response = try await model.searchMusicOnly(query: query)
            guard let hit = response.results.first else {
                logger.info("resolveMediaItems: no hits for '\(query)'")
                completionBox.call([.unsupported(forReason: .unsupportedMediaType)])
                return
            }
            guard let mediaItem = buildMediaItem(from: hit) else {
                logger.warning("resolveMediaItems: couldn't build INMediaItem for type=\(hit.resultType)")
                completionBox.call([.unsupported(forReason: .unsupportedMediaType)])
                return
            }
            logger.info("resolveMediaItems: resolved '\(query)' → \(hit.resultType) id=\(mediaItem.identifier ?? "?")")
            completionBox.call([.success(with: mediaItem)])
        } catch {
            logger.error("resolveMediaItems: search failed: \(error.localizedDescription)")
            completionBox.call([.unsupported(forReason: .serviceUnavailable)])
        }
    }

    // MARK: - Handle

    func handle(
        intent: INPlayMediaIntent,
        completion: @escaping (INPlayMediaIntentResponse) -> Void
    ) {
        let identifier = intent.mediaItems?.first?.identifier
        let box = HandleCompletionBox(call: completion)
        Task { @MainActor in
            guard let identifier else {
                logger.warning("handle: no resolved mediaItems on intent")
                box.call(INPlayMediaIntentResponse(code: .failureUnknownMediaType, userActivity: nil))
                return
            }
            logger.info("handle: dispatching identifier='\(identifier)'")
            await self.waitForServices()
            await self.performHandle(identifier: identifier, completionBox: box)
        }
    }

    @MainActor
    private func performHandle(
        identifier: String,
        completionBox: HandleCompletionBox
    ) async {
        guard let audio = AppServices.shared.audioPlayer,
              let model = AppServices.shared.dataModel else {
            logger.error("handle: audio player or data model not ready")
            completionBox.call(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let parts = identifier.split(separator: ":", maxSplits: 1)
        guard parts.count == 2 else {
            logger.error("handle: malformed identifier '\(identifier)'")
            completionBox.call(INPlayMediaIntentResponse(code: .failureUnknownMediaType, userActivity: nil))
            return
        }
        let kind = String(parts[0])
        let id = String(parts[1])
        do {
            let queued: [QueuedTrack]
            switch kind {
            case "track":
                queued = try await buildTrackQueue(id: id, model: model)
            case "album":
                queued = try await buildAlbumQueue(id: id, model: model)
            case "playlist":
                queued = try await buildPlaylistQueue(id: id, model: model)
            case "artist":
                queued = try await buildArtistQueue(id: id, model: model)
            default:
                logger.error("handle: unknown identifier kind '\(kind)'")
                completionBox.call(INPlayMediaIntentResponse(code: .failureUnknownMediaType, userActivity: nil))
                return
            }
            guard !queued.isEmpty else {
                logger.warning("handle: '\(identifier)' resolved to 0 playable tracks")
                completionBox.call(INPlayMediaIntentResponse(code: .failureUnknownMediaType, userActivity: nil))
                return
            }
            audio.play(tracks: queued, startingAt: 0)
            logger.info("handle: started playback of \(queued.count) track(s) for '\(identifier)'")
            completionBox.call(INPlayMediaIntentResponse(code: .success, userActivity: nil))
        } catch {
            logger.error("handle: dispatch failed for '\(identifier)': \(error.localizedDescription)")
            completionBox.call(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
        }
    }

    // MARK: - Service-readiness gate

    /// Suspend until `AppServices.shared.populate(...)` has run.
    /// Siri can wake the app in background before SwiftUI's
    /// RootView appears — without this, the first intent after a
    /// cold launch would race with the data-model / audio-player
    /// wiring and bail with "service unavailable".
    @MainActor
    private func waitForServices() async {
        if AppServices.shared.isReady { return }
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            AppServices.shared.whenReady { cont.resume() }
        }
    }

    // MARK: - Query composition

    /// Build a single string from whichever name fields Siri
    /// populated. Apple's parser fills some subset of mediaName /
    /// artistName / albumName depending on how the user phrased
    /// the request — concatenating all populated fields gives the
    /// server's full-text search the best shot at matching what
    /// the user actually said.
    @MainActor
    private func composeQuery(media: String?, artist: String?, album: String?) -> String {
        var parts: [String] = []
        if let s = media, !s.isEmpty { parts.append(s) }
        if let s = artist, !s.isEmpty { parts.append(s) }
        if let s = album, !s.isEmpty { parts.append(s) }
        return parts.joined(separator: " ")
    }

    // MARK: - INMediaItem construction

    /// Build an INMediaItem from a search hit. Identifier carries
    /// our "type:id" convention so handle() can route without
    /// re-searching.
    @MainActor
    private func buildMediaItem(from hit: ApiSearchResult) -> INMediaItem? {
        switch hit.resultType {
        case "track":
            guard let trackId = hit.trackId else { return nil }
            return INMediaItem(
                identifier: "track:\(trackId)",
                title: hit.name,
                type: .song,
                artwork: nil,
                artist: hit.artistName)
        case "album":
            guard let titleId = hit.titleId?.protoValue else { return nil }
            return INMediaItem(
                identifier: "album:\(titleId)",
                title: hit.name,
                type: .album,
                artwork: nil,
                artist: hit.artistName)
        case "playlist":
            guard let playlistId = hit.playlistId else { return nil }
            return INMediaItem(
                identifier: "playlist:\(playlistId)",
                title: hit.name,
                type: .playlist,
                artwork: nil,
                artist: nil)
        case "artist":
            guard let artistId = hit.artistId?.protoValue else { return nil }
            return INMediaItem(
                identifier: "artist:\(artistId)",
                title: hit.name,
                type: .musicStation,
                artwork: nil,
                artist: hit.name)
        default:
            return nil
        }
    }

    // MARK: - Queue builders

    /// Fetch the parent album for a single track and return the
    /// track as a one-item queue. We do the album round-trip so the
    /// QueuedTrack carries album + artist chrome for the Now
    /// Playing surface — the search hit alone doesn't always
    /// include track-level metadata we need.
    @MainActor
    private func buildTrackQueue(id: String, model: OnlineDataModel) async throws -> [QueuedTrack] {
        guard let trackId = Int64(id) else { return [] }
        let response = try await model.searchMusicOnly(query: String(trackId))
        guard let hit = response.results.first(where: { $0.trackId == trackId }),
              let albumTitleId = hit.albumTitleId else {
            return []
        }
        return [QueuedTrack(
            id: trackId,
            titleId: albumTitleId.protoValue,
            title: hit.name,
            albumName: hit.albumName ?? "",
            artistName: hit.artistName ?? "",
            trackNumber: 0,
            discNumber: 0,
            durationSeconds: nil)]
    }

    @MainActor
    private func buildAlbumQueue(id: String, model: OnlineDataModel) async throws -> [QueuedTrack] {
        guard let titleId = Int(id) else { return [] }
        let detail = try await model.titleDetail(id: TitleID(rawValue: titleId))
        guard let album = detail.album else { return [] }
        return album.tracks.map { track in
            let credit = track.trackArtistNames.first
                ?? album.albumArtists.first?.name
                ?? ""
            return QueuedTrack(
                id: track.id,
                titleId: track.titleId,
                title: track.name,
                albumName: detail.name,
                artistName: credit,
                trackNumber: track.trackNumber,
                discNumber: track.discNumber,
                durationSeconds: track.durationSeconds)
        }
    }

    @MainActor
    private func buildPlaylistQueue(id: String, model: OnlineDataModel) async throws -> [QueuedTrack] {
        guard let playlistId = Int64(id) else { return [] }
        let detail = try await model.playlist(id: playlistId)
        return detail.tracks.map { entry in
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
    }

    /// "Play <artist>" → shuffle the artist's owned-album tracks.
    /// Closer to user expectation than playing every track in album
    /// order ("Play Lady Gaga" shouldn't start at the first track
    /// of Joanne and play through her whole catalogue).
    @MainActor
    private func buildArtistQueue(id: String, model: OnlineDataModel) async throws -> [QueuedTrack] {
        guard let rawId = Int(id) else { return [] }
        let detail = try await model.artistDetail(id: ArtistID(rawValue: rawId))
        let artistName = detail.artist.name
        var queued: [QueuedTrack] = []
        for album in detail.ownedAlbums {
            let albumDetail = try await model.titleDetail(id: album.id)
            guard let albumProto = albumDetail.album else { continue }
            for track in albumProto.tracks {
                let credit = track.trackArtistNames.first
                    ?? albumProto.albumArtists.first?.name
                    ?? artistName
                queued.append(QueuedTrack(
                    id: track.id,
                    titleId: track.titleId,
                    title: track.name,
                    albumName: albumDetail.name,
                    artistName: credit,
                    trackNumber: track.trackNumber,
                    discNumber: track.discNumber,
                    durationSeconds: track.durationSeconds))
            }
        }
        queued.shuffle()
        return queued
    }
}
