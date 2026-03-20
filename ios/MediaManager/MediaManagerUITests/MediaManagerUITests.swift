import XCTest

@MainActor
final class MediaManagerUITests: XCTestCase {

    let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launch()
    }

    func testServerSetupScreenAppears() {
        XCTAssertTrue(app.staticTexts["Media Manager"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Enter your server address to get started."].exists)
        XCTAssertTrue(app.buttons["Connect"].exists)
    }

    func testConnectButtonDisabledWhenEmpty() {
        XCTAssertTrue(app.buttons["Connect"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Connect"].isEnabled)
    }
}
