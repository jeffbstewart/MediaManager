import Foundation
import Intents
import AVFoundation
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
///      the catalog via `OnlineDataModel.search` (broader than music-only
///      so we get movie + series hits too) and return an `INMediaItem`
///      whose `identifier` carries the type and IDs the `handle` phase
///      needs to actually start playback. Identifiers we issue:
///        - `track:<trackId>:<albumTitleId>`  — single song
///        - `album:<albumTitleId>`            — whole album
///        - `movie:<titleId>`                 — movie
///        - `series:<titleId>`                — TV series (resumes)
///   2. `handle` — Siri says "ok, do it". We parse the identifier, fetch
///      whatever detail RPC the kind needs (titleDetail for movies;
///      homeFeed + seasons/episodes for series resume; titleDetail for
///      music album tracklist), then either:
///        - audio: hand a [QueuedTrack] to `AudioPlayerManager.play`, or
///        - video: hand a `PlaybackRoute` to `VideoPlaybackCoordinator`
///          so `ContentView` opens the full-screen player.
///
/// Video playback is refused (with a log line) when we're connected to
/// CarPlay or the audio route is `.carAudio` — the head unit can't
/// render video and Siri's verbalisation of `.failure` is friendlier
/// than letting the user think the request worked.
///
/// Both phases dispatch onto `@MainActor` because `OnlineDataModel`
/// and `AudioPlayerManager` are main-actor-isolated. The completion
/// closures Siri hands us can be invoked from any thread.
@objc(SiriIntentHandler)
final class SiriIntentHandler: NSObject, INPlayMediaIntentHandling, INSearchForMediaIntentHandling {

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
            // Broader search so we pick up movies/TV. Server ranks
            // results by relevance, so the first hit of a supported
            // type wins.
            let response = try await dataModel.search(query: phrase)
            for result in response.results {
                if let item = makeMediaItem(from: result) {
                    completion([.success(with: item)])
                    return
                }
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

    @MainActor
    private static func makeMediaItem(from result: ApiSearchResult) -> INMediaItem? {
        switch result.resultType {
        case "track":
            guard let tid = result.trackId, let albumTid = result.albumTitleId else { return nil }
            return INMediaItem(
                identifier: "track:\(tid):\(albumTid.rawValue)",
                title: result.name,
                type: .song,
                artwork: nil,
                artist: result.artistName
            )
        case "album":
            guard let albumTid = result.titleId else { return nil }
            return INMediaItem(
                identifier: "album:\(albumTid.rawValue)",
                title: result.name,
                type: .album,
                artwork: nil,
                artist: result.artistName
            )
        case "movie":
            guard let tid = result.titleId else { return nil }
            return INMediaItem(
                identifier: "movie:\(tid.rawValue)",
                title: result.name,
                type: .movie,
                artwork: nil,
                artist: nil
            )
        case "series":
            guard let tid = result.titleId else { return nil }
            return INMediaItem(
                identifier: "series:\(tid.rawValue)",
                title: result.name,
                type: .tvShow,
                artwork: nil,
                artist: nil
            )
        default:
            return nil
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
        guard let dataModel = AppServices.shared.dataModel else {
            os_log("dispatch: AppServices.dataModel not ready",
                   log: log, type: .error)
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
            switch kind {
            case "track":
                try await dispatchTrack(parts: parts, dataModel: dataModel, completion: completion)
            case "album":
                try await dispatchAlbum(parts: parts, dataModel: dataModel, completion: completion)
            case "movie":
                try await dispatchMovie(parts: parts, dataModel: dataModel, completion: completion)
            case "series":
                try await dispatchSeries(parts: parts, dataModel: dataModel, completion: completion)
            default:
                os_log("dispatch: unknown identifier kind %{public}@",
                       log: log, type: .error, kind)
                completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            }
        } catch {
            os_log("dispatch: error %{public}@",
                   log: log, type: .error, String(describing: error))
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
        }
    }

    // MARK: - Audio dispatch

    @MainActor
    private static func dispatchTrack(
        parts: [Substring],
        dataModel: OnlineDataModel,
        completion: @escaping (INPlayMediaIntentResponse) -> Void
    ) async throws {
        // "track:<trackId>:<albumTitleId>"
        guard parts.count == 3,
              let trackId = Int64(parts[1]),
              let albumIdInt = Int(parts[2]),
              let audioPlayer = AppServices.shared.audioPlayer else {
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let detail = try await dataModel.titleDetail(id: TitleID(rawValue: albumIdInt))
        guard let album = detail.album else {
            os_log("dispatchTrack: titleDetail returned no album for id=%d",
                   log: log, type: .error, albumIdInt)
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let queue = album.tracks.map { makeQueuedTrack($0, albumName: detail.name, album: album) }
        guard !queue.isEmpty else {
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let startIndex = queue.firstIndex(where: { $0.id == trackId }) ?? 0
        os_log("dispatchTrack: playing %d tracks startingAt=%d",
               log: log, type: .default, queue.count, startIndex)
        audioPlayer.play(tracks: queue, startingAt: startIndex)
        completion(INPlayMediaIntentResponse(code: .success, userActivity: nil))
    }

    @MainActor
    private static func dispatchAlbum(
        parts: [Substring],
        dataModel: OnlineDataModel,
        completion: @escaping (INPlayMediaIntentResponse) -> Void
    ) async throws {
        // "album:<albumTitleId>"
        guard let albumIdInt = Int(parts[1]),
              let audioPlayer = AppServices.shared.audioPlayer else {
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let detail = try await dataModel.titleDetail(id: TitleID(rawValue: albumIdInt))
        guard let album = detail.album else {
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let queue = album.tracks.map { makeQueuedTrack($0, albumName: detail.name, album: album) }
        guard !queue.isEmpty else {
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        os_log("dispatchAlbum: playing %d tracks",
               log: log, type: .default, queue.count)
        audioPlayer.play(tracks: queue, startingAt: 0)
        completion(INPlayMediaIntentResponse(code: .success, userActivity: nil))
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

    // MARK: - Video dispatch

    @MainActor
    private static func dispatchMovie(
        parts: [Substring],
        dataModel: OnlineDataModel,
        completion: @escaping (INPlayMediaIntentResponse) -> Void
    ) async throws {
        // "movie:<titleId>"
        guard let titleIdInt = Int(parts[1]) else {
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        if isInCarContext {
            os_log("dispatchMovie: refusing video in car context",
                   log: log, type: .default)
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let detail = try await dataModel.titleDetail(id: TitleID(rawValue: titleIdInt))
        guard detail.playable, let transcodeId = detail.transcodeId else {
            os_log("dispatchMovie: title not playable id=%d",
                   log: log, type: .default, titleIdInt)
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let hasSubs = detail.transcodes.first(where: { $0.id == transcodeId })?.hasSubtitles ?? false
        let route = PlaybackRoute(
            transcodeId: transcodeId,
            titleName: detail.name,
            episodeName: nil,
            hasSubtitles: hasSubs
        )
        os_log("dispatchMovie: requesting playback id=%d transcode=%d",
               log: log, type: .default, titleIdInt, transcodeId.rawValue)
        VideoPlaybackCoordinator.shared.request(route)
        completion(videoResponse())
    }

    @MainActor
    private static func dispatchSeries(
        parts: [Substring],
        dataModel: OnlineDataModel,
        completion: @escaping (INPlayMediaIntentResponse) -> Void
    ) async throws {
        // "series:<titleId>"
        guard let titleIdInt = Int(parts[1]) else {
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        if isInCarContext {
            os_log("dispatchSeries: refusing video in car context",
                   log: log, type: .default)
            completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
            return
        }
        let seriesId = TitleID(rawValue: titleIdInt)
        // Pass 1: server-side "Resume Playing" carousel knows exactly
        // which episode to come back to.
        if let resumeRoute = try await resumeRouteFromHomeFeed(seriesId: seriesId, dataModel: dataModel) {
            os_log("dispatchSeries: resume from home feed id=%d S%dE%d",
                   log: log, type: .default,
                   titleIdInt,
                   resumeRoute.seasonNumber ?? -1,
                   resumeRoute.episodeNumber ?? -1)
            VideoPlaybackCoordinator.shared.request(resumeRoute)
            completion(videoResponse())
            return
        }
        // Pass 2: no resume data — start from S1E1.
        if let firstEpisodeRoute = try await firstEpisodeRoute(seriesId: seriesId, dataModel: dataModel) {
            os_log("dispatchSeries: starting from first episode id=%d",
                   log: log, type: .default, titleIdInt)
            VideoPlaybackCoordinator.shared.request(firstEpisodeRoute)
            completion(videoResponse())
            return
        }
        os_log("dispatchSeries: no playable episode for series id=%d",
               log: log, type: .error, titleIdInt)
        completion(INPlayMediaIntentResponse(code: .failure, userActivity: nil))
    }

    /// Look up the series in the home feed's "Resume Playing" carousel
    /// and build a `PlaybackRoute` from the resume hints the server
    /// stamped onto the Title entry. Returns nil if the series isn't
    /// in the resume list — typically because the user has never
    /// started it.
    @MainActor
    private static func resumeRouteFromHomeFeed(
        seriesId: TitleID,
        dataModel: OnlineDataModel
    ) async throws -> PlaybackRoute? {
        let feed = try await dataModel.homeFeed()
        guard let resumeCarousel = feed.carousels.first(where: { $0.name == "Resume Playing" }) else {
            return nil
        }
        guard let item = resumeCarousel.items.first(where: { $0.id.rawValue == seriesId.rawValue }) else {
            return nil
        }
        guard item.playable, let transcodeId = item.transcodeId else {
            return nil
        }
        // Episode-context labels for the player chrome. The server
        // populates resume_season_number / resume_episode_number for
        // TV entries; movies in the carousel won't have these set.
        let seasonNumber = item.proto.hasResumeSeasonNumber ? Int(item.proto.resumeSeasonNumber) : nil
        let episodeNumber = item.proto.hasResumeEpisodeNumber ? Int(item.proto.resumeEpisodeNumber) : nil
        let episodeName = item.proto.hasResumeEpisodeName ? item.proto.resumeEpisodeName : nil
        return PlaybackRoute(
            transcodeId: transcodeId,
            titleName: item.name,
            episodeName: episodeName,
            hasSubtitles: false, // homeFeed item doesn't carry per-transcode subs info
            nextEpisode: nil,
            seasonNumber: seasonNumber,
            episodeNumber: episodeNumber
        )
    }

    /// Fallback when a series isn't in the resume list: fetch its
    /// season list, then the first season's episode list, and play
    /// the first playable episode.
    @MainActor
    private static func firstEpisodeRoute(
        seriesId: TitleID,
        dataModel: OnlineDataModel
    ) async throws -> PlaybackRoute? {
        let seasons = try await dataModel.seasons(titleId: seriesId)
        guard let firstSeason = seasons.sorted(by: { $0.seasonNumber < $1.seasonNumber }).first else {
            return nil
        }
        let episodes = try await dataModel.episodes(titleId: seriesId, season: firstSeason.seasonNumber)
        guard let firstEpisode = episodes
            .sorted(by: { $0.episodeNumber < $1.episodeNumber })
            .first(where: { $0.playable && $0.transcodeId != nil }),
            let transcodeId = firstEpisode.transcodeId else {
            return nil
        }
        // Series name for the player chrome.
        let detail = try await dataModel.titleDetail(id: seriesId)
        return PlaybackRoute(
            transcodeId: transcodeId,
            titleName: detail.name,
            episodeName: firstEpisode.name,
            hasSubtitles: firstEpisode.hasSubtitles,
            nextEpisode: nil,
            seasonNumber: firstEpisode.seasonNumber,
            episodeNumber: firstEpisode.episodeNumber
        )
    }

    /// `.continueInApp` tells iOS to foreground us so the full-screen
    /// player can actually render. `.success` (which we use for audio)
    /// keeps the app backgrounded — fine for music since Now Playing
    /// surfaces handle it, but for video the AVPlayer has nowhere to
    /// draw and you get audio-only with eventual stall.
    @MainActor
    private static func videoResponse() -> INPlayMediaIntentResponse {
        // Apple's docs say `.continueInApp` should carry a userActivity
        // that describes what's continuing. We hand in a bare INPlayMediaIntent
        // activity — the actual route is sitting in
        // VideoPlaybackCoordinator.shared.pendingRoute and ContentView's
        // .onChange picks it up the moment we come to foreground.
        let activity = NSUserActivity(activityType: NSStringFromClass(INPlayMediaIntent.self))
        return INPlayMediaIntentResponse(code: .continueInApp, userActivity: activity)
    }

    // MARK: - INSearchForMediaIntentHandling

    /// Siri calls this for "Watch X" (and some other media-search verbs)
    /// when she can't identify X from a global catalog. We reuse the
    /// same catalog search to return matching `INMediaItem`s; if a
    /// hit lands, Siri then dispatches `INPlayMediaIntent` to actually
    /// start playback — which our other handler covers. Declaration
    /// alone is what unlocks "Watch" routing; the resolution returns
    /// give Siri something to confirm verbally ("Found Top Gun. Play it?").
    func resolveMediaItems(
        for intent: INSearchForMediaIntent,
        with completion: @escaping ([INMediaItemResolutionResult]) -> Void
    ) {
        let phrase = intent.mediaSearch?.mediaName ?? ""
        os_log("search.resolveMediaItems: phrase=%{public}@",
               log: log, type: .default, phrase)
        guard !phrase.isEmpty else {
            completion([])
            return
        }
        let box = SiriCompletion(call: completion)
        Task { @MainActor in
            await Self.resolveSearch(phrase: phrase, completion: box.call)
        }
    }

    @MainActor
    private static func resolveSearch(
        phrase: String,
        completion: @escaping ([INMediaItemResolutionResult]) -> Void
    ) async {
        guard let dataModel = AppServices.shared.dataModel else {
            os_log("search.resolve: dataModel not ready", log: log, type: .error)
            completion([])
            return
        }
        do {
            let response = try await dataModel.search(query: phrase)
            for result in response.results {
                if let item = makeMediaItem(from: result) {
                    completion([INMediaItemResolutionResult.success(with: item)])
                    return
                }
            }
            completion([])
        } catch {
            os_log("search.resolve: failed: %{public}@",
                   log: log, type: .error, String(describing: error))
            completion([])
        }
    }

    /// `INSearchForMediaIntent.handle` is just a stub — the actual
    /// playback dispatch happens via `INPlayMediaIntent` that Siri
    /// fires after the search resolution lands. We return `.success`
    /// so Siri considers the search satisfied.
    func handle(
        intent: INSearchForMediaIntent,
        completion: @escaping (INSearchForMediaIntentResponse) -> Void
    ) {
        os_log("search.handle: returning .success", log: log, type: .default)
        completion(INSearchForMediaIntentResponse(code: .success, userActivity: nil))
    }

    // MARK: - Car context detection

    /// True when the phone is paired to a CarPlay head unit (scene
    /// connected) OR audio is currently routing to a car system.
    /// We refuse video dispatches under either condition.
    @MainActor
    private static var isInCarContext: Bool {
        if AppServices.shared.isCarPlayConnected { return true }
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        return outputs.contains { $0.portType == .carAudio }
    }
}
