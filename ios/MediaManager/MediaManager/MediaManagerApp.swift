import SwiftUI
import AVFoundation

@main
struct MediaManagerApp: App {
    @State private var authManager = AuthManager()

    init() {
        // Configure audio session for media playback — plays through speakers
        // even when the silent switch is on, and continues in the background.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(authManager)
        }
    }
}
