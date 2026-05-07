import CarPlay
import UIKit

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
        logger.info("CarPlay scene connected")
        self.interfaceController = interfaceController
        AppServices.shared.whenReady { [weak self] in
            self?.installBrowseHierarchy()
        }
        // Show a loading placeholder right away so the head unit
        // doesn't sit on a blank screen during the cold-launch race
        // (CarPlay scene activates before SwiftUI App's init in
        // some scenarios — see AppServices.whenReady).
        if !AppServices.shared.isReady {
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
        return CPListTemplate(title: "MediaManager", sections: [section])
    }

    private func installBrowseHierarchy() {
        guard let interfaceController else { return }
        let controller = CarPlayBrowseController(interfaceController: interfaceController)
        browseController = controller
        controller.install()
    }
}
