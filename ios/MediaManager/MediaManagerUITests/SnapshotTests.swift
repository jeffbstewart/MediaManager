import XCTest

/// App Store screenshot capture. Drives the production navigation
/// stack on a fresh simulator (no UserDefaults, no keychain, no
/// cached server) against the public demo server.
///
/// Server URL and credentials arrive via `app.launchEnvironment`,
/// sourced by `lifecycle/capture-ios-screenshots.sh` from
/// `app_store_demo_setup/secrets/.env`. The `-MMSnapshotMode` launch
/// arg makes views suppress any rendering of the connected host —
/// the demo URL must never appear in a submitted screenshot. See
/// `MediaManager/Services/SnapshotMode.swift`.
///
/// Output: six PNGs per device, attached to the .xcresult bundle
/// with `.keepAlways` lifetime. The wrapper script extracts them
/// via `xcrun xcresulttool`.
@MainActor
final class SnapshotTests: XCTestCase {

    let app = XCUIApplication()

    /// Generous overall budget — login + two ToS dialogs + a catalog
    /// fetch on a cold simulator can take a while.
    private static let stepTimeout: TimeInterval = 30

    override func setUp() async throws {
        try await super.setUp()
        continueAfterFailure = false

        let env = ProcessInfo.processInfo.environment
        guard let serverURL = env["MM_SNAPSHOT_SERVER_URL"], !serverURL.isEmpty,
              let username  = env["MM_SNAPSHOT_USERNAME"],   !username.isEmpty,
              let password  = env["MM_SNAPSHOT_PASSWORD"],   !password.isEmpty
        else {
            XCTFail("MM_SNAPSHOT_SERVER_URL / MM_SNAPSHOT_USERNAME / MM_SNAPSHOT_PASSWORD must be passed to the test runner via TEST_RUNNER_* env vars")
            return
        }

        app.launchArguments = ["-MMSnapshotMode"]
        app.launchEnvironment = [
            "MM_SNAPSHOT_SERVER_URL": serverURL,
            "MM_SNAPSHOT_USERNAME": username,
            "MM_SNAPSHOT_PASSWORD": password,
        ]
        app.launch()
    }

    func testViewerShots() throws {
        let env = ProcessInfo.processInfo.environment
        let serverURL = env["MM_SNAPSHOT_SERVER_URL"]!
        let username  = env["MM_SNAPSHOT_USERNAME"]!
        let password  = env["MM_SNAPSHOT_PASSWORD"]!

        // -----------------------------------------------------------
        // Shot 02: app-level Privacy + ToS gate (first thing rendered
        // on a fresh launch). Captured BEFORE shot 01 because the
        // policy gate hides everything else until accepted — see
        // MediaManagerApp.swift body.
        // -----------------------------------------------------------
        let agreeButton = app.buttons["app-policy-agree"]
        XCTAssertTrue(
            agreeButton.waitForExistence(timeout: Self.stepTimeout),
            "expected the app-policy 'I Agree' button on launch")
        attach(name: "02-app-policy")
        agreeButton.tap()

        // -----------------------------------------------------------
        // Shot 01: ServerSetupView in its empty state — no URL typed
        // yet, SSDP suppressed in snapshot mode so the discovered-host
        // banner cannot leak the demo URL.
        // -----------------------------------------------------------
        let urlField = app.textFields["server-url-field"]
        XCTAssertTrue(
            urlField.waitForExistence(timeout: Self.stepTimeout),
            "expected the server-URL text field after accepting app policy")
        attach(name: "01-server-setup")

        // Type the URL and connect (post-capture, so the typed value
        // is not in any screenshot).
        urlField.tap()
        urlField.typeText(serverURL)
        app.buttons["server-connect"].tap()

        // -----------------------------------------------------------
        // Shot 03: LoginView with username pre-filled, password
        // empty (SecureField masks it anyway, but we capture before
        // typing for hygiene). LoginView in snapshot mode suppresses
        // the host string at line 23.
        // -----------------------------------------------------------
        let usernameField = app.textFields["login-username"]
        XCTAssertTrue(
            usernameField.waitForExistence(timeout: Self.stepTimeout),
            "expected the login username field after connecting")
        usernameField.tap()
        usernameField.typeText(username)
        // Dismiss the keyboard so the chrome under it is visible.
        app.tap()  // tapping the window dismisses the keyboard on iOS
        attach(name: "03-login")

        let passwordField = app.secureTextFields["login-password"]
        XCTAssertTrue(passwordField.waitForExistence(timeout: 5))
        passwordField.tap()
        passwordField.typeText(password)
        app.buttons["login-submit"].tap()

        // -----------------------------------------------------------
        // Optional: the server-side TermsAgreementView may appear if
        // this user's terms_of_use_accepted_at is NULL. Handle it if
        // present; otherwise skip. Not in the captured manifest —
        // App Store reviewers will see their own ToS dialog with the
        // reviewer-* accounts.
        // -----------------------------------------------------------
        let serverContinue = app.buttons["server-terms-continue"]
        if serverContinue.waitForExistence(timeout: 5) {
            app.buttons["server-terms-privacy-checkbox"].tap()
            app.buttons["server-terms-terms-checkbox"].tap()
            serverContinue.tap()
        }

        // -----------------------------------------------------------
        // Shot 04: Home tab. After login we land on .home (selectedTab
        // default in ContentView). Wait for any carousel content or
        // the navigation title to settle.
        // -----------------------------------------------------------
        let homeTitle = app.navigationBars["Household Disc Keeper"]
            .firstMatch
        // On iPhone the split view collapses; the sidebar title is
        // shown initially. Wait for either the sidebar title OR a
        // direct Home heading.
        XCTAssertTrue(
            homeTitle.waitForExistence(timeout: Self.stepTimeout)
            || app.staticTexts["Home"].waitForExistence(timeout: 5),
            "expected to reach the authenticated app after login")

        // On compact (iPhone) the sidebar shows; on regular (iPad)
        // both panes are visible. Either way, "Home" is the default
        // selection so the detail view is HomeView already. Give the
        // carousels a moment to populate.
        Thread.sleep(forTimeInterval: 3)
        attach(name: "04-home")

        // -----------------------------------------------------------
        // Shot 05: Movies catalog. Tap the "Movies" sidebar row.
        // SwiftUI's List exposes rows as cells (not buttons) at the
        // XCUITest layer — match by accessibility label on either.
        // -----------------------------------------------------------
        navigateToSidebarRow(named: "Movies")
        let moviesNavTitle = app.navigationBars
            .matching(NSPredicate(format: "identifier BEGINSWITH 'Movies ('"))
            .firstMatch
        _ = moviesNavTitle.waitForExistence(timeout: Self.stepTimeout)
        Thread.sleep(forTimeInterval: 3) // poster thumbnails
        attach(name: "05-catalog-movies")

        // -----------------------------------------------------------
        // Shot 06: Sherlock Holmes (TV title 33) detail page. Tap
        // "TV Shows" sidebar → tap the Sherlock tile.
        // -----------------------------------------------------------
        navigateToSidebarRow(named: "TV Shows")
        let tvNavTitle = app.navigationBars
            .matching(NSPredicate(format: "identifier BEGINSWITH 'TV Shows ('"))
            .firstMatch
        _ = tvNavTitle.waitForExistence(timeout: Self.stepTimeout)
        Thread.sleep(forTimeInterval: 3)

        // Find a tile by its visible name. PosterCard renders
        // Text(title.name); XCUITest exposes that as a StaticText
        // with the title name as label. Tapping it routes through
        // SwiftUI hit-testing to the enclosing NavigationLink.
        let sherlockTile = app.staticTexts
            .matching(NSPredicate(format: "label CONTAINS[c] 'sherlock'"))
            .firstMatch
        XCTAssertTrue(
            sherlockTile.waitForExistence(timeout: Self.stepTimeout),
            "expected a TV tile containing 'Sherlock' in the catalog")
        sherlockTile.tap()

        Thread.sleep(forTimeInterval: 4) // poster + episode list
        attach(name: "06-title-detail-tv")
    }

