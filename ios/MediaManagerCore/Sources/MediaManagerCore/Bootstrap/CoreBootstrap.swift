import Foundation

/// Construction surface for the manager objects every MediaManager
/// process needs (the main iOS app, the future Intents Extension, and
/// later the watchOS app). Lives in the shared package so the
/// extension can build the same auth + gRPC stack the main app uses
/// without duplicating wiring.
///
/// Deliberately scoped tight: only the "auth + downloads" pair lands
/// here for now. The app builds the audio / book / image / data-model
/// managers on top in its own `init`. As Phase B reveals what the
/// extension actually needs, we can promote more construction here.
@MainActor
public enum CoreBootstrap {

    /// Build the auth manager + the downloads manager and wire them
    /// to the same gRPC / HTTP client pair. Run from process-init
    /// before any UI scene or extension handler asks for services.
    public static func makeCoreServices() -> CoreServices {
        let authManager = AuthManager()
        let downloadManager = DownloadManager()
        downloadManager.configure(
            apiClient: authManager.apiClient,
            grpcClient: authManager.grpcClient
        )
        return CoreServices(authManager: authManager, downloadManager: downloadManager)
    }
}

/// Bundle of the managers `CoreBootstrap.makeCoreServices()` returns.
/// Held by the caller (app `@State` or extension property) for the
/// lifetime of the process.
@MainActor
public struct CoreServices {
    public let authManager: AuthManager
    public let downloadManager: DownloadManager
}
