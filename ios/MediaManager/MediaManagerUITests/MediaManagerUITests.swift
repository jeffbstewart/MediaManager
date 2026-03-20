import XCTest

@MainActor
final class MediaManagerUITests: XCTestCase {

    let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launch()
    }

    func testSidebarItemsExist() {
        XCTAssertTrue(app.staticTexts["Home"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Catalog"].exists)
        XCTAssertTrue(app.staticTexts["Search"].exists)
        XCTAssertTrue(app.staticTexts["Wish List"].exists)
    }

    func testNavigationTitle() {
        XCTAssertTrue(app.navigationBars["Media Manager"].waitForExistence(timeout: 5))
    }
}
