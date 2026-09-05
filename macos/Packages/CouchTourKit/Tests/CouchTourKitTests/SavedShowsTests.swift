import XCTest
@testable import CouchTourKit

@MainActor
final class SavedShowsTests: XCTestCase {
    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "SavedShowsTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suiteName) }
        return defaults
    }

    func testTogglingAKeyAddsIt() {
        let savedShows = SavedShows(defaults: isolatedDefaults())
        savedShows.toggle("1997-11-17")
        XCTAssertTrue(savedShows.contains("1997-11-17"))
        XCTAssertEqual(["1997-11-17"], savedShows.keys)
    }

    func testTogglingAnAlreadySavedKeyRemovesIt() {
        let savedShows = SavedShows(defaults: isolatedDefaults())
        savedShows.toggle("1997-11-17")
        savedShows.toggle("1997-11-17")
        XCTAssertFalse(savedShows.contains("1997-11-17"))
        XCTAssertTrue(savedShows.keys.isEmpty)
    }

    func testSavedShowsPersistAcrossInstancesSharingTheSameDefaults() {
        let defaults = isolatedDefaults()
        SavedShows(defaults: defaults).toggle("1994-06-18")
        let reloaded = SavedShows(defaults: defaults)
        XCTAssertTrue(reloaded.contains("1994-06-18"))
        XCTAssertEqual(["1994-06-18"], reloaded.keys)
    }
}
