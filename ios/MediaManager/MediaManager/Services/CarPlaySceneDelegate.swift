import CarPlay
import UIKit
import MediaManagerCore
import MediaManagerProtos

private let logger = MMLogger(category: "CarPlay")

/// CarPlay scene root. Activated by iOS when a head unit (or the
/// Xcode CarPlay simulator window) connects. Owns the
/// CPInterfaceController for its lifetime — that's the navigation
/// stack equivalent for CarPlay templates.
///
/// First-pass scope: render a single "MediaManager" CPListTemplate
/// with a placeholder row so we can verify the scene plumbing fires
/// in the simulator. Subsequent passes wire albums / playlists /
/// recently-played + Play All, all routed through the same
/// AudioPlayerManager the in-app mini-player uses.
@MainActor
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {

    /// Held for the lifetime of the connection. iOS calls
    /// `didDisconnectInterfaceController` when the user unplugs,
    /// switches apps, or kills CarPlay; we drop the reference there.
    private var interfaceController: CPInterfaceController?
    private var browseController: CarPlayBrowseController?

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        // Snapshot enough state at scene-connect to triage "Couldn't
        // load" reports from the car. The two interesting axes are:
        // (1) is AppServices populated yet — if not, we're racing the
        // SwiftUI App's init and the user will see "App not ready"
        // until populate() fires; (2) has KeychainService got an
        // access token — if not, the gRPC calls will come back
        // UNAUTHENTICATED and the user needs to open the app on the
        // phone to re-auth.
        let isReady = AppServices.shared.isReady
        let hasToken = KeychainService.load(key: .accessToken) != nil
        let hasRefresh = KeychainService.load(key: .refreshToken) != nil
        logger.info("CarPlay scene connected: appServicesReady=\(isReady) hasAccessToken=\(hasToken) hasRefreshToken=\(hasRefresh)")
        self.interfaceController = interfaceController
        AppServices.shared.whenReady { [weak self] in
            logger.info("CarPlay scene: AppServices ready, refreshing tokens before loading data")
            self?.refreshTokensAndInstallBrowseHierarchy()
        }
        // Show a loading placeholder right away so the head unit
        // doesn't sit on a blank screen during the cold-launch race
        // (CarPlay scene activates before SwiftUI App's init in
        // some scenarios — see AppServices.whenReady).
        if !isReady {
            logger.info("CarPlay scene: AppServices not ready yet, showing loading template")
            interfaceController.setRootTemplate(loadingTemplate(), animated: false)
        }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        logger.info("CarPlay scene disconnected")
        self.interfaceController = nil
        self.browseController = nil
    }

    // MARK: - Templates

    private func loadingTemplate() -> CPListTemplate {
        let item = CPListItem(text: "Loading…", detailText: nil)
        let section = CPListSection(items: [item])
        return CPListTemplate(title: "Household Disc Keeper", sections: [section])
    }

    /// Refresh tokens (in case they expired while the app was suspended),
    /// then install the browse hierarchy. Tokens may be stale when CarPlay
    /// opens if the phone's been locked or the app suspended — the long-lived
    /// refresh token can restore access without requiring re-auth.
    private func refreshTokensAndInstallBrowseHierarchy() {
        guard let authManager = AppServices.shared.authManager else {
            logger.error("refreshTokensAndInstallBrowseHierarchy: authManager is nil")
            installBrowseHierarchy()
            return
        }
        Task { [weak self] in
            logger.info("refreshTokensAndInstallBrowseHierarchy: refreshing tokens")
            await authManager.refreshTokenNow()
            await MainActor.run {
                self?.installBrowseHierarchy()
            }
        }
    }

    private func installBrowseHierarchy() {
        guard let interfaceController else { return }
        let controller = CarPlayBrowseController(interfaceController: interfaceController)
        browseController = controller
        controller.install()
    }
}
