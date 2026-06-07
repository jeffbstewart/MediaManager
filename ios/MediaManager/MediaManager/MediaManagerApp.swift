import SwiftUI
import AVFoundation

@MainActor
class AppDelegate: NSObject, UIApplicationDelegate {
    var downloadManager: DownloadManager?

    // Background URLSession no longer used — downloads go via gRPC streaming.

    /// Route scene connection requests to the right delegate. The
    /// SwiftUI App handles the default phone scene automatically;
    /// the CarPlay scene needs an explicit configuration so iOS knows
    /// to instantiate CarPlaySceneDelegate.
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if connectingSceneSession.role == .carTemplateApplication {
            let cfg = UISceneConfiguration(name: "CarPlay Configuration", sessionRole: connectingSceneSession.role)
            cfg.delegateClass = CarPlaySceneDelegate.self
            return cfg
        }
        return UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
    }
}

@main
struct MediaManagerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    // No default initializers on @State — they cause SwiftUI to construct a
    // throwaway value before init() runs, and AuthManager's restoreSession
    // spawns a strongly-captured Task that keeps the throwaway alive long
    // enough to start its own LogStreamer (resulting in two concurrent
    // streams to ObservabilityService).
    @State private var authManager: AuthManager
    @State private var downloadManager: DownloadManager
    @State private var bookCache: BookCacheManager
    /// Local flusher for reading-progress writes. The queue itself is
    /// a `ReadingProgressQueue.shared` singleton (actors don't fit
    /// SwiftUI's @Observable environment), the flusher is the
    /// observable wrapper that views interact with for `flushNow()`.
    @State private var progressFlusher: ProgressFlusher
    /// Single source of truth for audio playback. Drives the in-app
    /// mini-player + future full-screen Now Playing view, the system
    /// Now Playing surfaces (lock screen, Control Center, AirPods,
    /// CarPlay), and the future Watch remote. Created at app scope
    /// so audio survives navigation across tabs.
    @State private var audioPlayer: AudioPlayerManager
    /// Owns offline album storage. Held at app scope so downloads
    /// survive navigation, mirrors BookCacheManager's lifetime.
    @State private var audioCache: AudioCacheManager
    @State private var dataModel: OnlineDataModel
    @State private var imageProvider: ImageProvider
    /// First-launch gate. User must accept the app-level privacy
    /// policy + ToS before any other view renders. Stored in
    /// UserDefaults; never transmitted to any server.
    @State private var appPolicy: AppPolicyAgreement

    /// MetricKit subscriber that ships iOS crash diagnostics from
    /// previous launches into Binnacle. Held onto for the app
    /// lifetime so the subscription stays live; doesn't need to be
    /// observable / injected.
    private let crashReporter: CrashReporter

    init() {
        // Crash reporter first — registers MetricKit subscription
        // and drains any persisted crash from a previous launch
        // before any other init-time code can re-crash and steal
        // the attribution.
        crashReporter = CrashReporter()

        // Run any pending on-disk migrations BEFORE the cache
        // managers construct themselves, so they always see a
        // coherent download tree. v2 wipes video + book downloads
        // (audio is preserved) so the new offline-parity browse
        // surfaces can rely on the richer metadata format we now
        // persist at download time. See OfflineMigration.swift.
        OfflineMigration.runIfNeeded()

        // Configure audio session for media playback — plays through
        // speakers regardless of the silent switch and continues in
        // the background. Simulator is forgiving about both pieces of
        // configuration; device is not, and `try?` was swallowing the
        // error class that lets us tell setup failures from "audio
        // works but isn't reaching the speaker."
        //
        // - `.default` mode (not `.moviePlayback`) is the right pick
        //   for music streaming — `.moviePlayback` carries video-
        //   playback assumptions (longer interruption tolerance,
        //   route preferences) that can fight a music app on device.
        // - `.longFormAudio` policy hints to the system that this is
        //   a music app, enabling AirPlay 2 multi-room and the
        //   appropriate interruption / pre-emption behaviour.
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, policy: .longFormAudio)
            try session.setActive(true)
        } catch {
            // Use NSLog so this lands in the system log even before
            // MMLogger / Binnacle wiring is up.
            NSLog("[MediaManagerApp] AVAudioSession setup failed: \(error)")
        }

        let am = AuthManager()
        let dm = DownloadManager()
        let bc = BookCacheManager()
        let pf = ProgressFlusher(queue: ReadingProgressQueue.shared, downloads: dm)
        let ap = AudioPlayerManager()
        let ac = AudioCacheManager()
        let dataModel = OnlineDataModel(authManager: am, downloadManager: dm)
        let ip = ImageProvider(grpcClient: am.grpcClient)
        _authManager = State(initialValue: am)
        _downloadManager = State(initialValue: dm)
        _bookCache = State(initialValue: bc)
        _progressFlusher = State(initialValue: pf)
        _audioPlayer = State(initialValue: ap)
        _audioCache = State(initialValue: ac)
        _dataModel = State(initialValue: dataModel)
        _imageProvider = State(initialValue: ip)
        _appPolicy = State(initialValue: AppPolicyAgreement())

        // Configure + publish AppServices in init() rather than the
        // RootView.onAppear that used to run this block. CarPlay
        // scenes activate independently of the phone's UI scene —
        // if the population waits for onAppear, the head unit hangs
        // on "Loading…" until the user opens the iOS app on the
        // phone. Doing it here means a process cold-launch into
        // CarPlay (the user plugs in the phone with the app not
        // running) has AppServices ready by the time
        // CarPlaySceneDelegate's `didConnect` fires, so the browse
        // hierarchy installs immediately.
        dm.configure(apiClient: am.apiClient, grpcClient: am.grpcClient)
        bc.configure(grpcClient: am.grpcClient)
        pf.configure(grpcClient: am.grpcClient)
        ac.configure(apiClient: am.apiClient)
        ap.configure(apiClient: am.apiClient, imageProvider: ip, audioCache: ac)
        // Back-channel for AudioPlayerManager → DownloadManager.flushPendingListeningProgress.
        // See the previous in-onAppear comment block for why.
        ap.configureProgressFlusher { [dm] in
            await dm.flushPendingListeningProgress()
        }
        AppServices.shared.populate(
            audioPlayer: ap,
            dataModel: dataModel,
            audioCache: ac,
            bookCache: bc,
            imageProvider: ip)
    }

    var body: some Scene {
        WindowGroup {
            // UI-test hook: when launched with `-MMReaderTestMode`, root
            // straight into a NavigationStack rendering BookReaderView
            // against the bundled `test.epub`. Bypasses login, server
            // discovery, and the home feed — the test only needs the
            // reader pane reachable in a deterministic state. Production
            // launches don't carry the arg and fall through to RootView.
            if ProcessInfo.processInfo.arguments.contains("-MMReaderTestMode") {
                NavigationStack {
                    BookReaderView(route: BookReaderRoute(
                        mediaItemId: -1,
                        titleName: "Test Book",
                        testBundleEpub: "test.epub"))
                }
                .environment(authManager)
                .environment(downloadManager)
                .environment(bookCache)
                .environment(progressFlusher)
                .environment(audioPlayer)
                .environment(audioCache)
                .environment(dataModel)
                .environment(imageProvider)
            } else if !appPolicy.hasAgreed {
                // Hard gate: nothing else renders until the user
                // accepts the app-level privacy policy + ToS. Per
                // App Store guidelines + product requirements: the
                // user must see ONLY this screen on first launch
                // until they tap Agree. AppPolicyAgreement.accept()
                // flips hasAgreed and the next render falls through
                // to the normal RootView path.
                AppPolicyAgreementView()
                    .environment(appPolicy)
            } else {
                // Manager configuration + AppServices.populate moved
                // to init() — see the long comment there. RootView
                // no longer needs onAppear for that.
                RootView()
                    .environment(authManager)
                    .environment(downloadManager)
                    .environment(bookCache)
                    .environment(progressFlusher)
                    .environment(audioPlayer)
                    .environment(audioCache)
                    .environment(dataModel)
                    .environment(imageProvider)
            }
        }
    }
}
