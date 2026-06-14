import Foundation
import Intents
import os.log

private let log = OSLog(subsystem: "net.stewart.mediamanager",
                       category: "SiriIntentHandler")

/// Phase 1 in-app handler for INPlayMediaIntent (Path 1 architecture —
/// no Intents Extension target). Vended from `AppDelegate
/// .application(_:handlerFor:)` for each incoming intent.
///
/// Same stub behavior as the extension version: resolve to one
/// hardcoded media item, return `.success` so Siri confirms playback.
/// Real catalog search via gRPC lands in Phase 3; real playback via
/// AudioPlayerManager lands in Phase 2.
@objc(SiriIntentHandler)
final class SiriIntentHandler: NSObject, INPlayMediaIntentHandling {

    override init() {
        super.init()
        os_log("SiriIntentHandler init", log: log, type: .default)
    }

    func resolveMediaItems(
        for intent: INPlayMediaIntent,
        with completion: @escaping ([INPlayMediaMediaItemResolutionResult]) -> Void
    ) {
        let phrase = intent.mediaSearch?.mediaName ?? "(no phrase)"
        os_log("resolveMediaItems: phrase=%{public}@",
               log: log, type: .default, phrase)

        let item = INMediaItem(
            identifier: "track:1",
            title: "Phase 1 Stub Track",
            type: .song,
            artwork: nil,
            artist: "MediaManager Test"
        )
        completion([.success(with: item)])
    }

    func handle(
        intent: INPlayMediaIntent,
        completion: @escaping (INPlayMediaIntentResponse) -> Void
    ) {
        os_log("handle: returning .success (Phase 1 stub — no playback yet)",
               log: log, type: .default)
        // .success tells Siri "we're playing it" so she stops asking.
        // Actual AudioPlayerManager.play(...) wiring lands in Phase 2.
        let response = INPlayMediaIntentResponse(code: .success, userActivity: nil)
        completion(response)
    }
}
