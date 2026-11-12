import Foundation
import Observation

/// Hand-off slot between non-view code (today: SiriIntentHandler) and
/// the root view's full-screen video player presentation. Set
/// `pendingRoute` to drive `ContentView`'s `.fullScreenCover` open;
/// `ContentView` reacts via `.onChange`, copies the route into its own
/// `@State`, then clears the slot.
///
/// Lives as a singleton (similar to AppServices) because SwiftUI views
/// and Swift code outside the view hierarchy both need to reach it.
@Observable
@MainActor
final class VideoPlaybackCoordinator {
    static let shared = VideoPlaybackCoordinator()

    private init() {}

    var pendingRoute: PlaybackRoute?

    func request(_ route: PlaybackRoute) {
        pendingRoute = route
    }

    func consume() -> PlaybackRoute? {
        let r = pendingRoute
        pendingRoute = nil
        return r
    }
}
