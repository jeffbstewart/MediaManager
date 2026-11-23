import CarPlay
import Observation
import UIKit
import MediaManagerCore
import MediaManagerProtos

private let logger = MMLogger(category: "CarPlay")

/// CarPlay scene root. Activated by iOS when a head unit (or the
/// Xcode CarPlay simulator window) connects. Owns the
/// CPInterfaceController for its lifetime — that's the navigation
/// stack equivalent for CarPlay templates.
///
/// The root follows the phone's sign-in state (`CarPlayAccessPolicy`):
/// "Loading…" while the session restores, the music tabs
/// (`CarPlayBrowseController`) when signed in, and otherwise one row
/// saying why there is no music, to be fixed once not driving.
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
        // CarPlay doesn't pass through the phone's first screens, so it
        // honours the adults-only gate itself.
        if AgeGate.storedStatus.map({ AgeGatePolicy.refusesService(stored: $0) }) == true {
            logger.info("CarPlay scene: age gate refuses service")
            interfaceController.setRootTemplate(adultsOnlyTemplate(), animated: false, completion: nil)
            return
        }
        AppServices.shared.whenReady { [weak self] in
            logger.info("CarPlay scene: AppServices ready, following sign-in state")
            self?.followAccess()
        }
        // Show a loading placeholder right away so the head unit
        // doesn't sit on a blank screen during the cold-launch race
        // (CarPlay scene activates before SwiftUI App's init in
        // some scenarios — see AppServices.whenReady).
        if !isReady {
            logger.info("CarPlay scene: AppServices not ready yet, showing loading template")
            interfaceController.setRootTemplate(loadingTemplate(), animated: false, completion: nil)
        }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        logger.info("CarPlay scene disconnected")
        self.interfaceController = nil
        self.browseController = nil
        self.shownAccess = nil
    }

    // MARK: - Sign-in state

    /// What the screen shows now, so a state change that doesn't change
    /// it (Face ID completing, say) doesn't rebuild the tabs.
    private var shownAccess: CarPlayAccess?

    /// Shows what the phone's sign-in state allows, and follows it: a
    /// phone that signs in at a stop gets its music tabs, and one that
    /// is signed out mid-drive gets the explanation (#99).
    private func followAccess() {
        guard interfaceController != nil, let auth = AppServices.shared.authManager else { return }
        let access = withObservationTracking {
            CarPlayAccessPolicy.access(for: auth.state, awaitingBiometric: auth.awaitingBiometric)
        } onChange: {
            Task { @MainActor [weak self] in self?.followAccess() }
        }
        guard access != shownAccess else { return }
        shownAccess = access
        logger.info("CarPlay access: \(String(describing: access))")
        switch access {
        case .waiting:
            browseController = nil
            interfaceController?.setRootTemplate(loadingTemplate(), animated: false, completion: nil)
        case .ready:
            refreshTokensAndInstallBrowseHierarchy()
        case .unavailable(let reason):
            browseController = nil
            interfaceController?.setRootTemplate(unavailableTemplate(reason), animated: false, completion: nil)
        }
    }

    // MARK: - Templates

    /// The name under the app's icon, which a localized InfoPlist can
    /// translate and a branded build can change; never typed here.
    private static var appName: String {
        let info = Bundle.main.localizedInfoDictionary ?? Bundle.main.infoDictionary
        return ["CFBundleDisplayName", "CFBundleName"]
            .compactMap { info?[$0] as? String }
            .first { !$0.trimmingCharacters(in: .whitespaces).isEmpty } ?? ""
    }

    private func adultsOnlyTemplate() -> CPListTemplate {
        let item = CPListItem(
            text: "Only available to adults",
            detailText: "Household Disc Keeper isn't available on this phone.")
        return CPListTemplate(title: Self.appName, sections: [CPListSection(items: [item])])
    }

    private func unavailableTemplate(_ reason: CarPlayUnavailableReason) -> CPListTemplate {
        let item = CPListItem(text: String(localized: reason.title), detailText: String(localized: reason.detail))
        return CPListTemplate(title: Self.appName, sections: [CPListSection(items: [item])])
    }

    private func loadingTemplate() -> CPListTemplate {
        let item = CPListItem(text: "Loading…", detailText: nil)
        let section = CPListSection(items: [item])
        return CPListTemplate(title: Self.appName, sections: [section])
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
        // The refresh can sign the phone out; the explanation shown
        // since then must not be replaced by tabs that would fail.
        guard let interfaceController, shownAccess == .ready else { return }
        let controller = CarPlayBrowseController(interfaceController: interfaceController)
        browseController = controller
        controller.install()
    }
}
