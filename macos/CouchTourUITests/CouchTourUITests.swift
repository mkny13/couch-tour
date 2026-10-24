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

        // Each sidebar nav item is a top-level destination. Clicking one must REPLACE the
        // path with exactly that route (AppModel.jump(to:)), not append it on top of
        // whatever was already on the stack. The toolbar breadcrumb exposes the full
        // path as its accessibility label ("Breadcrumb: Couch Tour, Artists, Search"),
        // so it is the discriminating signal: after a top-level jump the trail must name
        // exactly one destination. An append implementation would leave prior
        // destinations on the stack and the trail would grow, which these assertions
        // catch (#721). Reachability of each screen is kept below.
        let destinations: [(nav: String, screen: String, crumb: String?)] = [
            ("sidebar.nav.home", "home.screen", "Couch Tour"),
            ("sidebar.nav.artists", "artists.screen", "Couch Tour, Artists"),
            ("sidebar.nav.search", "search.screen", "Couch Tour, Search"),
            ("sidebar.nav.library", "library.screen", "Couch Tour, Playlists"),
            ("sidebar.nav.history", "history.screen", "Couch Tour, Listening"),
            // Settings opens its own window (openSettings()) rather than pushing onto
            // the NavigationStack, so there is no path depth to assert there.
            ("sidebar.nav.settings", "settings.screen", nil),
        ]

        for dest in destinations {
            let nav = app.descendants(matching: .any)[dest.nav].firstMatch
            XCTAssertTrue(nav.waitForExistence(timeout: 10), "Sidebar nav item \(dest.nav) not found")
            nav.click()

            let screen = app.descendants(matching: .any)[dest.screen].firstMatch
            XCTAssertTrue(screen.waitForExistence(timeout: 10),
                          "Screen identifier \(dest.screen) not found after clicking \(dest.nav)")

            if let crumb = dest.crumb {
                let expected = "Breadcrumb: \(crumb)"
                // Re-query inside the predicate: SwiftUI rebuilds the breadcrumb on
                // each path change, so a stale element reference would otherwise read
                // exists=false and mask the trail we are checking.
                let trail = expectation(for: NSPredicate { _, _ in
                    let breadcrumb = self.app.descendants(matching: .any)
                        .matching(NSPredicate(format: "label BEGINSWITH[cd] 'Breadcrumb:'"))
                        .firstMatch
                    return breadcrumb.exists && breadcrumb.label == expected
                }, evaluatedWith: nil)
                wait(for: [trail], timeout: 10)
            }

            if dest.screen == "settings.screen" {
                app.typeKey("w", modifierFlags: .command)
            }
        }

        let homeNav = app.descendants(matching: .any)["sidebar.nav.home"].firstMatch
        XCTAssertTrue(homeNav.waitForExistence(timeout: 10))
        homeNav.click()
        XCTAssertTrue(app.descendants(matching: .any)["home.screen"].firstMatch.waitForExistence(timeout: 10))
    }

    func testClearingQueryKeepsSidebarSearchOpen() {
        // Clearing the query used to pop `.search` even when the sidebar opened it, so the
        // in-page clear button threw the user (and the smoke journeys) off the Search screen (#730).
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COUCHTOUR_UI_TEST_MODE")
        app.launch()

        let nav = app.descendants(matching: .any)["sidebar.nav.search"].firstMatch
        XCTAssertTrue(nav.waitForExistence(timeout: 10))
        nav.click()

        let field = app.descendants(matching: .any)["search.field"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.click()
        field.typeText("moe")

        let clear = app.descendants(matching: .any)["search.clear"].firstMatch
        XCTAssertTrue(clear.waitForExistence(timeout: 10))
        // Clear from the keyboard: at the default window width search.clear sits under the
        // player rail and a mouse click doesn't reach it (#739).
        field.typeKey("a", modifierFlags: .command)
        field.typeKey(.delete, modifierFlags: [])

        // search.clear only exists while the query is non-empty. waitForExistence returns as soon
        // as it exists, so wait for it to go away instead.
        let cleared = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: clear)
        wait(for: [cleared], timeout: 10)
        XCTAssertTrue(app.descendants(matching: .any)["search.screen"].firstMatch.exists,
                      "clearing the query closed the Search screen opened from the sidebar")
    }

    func testClearButtonClearsQueryWithMouseClick() {
        // At default and minimum window widths, search.screen must fit the center pane with
        // search.clear clickable via mouse (not drawn under PlayerRailView, #739).
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COUCHTOUR_UI_TEST_MODE")
        app.launch()

        let nav = app.descendants(matching: .any)["sidebar.nav.search"].firstMatch
        XCTAssertTrue(nav.waitForExistence(timeout: 10))
        nav.click()

        let field = app.descendants(matching: .any)["search.field"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.click()
        field.typeText("moe")

        let clear = app.descendants(matching: .any)["search.clear"].firstMatch
        XCTAssertTrue(clear.waitForExistence(timeout: 10))

        // Assert search.screen fits inside the center pane:
        // Window width is sidebar (236 pt) + center pane + player rail (392 pt).
        // The center pane ends at window.frame.maxX - 392 (plus divider), so search.screen
        // and search.clear must not extend beyond the left edge of PlayerRailView.
        let window = app.windows.firstMatch
        let searchScreen = app.descendants(matching: .any)["search.screen"].firstMatch
        XCTAssertTrue(searchScreen.waitForExistence(timeout: 10))
        let railLeftEdge = window.frame.maxX - 392
        XCTAssertLessThanOrEqual(searchScreen.frame.maxX, railLeftEdge + 1,
                                 "search.screen extends under PlayerRailView: screen maxX \(searchScreen.frame.maxX) > rail left edge \(railLeftEdge)")
        XCTAssertLessThanOrEqual(clear.frame.maxX, railLeftEdge + 1,
                                 "search.clear extends under PlayerRailView: clear maxX \(clear.frame.maxX) > rail left edge \(railLeftEdge)")

        clear.click()

        // search.clear only exists while the query is non-empty. Assert it disappears after the click
        // and that the query field is cleared.
        let cleared = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: clear)
        wait(for: [cleared], timeout: 10)
        XCTAssertEqual((field.value as? String) ?? "", "", "search.field was not empty after clicking clear button")
        XCTAssertTrue(searchScreen.exists,
                      "clearing the query closed the Search screen opened from the sidebar")
    }

    func testColdStartPresentsSidebarNavHome() {
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COUCHTOUR_UI_TEST_MODE")
        app.launch()

        let homeNav = app.descendants(matching: .any)["sidebar.nav.home"].firstMatch
        XCTAssertTrue(homeNav.waitForExistence(timeout: 10), "sidebar.nav.home not present after fresh launch")
        XCTAssertTrue(homeNav.isHittable, "sidebar.nav.home not hittable after fresh launch")
    }

    func testColdStartPresentsSidebarNavSearch() {
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COUCHTOUR_UI_TEST_MODE")
        app.launch()

        let searchNav = app.descendants(matching: .any)["sidebar.nav.search"].firstMatch
        XCTAssertTrue(searchNav.waitForExistence(timeout: 10), "sidebar.nav.search not present after fresh launch")
        XCTAssertTrue(searchNav.isHittable, "sidebar.nav.search not hittable after fresh launch")
    }

    func testHomeSectionsPresentAfterRelaunch() {
        // Home renders each section's identifier even when empty, so a relaunch must expose all three.
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COUCHTOUR_UI_TEST_MODE")
        app.launch()

        let homeNav = app.descendants(matching: .any)["sidebar.nav.home"].firstMatch
        XCTAssertTrue(homeNav.waitForExistence(timeout: 10))
        homeNav.click()

        for id in ["home.in_progress", "home.next_tour_stops", "home.on_this_date"] {
            XCTAssertTrue(app.descendants(matching: .any)[id].firstMatch.waitForExistence(timeout: 15),
                          "\(id) missing after relaunch")
        }
    }

    func testNextStopChipNavigatesToShowDetailWithoutOpeningTourPicker() {
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "COUCHTOUR_UI_TEST_MODE")
        app.launchEnvironment["COUCHTOUR_UI_TEST_NEXT_STOP"] = "1"
        app.launch()

        let homeNav = app.descendants(matching: .any)["sidebar.nav.home"].firstMatch
        XCTAssertTrue(homeNav.waitForExistence(timeout: 10), "sidebar.nav.home not present after launch")
        homeNav.click()

        let chip = app.descendants(matching: .any)["home.next_tour_stops.chip.phishin.phish"].firstMatch
        XCTAssertTrue(chip.waitForExistence(timeout: 10), "Next stop chip not found in home.next_tour_stops")
        chip.click()

        let showDetail = app.descendants(matching: .any)["show.detail"].firstMatch
        if !showDetail.waitForExistence(timeout: 10) {
            print("AXDUMP-BEGIN\n\(app.debugDescription)\nAXDUMP-END")
            XCTFail("show.detail not present after clicking next stop chip")
        }

        let tourPicker = app.descendants(matching: .any)["tour_picker.sheet"].firstMatch
        XCTAssertFalse(tourPicker.exists, "tour picker sheet was presented")
        XCTAssertFalse(app.sheets.firstMatch.exists, "a sheet was presented")
    }
}
