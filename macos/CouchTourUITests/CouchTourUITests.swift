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
        // A `.menu` Picker surfaces as a pop-up button on macOS, so match by identifier only.
        let sort = app.descendants(matching: .any)["uih.sort"].firstMatch
        XCTAssertTrue(sort.waitForExistence(timeout: 10))

        let newer = app.staticTexts["1998-12-31"]
        let older = app.staticTexts["1997-11-22"]
        XCTAssertTrue(newer.exists)
        XCTAssertTrue(older.exists)
        XCTAssertLessThan(newer.frame.minY, older.frame.minY)

        sort.click()
        let oldestFirst = app.menuItems["Date (Oldest First)"]
        XCTAssertTrue(oldestFirst.waitForExistence(timeout: 5))
        oldestFirst.click()

        let reordered = expectation(for: NSPredicate { _, _ in older.frame.minY < newer.frame.minY },
                                    evaluatedWith: nil)
        wait(for: [reordered], timeout: 5)
    }

    func testTagFilterNarrowsShowList() {
        let tag = app.descendants(matching: .any)["uih.tag"].firstMatch
        XCTAssertTrue(tag.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["1997-11-22"].exists)

        tag.click()
        let jam = app.menuItems["jam"]
        XCTAssertTrue(jam.waitForExistence(timeout: 5))
        jam.click()

        XCTAssertTrue(app.staticTexts["1998-12-31"].waitForExistence(timeout: 5))
        let gone = expectation(for: NSPredicate(format: "exists == false"),
                               evaluatedWith: app.staticTexts["1997-11-22"])
        wait(for: [gone], timeout: 5)
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
        let large = app.descendants(matching: .any)["uih.artwork.large"].firstMatch
        XCTAssertTrue(large.waitForExistence(timeout: 10))
        // The label comes from ArtworkView itself (artist + badge); the harness adds no override.
        XCTAssertTrue(large.label.contains("1980-01-02"), "label was: \(large.label)")
    }

    func testLikedTrackResolutionErrorAppearsWithRows() {
        app.terminate()
        app.launchEnvironment["COUCHTOUR_UI_TEST_SCREEN"] = "library"
        app.launch()

        let row = app.buttons["library.row.liked-uih-liked-1"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.click()

        let error = app.staticTexts["library.play_error"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        // A SwiftUI Text surfaces its string as the element's value, not its label.
        let message = error.value as? String ?? ""
        XCTAssertTrue(message.contains("Harness Liked Track"), "value was: \(message)")
        XCTAssertTrue(row.exists)
    }

    func testLikedTrackResolutionPreservesTransientRequestError() {
        app.terminate()
        app.launchEnvironment["COUCHTOUR_UI_TEST_SCREEN"] = "resume_transient_error"
        app.launch()

        let statusText = app.staticTexts["uih.resume_status"]
        let expectedCode = URLError(.notConnectedToInternet).errorCode
        let expectedText = "URLError:\(expectedCode)"
        // The element renders "pending" before the harness `.task` finishes, so existence
        // alone isn't completion; wait for the injected result itself.
        let resolved = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in
                ((statusText.value as? String) ?? statusText.label) == expectedText
            },
            object: nil
        )
        XCTAssertEqual(XCTWaiter().wait(for: [resolved], timeout: 10), .completed,
                       "status was: \((statusText.value as? String) ?? statusText.label)")
        let actual = (statusText.value as? String) ?? statusText.label
        XCTAssertEqual(actual, expectedText)

        let invalidIdText = app.staticTexts["uih.invalid_id_status"]
        XCTAssertTrue(invalidIdText.waitForExistence(timeout: 5))
        let invalidIdActual = (invalidIdText.value as? String) ?? invalidIdText.label
        XCTAssertEqual(invalidIdActual, "ResumeError.unresumable")

        let missingTrackText = app.staticTexts["uih.missing_track_status"]
        XCTAssertTrue(missingTrackText.waitForExistence(timeout: 5))
        let missingTrackActual = (missingTrackText.value as? String) ?? missingTrackText.label
        XCTAssertEqual(missingTrackActual, "ResumeError.unresumable")

        let unplayableTrackText = app.staticTexts["uih.unplayable_track_status"]
        XCTAssertTrue(unplayableTrackText.waitForExistence(timeout: 5))
        let unplayableTrackActual = (unplayableTrackText.value as? String) ?? unplayableTrackText.label
        XCTAssertEqual(unplayableTrackActual, "ResumeError.unresumable")
    }

    func testArtistsScreenExposesArtistsList() {
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COUCHTOUR_UI_TEST_MODE")
        app.launchEnvironment["COUCHTOUR_UI_TEST_ARTISTS"] = "1"
        app.launch()

        let nav = app.descendants(matching: .any)["sidebar.nav.artists"].firstMatch
        XCTAssertTrue(nav.waitForExistence(timeout: 10))
        nav.click()

        let screen = app.descendants(matching: .any)["artists.screen"].firstMatch
        XCTAssertTrue(screen.waitForExistence(timeout: 10))

        let list = app.descendants(matching: .any)["artists.list"].firstMatch
        if !list.waitForExistence(timeout: 10) {
            print("AXDUMP-BEGIN\n\(app.debugDescription)\nAXDUMP-END")
            XCTFail("artists.list not found")
        }

        let moeRow = app.descendants(matching: .any)["artists.row.relisten.moe"].firstMatch
        XCTAssertTrue(moeRow.waitForExistence(timeout: 10), "artists.row.relisten.moe not found in artists.list")
        moeRow.click()

        let artistScreen = app.descendants(matching: .any)["artist.screen"].firstMatch
        XCTAssertTrue(artistScreen.waitForExistence(timeout: 10), "artist.screen not found after clicking moe row")
    }

    func testNavReachesEveryDestination() {
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COUCHTOUR_UI_TEST_MODE")
        app.launchEnvironment["COUCHTOUR_UI_TEST_ARTISTS"] = "1"
        app.launch()

        let destinations: [(nav: String, screen: String)] = [
            ("sidebar.nav.home", "home.screen"),
            ("sidebar.nav.artists", "artists.screen"),
            ("sidebar.nav.search", "search.screen"),
            ("sidebar.nav.library", "library.screen"),
            ("sidebar.nav.history", "history.screen"),
            ("sidebar.nav.settings", "settings.screen"),
        ]

        for dest in destinations {
            let nav = app.descendants(matching: .any)[dest.nav].firstMatch
            XCTAssertTrue(nav.waitForExistence(timeout: 10), "Sidebar nav item \(dest.nav) not found")
            nav.click()

            let screen = app.descendants(matching: .any)[dest.screen].firstMatch
            XCTAssertTrue(screen.waitForExistence(timeout: 10), "Screen identifier \(dest.screen) not found after clicking \(dest.nav)")

            if dest.screen == "settings.screen" {
                app.typeKey("w", modifierFlags: .command)
            }
        }

        let homeNav = app.descendants(matching: .any)["sidebar.nav.home"].firstMatch
        XCTAssertTrue(homeNav.waitForExistence(timeout: 10))
        homeNav.click()
        XCTAssertTrue(app.descendants(matching: .any)["home.screen"].firstMatch.waitForExistence(timeout: 10))
    }

    func testColdStartPresentsSidebarNavHome() {
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COUCHTOUR_UI_TEST_MODE")
        app.launch()

        let homeNav = app.descendants(matching: .any)["sidebar.nav.home"].firstMatch
        XCTAssertTrue(homeNav.waitForExistence(timeout: 10), "sidebar.nav.home not present after fresh launch")
        XCTAssertTrue(homeNav.isHittable, "sidebar.nav.home not hittable after fresh launch")
    }
}

