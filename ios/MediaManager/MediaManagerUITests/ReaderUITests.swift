import XCTest

/// On-screen integration test for the ebook reader. Complements
/// `ReaderBridgeTests` (which exercises bridge mechanics in an
/// off-screen WKWebView and can't drive the full epub.js
/// boot-to-ready cycle — see that file's doc comment).
///
/// Runs against the bundled `test.epub` via the `-MMReaderTestMode`
/// launch arg, which routes the app straight into `BookReaderView`
/// with a sentinel `BookReaderRoute`. No login, no server, no home
/// feed — the whole production navigation stack is bypassed so the
/// test only sees the reader's render + interaction surface.
///
/// What this catches:
///   - reader.html / epub.js / jszip stop loading at all
///   - boot() never reaches `ready` (toolbar buttons stay disabled)
///   - SwiftUI chrome can't find the views it expects (toolbar
///     icons by accessibility label)
///
/// What this does NOT catch:
///   - real-server data flows (manifest fetch, byte streaming,
///     progress reporting) — covered separately by GrpcClient
///     unit tests.
///   - visual regressions — needs a snapshot library.
@MainActor
final class ReaderUITests: XCTestCase {

    let app = XCUIApplication()

    override func setUp() async throws {
        try await super.setUp()
        continueAfterFailure = false
        app.launchArguments = ["-MMReaderTestMode"]
        app.launch()
    }

    /// The reader's `.task` runs `loadAndOpen()` which does I/O
    /// (staging files, copying the bundled EPUB) and then waits for
    /// epub.js to fire `ready`. On a warm simulator this is well under
    /// 5 s; the 15 s ceiling is generous enough to absorb a cold-boot
    /// or a slow CI runner.
    private static let readerLoadTimeout: TimeInterval = 15

    func testReaderLaunchesAndShowsToolbar() {
        // Title shows immediately (set by SwiftUI's navigationTitle
        // before the WebView mounts).
        XCTAssertTrue(
            app.staticTexts["Test Book"].waitForExistence(timeout: 5),
            "expected the reader's nav title 'Test Book'")

        // The TOC button is wired up at view construction time but
        // .disabled until `status == .reading` (i.e. epub.js fired
        // its `ready` postMessage). When it becomes enabled, we know
        // the JS bridge succeeded end-to-end.
        let tocButton = app.buttons["list.bullet"]
        XCTAssertTrue(
            tocButton.waitForExistence(timeout: 5),
            "TOC toolbar button should exist (image: list.bullet)")

        // SwiftUI Button(.disabled(...)) sets `isEnabled` on the
        // accessibility element; we poll until enabled or fail.
        let enabledPredicate = NSPredicate(format: "isEnabled == true")
        expectation(for: enabledPredicate, evaluatedWith: tocButton)
        waitForExpectations(timeout: Self.readerLoadTimeout)

        // Once the reader is in `.reading` state, the percent label
        // also renders. It's monospaced digits like "0%", "1%", etc.
        // We just check that *some* percent label exists.
        let percentLabels = app.staticTexts.matching(
            NSPredicate(format: "label ENDSWITH '%'"))
        XCTAssertGreaterThan(
            percentLabels.count, 0,
            "expected the percent indicator to render once status==.reading")
    }

    /// Tapping the TOC button opens the chapter sheet. The fixture
    /// EPUB has three chapters — Chapter 1 / 2 / 3 — so we assert on
    /// at least one of those labels.
    func testTOCSheetListsChapters() {
        let tocButton = app.buttons["list.bullet"]
        XCTAssertTrue(tocButton.waitForExistence(timeout: 5))
        // Wait for the reader to finish loading before tapping.
        let enabled = NSPredicate(format: "isEnabled == true")
        expectation(for: enabled, evaluatedWith: tocButton)
        waitForExpectations(timeout: Self.readerLoadTimeout)

        tocButton.tap()

        // SwiftUI sheets host their content in a separate window;
        // looking up by static text traverses both.
        XCTAssertTrue(
            app.staticTexts["Chapter 1"].waitForExistence(timeout: 5),
            "TOC sheet should list 'Chapter 1' from the fixture EPUB")
    }

