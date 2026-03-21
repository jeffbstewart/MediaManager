import SwiftUI
import AVFoundation
import os

private let appLog = Logger(subsystem: "net.stewart.mediamanager", category: "app")

@main
struct MediaManagerApp: App {
    @State private var authManager = AuthManager()

    init() {
        NSLog("MMAPP app starting")
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
