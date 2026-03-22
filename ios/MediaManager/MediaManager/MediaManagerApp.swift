import SwiftUI
import AVFoundation

@MainActor
class AppDelegate: NSObject, UIApplicationDelegate {
    var downloadManager: DownloadManager?

    func application(_ application: UIApplication,
                     handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        downloadManager?.handleBackgroundSessionEvent(completionHandler: completionHandler)
    }
}

@main
struct MediaManagerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var authManager = AuthManager()
    @State private var downloadManager = DownloadManager()

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
                .environment(downloadManager)
                .onAppear {
                    appDelegate.downloadManager = downloadManager
                }
        }
    }
}