    /// Cycling theme should leave the reader in a `.reading` state —
    /// regression coverage for the 'dark mode stuck' bug from v2,
    /// where `themes.select` failed to repaint the already-rendered
    /// chunk. With `themes.override` the theme button stays
    /// interactive across cycles.
    func testThemeButtonRemainsResponsive() {
        // Theme icon starts as one of `sun.max` / `book.closed` /
        // `moon` (driven by UserDefaults). Match the whole family so
        // we don't tightly couple to whichever the user last set.
        // Match on identifier, not label: the identifier is always
        // the symbol name, but the system gives some symbols a spoken
        // label (`moon` reads as "Do Not Disturb"), so a label match
        // loses the button as soon as the cycle reaches dark.
        let themeButton = app.buttons.matching(
            NSPredicate(format: "identifier IN {'sun.max', 'book.closed', 'moon'}"))
            .element(boundBy: 0)
        XCTAssertTrue(themeButton.waitForExistence(timeout: 5))
        let enabled = NSPredicate(format: "isEnabled == true")
        expectation(for: enabled, evaluatedWith: themeButton)
        waitForExpectations(timeout: Self.readerLoadTimeout)

        // Tap three times to cycle back to the starting theme. The
        // button should stay enabled the whole way.
        for _ in 0..<3 {
            themeButton.tap()
            XCTAssertTrue(themeButton.isEnabled, "theme button stayed disabled after a cycle")
        }
    }
}

/// A title page whose text is hidden by moving it far off-screen must
/// still be one page long (issue #34).
///
/// Standard Ebooks and others show the title as artwork and keep the
/// text for assistive technology with `position: absolute; left:
/// -999em`. epub.js sizes a paginated section by measuring its
/// content, counted that text, and laid the title page out dozens of
/// pages wide: one real page, then a run of blank ones.
///
/// Uses `test-offscreen-title.epub`, the fixture the web reader's test
/// uses: a title page with the off-screen rule, then an imprint, then
/// a chapter.
@MainActor
final class ReaderOffscreenTitleTests: XCTestCase {

    let app = XCUIApplication()

    override func setUp() async throws {
        try await super.setUp()
        continueAfterFailure = false
        app.launchArguments = ["-MMReaderTestMode", "-MMReaderTestBook", "test-offscreen-title.epub"]
        app.launch()
    }

    /// The percent label only moves once epub.js has finished indexing
    /// the book's locations, which runs in the background after the
    /// reader reports ready. Under the test runner that can take
    /// several seconds, so a page turn made before it finishes shows
    /// its percent late. A blank-page fault never shows the right
    /// percent, however long the wait.
    private static let percentTimeout: TimeInterval = 20

    func testTitlePageIsOnePage() {
        // The TOC button enables when epub.js reports ready.
        let tocButton = app.buttons["list.bullet"]
        XCTAssertTrue(tocButton.waitForExistence(timeout: 5))
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: tocButton)
        waitForExpectations(timeout: 15)

        // The page text is inside epub.js's iframe, which the test
        // cannot read, so position is judged by the percent label. The
        // book has three one-page documents: 0%, 50% and 100%. Two
        // page turns from the title page must reach the third. With
        // blank pages after the title page, two turns reach the second
        // at best.
        XCTAssertTrue(app.staticTexts["0%"].waitForExistence(timeout: 5), "the book should open on the title page")
        let web = app.webViews.firstMatch
        XCTAssertTrue(web.waitForExistence(timeout: 5))
        let nextPage = web.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))

        nextPage.tap()
        XCTAssertTrue(
            app.staticTexts["50%"].waitForExistence(timeout: Self.percentTimeout),
            "one page turn from the title page should reach the imprint")
        nextPage.tap()
        XCTAssertTrue(
            app.staticTexts["100%"].waitForExistence(timeout: Self.percentTimeout),
            "two page turns should reach the chapter; staying short of it means the title or imprint page is more than one page long")
    }
}
