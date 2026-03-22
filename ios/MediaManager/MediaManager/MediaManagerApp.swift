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
    @State private var dataModel: OnlineDataModel

    init() {
        // Configure audio session for media playback — plays through speakers
        // even when the silent switch is on, and continues in the background.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)

        let am = AuthManager()
        let dm = DownloadManager()
        _authManager = State(initialValue: am)
        _downloadManager = State(initialValue: dm)
        _dataModel = State(initialValue: OnlineDataModel(authManager: am, downloadManager: dm))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(authManager)
                .environment(downloadManager)
                .environment(dataModel)
                .onAppear {
                    appDelegate.downloadManager = downloadManager
                    downloadManager.configure(apiClient: authManager.apiClient, grpcClient: authManager.grpcClient)
                }
        }
    }
}
