import Foundation

/// Process-wide accessor for the manager objects that SwiftUI holds
/// in @State at the App scope. The SwiftUI scene reaches them via
/// .environment(...); secondary scenes (CarPlay today, watchOS
/// counterpart later) need a different door.
///
/// Populated by MediaManagerApp.init right after it constructs the
/// managers. Kept optional because secondary scenes can connect
/// *before* SwiftUI gets a chance to initialise — most commonly when
/// the user starts the car with the phone already paired and the
/// system spins up the CarPlay scene first. In that case the scene
/// delegate registers a callback via `whenReady` and renders a
/// loading placeholder until the App's init populates the slots.
@MainActor
final class AppServices {
    static let shared = AppServices()

    private(set) var authManager: AuthManager?
    private(set) var audioPlayer: AudioPlayerManager?
    private(set) var dataModel: OnlineDataModel?
    private(set) var audioCache: AudioCacheManager?
    private(set) var bookCache: BookCacheManager?
    private(set) var imageProvider: ImageProvider?

    /// Closures the secondary scenes register while waiting for the
    /// App to populate. Called once on the next `populate(...)` call,
    /// then cleared. Order is best-effort registration order.
    private var readyCallbacks: [() -> Void] = []

    private init() {}

    /// Install the live managers. SwiftUI App calls this once at
    /// startup. Subsequent calls overwrite — the App is the source
    /// of truth, so a re-init scenario (rare; only on Scene
    /// reconnection in some simulator edge cases) keeps the latest
    /// references.
    func populate(
        authManager: AuthManager,
        audioPlayer: AudioPlayerManager,
        dataModel: OnlineDataModel,
        audioCache: AudioCacheManager,
        bookCache: BookCacheManager,
        imageProvider: ImageProvider
    ) {
        self.authManager = authManager
        self.audioPlayer = audioPlayer
        self.dataModel = dataModel
        self.audioCache = audioCache
        self.bookCache = bookCache
        self.imageProvider = imageProvider
        let pending = readyCallbacks
        readyCallbacks.removeAll()
        for cb in pending { cb() }
    }

    /// Run `cb` once the App has populated the services. If they're
    /// already populated, runs synchronously on the calling actor.
    /// Otherwise queues until the next populate(...) call.
    func whenReady(_ cb: @escaping () -> Void) {
        if audioPlayer != nil {
            cb()
        } else {
            readyCallbacks.append(cb)
        }
    }

    var isReady: Bool { audioPlayer != nil }
}
