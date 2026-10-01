import XCTest

final class CouchTourUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["COUCHTOUR_UI_TEST_MODE"] = "1"
        app.launch()
    }

    func testSortControlReordersShows() {
        let sort = app.menuButtons["uih.sort"]
        XCTAssertTrue(sort.waitForExistence(timeout: 10))

        XCTAssertTrue(app.staticTexts["1998-12-31"].exists)

        sort.click()
        app.menuItems["Date (Oldest First)"].click()
        XCTAssertTrue(app.staticTexts["1997-11-22"].waitForExistence(timeout: 5))
    }

    func testTagFilterNarrowsShowList() {
        let tag = app.menuButtons["uih.tag"]
        XCTAssertTrue(tag.waitForExistence(timeout: 10))

        tag.click()
        app.menuItems["jam"].click()

        XCTAssertTrue(app.staticTexts["1998-12-31"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["1997-11-22"].exists)
    }

    func testRowContextMenuHasExpectedActions() {
        let row = app.staticTexts["1998-12-31"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))

        row.rightClick()
        XCTAssertTrue(app.menuItems["Open Show"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.menuItems["Mark Completed"].exists)
        XCTAssertTrue(app.menuItems["Remove from List"].exists)
        app.typeKey(.escape, modifierFlags: [])
    }

    func testLargeArtworkShowsDateBadge() {
        XCTAssertTrue(app.staticTexts["1980-01-02"].waitForExistence(timeout: 5))
    }
}
