import XCTest

/// App Store screenshot capture. Drives the production navigation
/// stack on a fresh simulator (no UserDefaults, no keychain, no
/// cached server) against whichever server the capture script is
/// pointed at: a real library works, because of screenshot mode.
///
/// Server URL and credentials arrive via `app.launchEnvironment`,
/// supplied by `lifecycle/capture-ios-screenshots.sh`. The
/// `-MMSnapshotMode` launch arg turns on screenshot mode
/// (`ScreenshotMode` in MediaManagerCore): every title, person and
/// synopsis is replaced by an invented one, every image by generated
/// artwork, and the connected host is never rendered. Nothing in a
/// captured screen names a real work, so the test picks tiles by
/// position, never by name.
///
/// Output: up to ten PNGs per device, attached to the .xcresult bundle
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

    // The capture is two tests so the server's own terms screen can be
    // photographed without being used up. A server shows that screen
    // until the account agrees, which happens once per account, not
    // once per device. `testPreLoginShots` stops on it without
    // agreeing, so it can run on every device; `testViewerShots`
    // agrees and carries on into the app.

    /// Shots 01 to 03, plus 07 when the account still owes the
    /// server's terms. Leaves the account as it found it.
    func testPreLoginShots() throws {
        signIn(capturing: true)

        let serverContinue = app.buttons["server-terms-continue"]
        if serverContinue.waitForExistence(timeout: 8) {
            attach(name: "07-server-terms")
        }
        // Deliberately no agreement: see the note above.
    }

    /// Shots 04 to 06 and 08 to 10, signed in. Agrees to the server's terms if the
    /// account has not yet.
    func testViewerShots() throws {
        signIn(capturing: false)

        let serverContinue = app.buttons["server-terms-continue"]
        if serverContinue.waitForExistence(timeout: 5) {
            app.buttons["server-terms-privacy-checkbox"].tap()
            app.buttons["server-terms-terms-checkbox"].tap()
            serverContinue.tap()
        }

        // -----------------------------------------------------------
        // Shot 04: Home. After login we land on .home (selectedTab
        // default in ContentView). Home is the default selection on
        // both size classes; its navigation title is the signal that
        // login finished.
        // -----------------------------------------------------------
        XCTAssertTrue(
            app.navigationBars["Home"].waitForExistence(timeout: Self.stepTimeout)
            || app.staticTexts["Home"].waitForExistence(timeout: 5),
            "expected to reach the authenticated app after login")

        // Give the carousels a moment to populate.
        Thread.sleep(forTimeInterval: 4)
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
        Thread.sleep(forTimeInterval: 3)
        attach(name: "05-catalog-movies")

        // -----------------------------------------------------------
        // Shot 06: a TV show's detail page. Tap "TV Shows" in the
        // sidebar, then the first tile of the grid.
        // -----------------------------------------------------------
        navigateToSidebarRow(named: "TV Shows")
        let tvNavTitle = app.navigationBars
            .matching(NSPredicate(format: "identifier BEGINSWITH 'TV Shows ('"))
            .firstMatch
        _ = tvNavTitle.waitForExistence(timeout: Self.stepTimeout)
        Thread.sleep(forTimeInterval: 3)

        // A poster tile is a button labelled "<name>, <year>". Names
        // are invented in screenshot mode, so match on the shape of
        // the label and take the first one.
        let firstTile = app.buttons
            .matching(NSPredicate(format: "label MATCHES %@", ".+, (19|20)[0-9]{2}.*"))
            .firstMatch
        XCTAssertTrue(
            firstTile.waitForExistence(timeout: Self.stepTimeout),
            "expected at least one TV tile in the catalog")
        firstTile.tap()

        Thread.sleep(forTimeInterval: 4) // backdrop + episode list
        attach(name: "06-title-detail-tv")

        // -----------------------------------------------------------
        // Shots 08 to 10: the other libraries, each a sidebar row
        // away. (07 is the server terms screen, in the other test.)
        // -----------------------------------------------------------
        for (row, name) in [("Books", "08-books"), ("Music", "09-music"), ("Wish List", "10-wish-list")] {
            navigateToSidebarRow(named: row)
            Thread.sleep(forTimeInterval: 5) // covers and rows
            attach(name: name)
        }
    }

    /// From a fresh install to the moment after the login form is
    /// submitted: the app's own policy gate, server setup, login.
    /// With `capturing`, takes shots 02, 01 and 03 on the way, each
    /// before anything is typed into it.
    private func signIn(capturing: Bool) {
        let env = ProcessInfo.processInfo.environment
        let serverURL = env["MM_SNAPSHOT_SERVER_URL"]!
        let username  = env["MM_SNAPSHOT_USERNAME"]!
        let password  = env["MM_SNAPSHOT_PASSWORD"]!

        // Shot 02: the app-level Privacy + Terms gate, the first thing
        // rendered on a fresh launch. It hides everything else until
        // accepted — see MediaManagerApp.swift body.
        let agreeButton = app.buttons["app-policy-agree"]
        XCTAssertTrue(
            agreeButton.waitForExistence(timeout: Self.stepTimeout),
            "expected the app-policy 'I Agree' button on launch")
        // Flick away any first-boot system banner here, once, where
        // the swipe cannot disturb anything. Doing it before every
        // shot scrolled Home out of position on iPad.
        dismissSystemBanners()
        // On a short display that swipe scrolls the policy page; put
        // it back at the top.
        app.swipeDown()
        if capturing { attach(name: "02-app-policy") }
        agreeButton.tap()

        // Shot 01: ServerSetupView in its empty state. Discovery is
        // suppressed in screenshot mode, so no banner names a server.
        let urlField = app.textFields["server-url-field"]
        XCTAssertTrue(
            urlField.waitForExistence(timeout: Self.stepTimeout),
            "expected the server-URL text field after accepting app policy")
        if capturing { attach(name: "01-server-setup") }
        urlField.tap()
        urlField.typeText(serverURL)
        app.buttons["server-connect"].tap()

        // Shot 03: LoginView, empty, before anything is typed: no
        // keyboard over the form and no account name in a published
        // picture. The host line is suppressed in screenshot mode.
        let usernameField = app.textFields["login-username"]
        XCTAssertTrue(
            usernameField.waitForExistence(timeout: Self.stepTimeout),
            "expected the login username field after connecting")
        if capturing { attach(name: "03-login") }
        usernameField.tap()
        usernameField.typeText(username)

        let passwordField = app.secureTextFields["login-password"]
        XCTAssertTrue(passwordField.waitForExistence(timeout: 5))
        passwordField.tap()
        passwordField.typeText(password)
        app.buttons["login-submit"].tap()
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

        // Any sidebar row at all: its presence means the sidebar is on
        // screen, even when the wanted row is further down the list
        // and not built yet.
        let anySidebarRow = app.cells.containing(NSPredicate(
            format: "label IN {'Home', 'Movies', 'TV Shows', 'Books', 'Music', 'Collections', 'Tags', 'Family', 'Cameras', 'Live TV', 'Downloads', 'Profile', 'About'}"
        )).firstMatch

        for _ in 0..<6 {
            if row.waitForExistence(timeout: 3) { break }
            if anySidebarRow.exists {
                // Sidebar showing, row below the fold (a short display,
                // or the Duo unfolded): scroll the list itself. A
                // swipe on the app would scroll the detail pane.
                anySidebarRow.swipeUp()
                continue
            }
            // No sidebar: back out one level. From a title's page that
            // is two steps (page, then grid). The system back button
            // is in the navigation bar on most devices and in a side
            // strip on the iPhone Duo's outer display.
            let systemBack = app.buttons["BackButton"].firstMatch
            let leadingNavButton = app.navigationBars
                .firstMatch
                .buttons
                .element(boundBy: 0)
            if systemBack.exists {
                systemBack.tap()
            } else if leadingNavButton.exists {
                leadingNavButton.tap()
            } else {
                break
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
        // `XCUIScreen.main` is the device's first display. On an
        // unfolded iPhone Duo that is still the outer one, and
        // `app.screenshot()` comes back black there, so the Duo's
        // inner display cannot be captured from here (issue #30).
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