    /// Navigate to a sidebar row by its visible label. SwiftUI's
    /// `List` + `Label(text, systemImage:)` lands the visible string
    /// on a `StaticText` inside an `Other` inside a `Cell`. On iPad
    /// regular size class HomeView (visible in the detail pane) also
    /// shows a "Movies" section header — same string in two places,
    /// so we scope to cells whose descendants carry the target label.
    /// That restricts the match to actual List rows in the sidebar.
    ///
    /// On iPhone compact size class the sidebar starts hidden behind
    /// a leading "back" chevron in the detail nav bar; we tap that
    /// to reveal the sidebar first if the row isn't present yet.
    /// iPad regular size class shows the sidebar permanently and
    /// the chevron tap is a no-op.
    private func navigateToSidebarRow(named label: String) {
        let cellPredicate = NSPredicate(format: "label == %@", label)
        let row = app.cells.containing(cellPredicate).firstMatch

        if !row.waitForExistence(timeout: 3) {
            let leadingNavButton = app.navigationBars
                .firstMatch
                .buttons
                .element(boundBy: 0)
            if leadingNavButton.exists {
                leadingNavButton.tap()
            }
        }

        XCTAssertTrue(
            row.waitForExistence(timeout: 10),
            "expected sidebar row '\(label)'")
        row.tap()
    }

    /// Capture the current screen as an XCTAttachment with `.keepAlways`
    /// so the wrapper script can pull the PNG out of the .xcresult.
    /// The name becomes the filename stem on disk.
    private func attach(name: String) {
        dismissSystemBanners()
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// On a fresh simulator, iOS posts a system notification banner
    /// (e.g. "Ready for Apple Intelligence") that sits at the top of
    /// the screen for ~10 seconds. It would overlay app content in
    /// any screenshot taken during that window. Querying the banner
    /// by accessibility identifier is fragile (the id changes between
    /// iOS versions), so we perform a coordinate-based swipe from a
    /// point near the top of the screen — banner present: dismissed;
    /// banner absent: harmless swipe over empty chrome.
    private func dismissSystemBanners() {
        let window = app.windows.firstMatch
        // 50% across, ~7% down — inside any banner's bounds, outside
        // any tappable app chrome we care about. Swipe upward off
        // the top edge to flick the banner away.
        let start = window.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.07))
        let end = window.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: -0.05))
        start.press(forDuration: 0.05, thenDragTo: end)
        // Give the system a beat to animate the dismissal before we
        // grab the screenshot.
        Thread.sleep(forTimeInterval: 0.5)
    }
}
