import Foundation
import Intents
import os.log
import MediaManagerCore
import MediaManagerProtos

private let log = OSLog(subsystem: "net.stewart.mediamanager",
                       category: "SiriIntentHandler")

/// SiriKit hands us @escaping non-Sendable closures. Swift 6 strict
/// concurrency rightly flags capturing them inside a `Task { @MainActor ... }`
/// as risking a data race. In practice these completions are called once
/// from whatever thread Siri picks — there's no shared state. Box them.
private struct SiriCompletion<Arg>: @unchecked Sendable {
    let call: (Arg) -> Void
}

/// In-app handler for `INPlayMediaIntent`. Vended from
/// `AppDelegate.application(_:handlerFor:)` for each incoming Siri
/// request.
///
/// Two-phase contract:
///   1. `resolveMediaItems` — Siri asks "what should I play?". We search
///      the catalog via `OnlineDataModel.searchMusicOnly` and return an
///      `INMediaItem` whose `identifier` carries the type and IDs the
///      `handle` phase needs to actually queue the playback. Identifiers
///      we issue:
///        - `track:<trackId>:<albumTitleId>`  — single song hit
///        - `album:<albumTitleId>`            — whole album
///   2. `handle` — Siri says "ok, do it". We parse the identifier,
///      fetch full album detail via `titleDetail`, build a `[QueuedTrack]`
///      and hand it to `AudioPlayerManager.play(...)`.
///
/// Both phases dispatch onto `@MainActor` because `OnlineDataModel`
/// and `AudioPlayerManager` are main-actor-isolated. The completion
/// closures Siri hands us can be invoked from any thread.
@objc(SiriIntentHandler)
final class SiriIntentHandler: NSObject, INPlayMediaIntentHandling {

    override init() {
        super.init()
        os_log("SiriIntentHandler init", log: log, type: .default)
    }

    // MARK: - resolveMediaItems

    func resolveMediaItems(
        for intent: INPlayMediaIntent,
        with completion: @escaping ([INPlayMediaMediaItemResolutionResult]) -> Void
    ) {
        let phrase = intent.mediaSearch?.mediaName ?? ""
        os_log("resolveMediaItems: phrase=%{public}@",
               log: log, type: .default, phrase)
        guard !phrase.isEmpty else {
            completion([])
            return
        }
        let box = SiriCompletion(call: completion)
        Task { @MainActor in
            await Self.resolve(phrase: phrase, completion: box.call)
        }
    }

    @MainActor
    private static func resolve(
        phrase: String,
        completion: @escaping ([INPlayMediaMediaItemResolutionResult]) -> Void
    ) async {
        guard let dataModel = AppServices.shared.dataModel else {
            os_log("resolve: dataModel not ready", log: log, type: .error)
            completion([])
            return
        }
        do {
            let response = try await dataModel.searchMusicOnly(query: phrase)
            // Prefer a track hit, then fall back to an album hit. The
            // server's ranking already favors closer matches, so taking
            // the first hit of the preferred type is fine.
            if let track = response.results.first(where: {
                $0.resultType == "track" && $0.trackId != nil && $0.albumTitleId != nil
            }), let tid = track.trackId, let albumTid = track.albumTitleId {
                let item = INMediaItem(
                    identifier: "track:\(tid):\(albumTid.rawValue)",
                    title: track.name,
                    type: .song,
                    artwork: nil,
                    artist: track.artistName
                )
                os_log("resolve: track id=%lld album=%d",
                       log: log, type: .default, tid, albumTid.rawValue)
                completion([.success(with: item)])
                return
            }
            if let album = response.results.first(where: {
                $0.resultType == "album" && $0.titleId != nil
            }), let albumTid = album.titleId {
                let item = INMediaItem(
                    identifier: "album:\(albumTid.rawValue)",
                    title: album.name,
                    type: .album,
                    artwork: nil,
                    artist: album.artistName
                )
                os_log("resolve: album id=%d",
                       log: log, type: .default, albumTid.rawValue)
                completion([.success(with: item)])
                return
            }
            os_log("resolve: no playable result for %{public}@",
                   log: log, type: .default, phrase)
            completion([])
        } catch {
            os_log("resolve: search failed: %{public}@",
                   log: log, type: .error, String(describing: error))
            completion([])
        }
    }

    // MARK: - handle

    func handle(
        intent: INPlayMediaIntent,
        completion: @escaping (INPlayMediaIntentResponse) -> Void
    ) {
        guard let identifier = intent.mediaItems?.first?.identifier else {
            os_log("handle: no identifier on resolved item",
                   log: log, type: .error)
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        os_log("handle: identifier=%{public}@",
               log: log, type: .default, identifier)
        let box = SiriCompletion(call: completion)
        Task { @MainActor in
            await Self.dispatch(identifier: identifier, completion: box.call)
        }
    }

    @MainActor
    private static func dispatch(
        identifier: String,
        completion: @escaping (INPlayMediaIntentResponse) -> Void
    ) async {
        guard let dataModel = AppServices.shared.dataModel,
              let audioPlayer = AppServices.shared.audioPlayer else {
            os_log("dispatch: AppServices not ready", log: log, type: .error)
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let parts = identifier.split(separator: ":")
        guard parts.count >= 2 else {
            os_log("dispatch: malformed identifier %{public}@",
                   log: log, type: .error, identifier)
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let kind = String(parts[0])

        do {
            let queue: [QueuedTrack]
            let startIndex: Int

            switch kind {
            case "track":
                // "track:<trackId>:<albumTitleId>"
                guard parts.count == 3,
                      let trackId = Int64(parts[1]),
                      let albumIdInt = Int(parts[2]) else {
                    completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
                    return
                }
                let detail = try await dataModel.titleDetail(id: TitleID(rawValue: albumIdInt))
                guard let album = detail.album else {
                    os_log("dispatch: titleDetail returned no album for id=%d",
                           log: log, type: .error, albumIdInt)
                    completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
                    return
                }
                queue = album.tracks.map { Self.makeQueuedTrack($0, albumName: detail.name, album: album) }
                startIndex = queue.firstIndex(where: { $0.id == trackId }) ?? 0

            case "album":
                // "album:<albumTitleId>"
                guard let albumIdInt = Int(parts[1]) else {
                    completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
                    return
                }
                let detail = try await dataModel.titleDetail(id: TitleID(rawValue: albumIdInt))
                guard let album = detail.album else {
                    os_log("dispatch: titleDetail returned no album for id=%d",
                           log: log, type: .error, albumIdInt)
                    completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
                    return
                }
                queue = album.tracks.map { Self.makeQueuedTrack($0, albumName: detail.name, album: album) }
                startIndex = 0

            default:
                os_log("dispatch: unknown identifier kind %{public}@",
                       log: log, type: .error, kind)
                completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
                return
            }

            guard !queue.isEmpty else {
                os_log("dispatch: empty queue for %{public}@",
                       log: log, type: .default, identifier)
                completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
                return
            }
            os_log("dispatch: playing %d tracks startingAt=%d",
                   log: log, type: .default, queue.count, startIndex)
            audioPlayer.play(tracks: queue, startingAt: startIndex)
            completion(INPlayMediaIntentResponse(code: .success, userActivity: nil))
        } catch {
            os_log("dispatch: error %{public}@",
                   log: log, type: .error, String(describing: error))
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
        }
    }

    @MainActor
    private static func makeQueuedTrack(
        _ t: ApiTrack,
        albumName: String,
        album: ApiAlbum
    ) -> QueuedTrack {
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
}
