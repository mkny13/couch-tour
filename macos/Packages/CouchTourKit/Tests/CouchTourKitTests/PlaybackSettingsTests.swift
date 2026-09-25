import Combine
import CouchTourKit
import XCTest

@MainActor
final class PlaybackSettingsTests: XCTestCase {

    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: "dev.mike.couchtour.PlaybackSettingsTests")!
        defaults.removePersistentDomain(forName: "dev.mike.couchtour.PlaybackSettingsTests")
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: "dev.mike.couchtour.PlaybackSettingsTests")
        defaults = nil
        super.tearDown()
    }

    func testDefaultsAreFalse() {
        let settings = PlaybackSettings(defaults: defaults)
        XCTAssertFalse(settings.skipFiller)
        XCTAssertFalse(settings.levelVolume)
    }

    func testLevelVolumePersistsAcrossInstances() {
        let settings = PlaybackSettings(defaults: defaults)
        settings.levelVolume = true
        XCTAssertTrue(settings.levelVolume)

        let reloaded = PlaybackSettings(defaults: defaults)
        XCTAssertTrue(reloaded.levelVolume)

        settings.levelVolume = false
        let reloaded2 = PlaybackSettings(defaults: defaults)
        XCTAssertFalse(reloaded2.levelVolume)
    }

    func testSkipFillerPersistsAcrossInstances() {
        let settings = PlaybackSettings(defaults: defaults)
        settings.skipFiller = true
        XCTAssertTrue(settings.skipFiller)

        let reloaded = PlaybackSettings(defaults: defaults)
        XCTAssertTrue(reloaded.skipFiller)
    }

    func testClearMeasuredLoudnessEmitsEvent() {
        let settings = PlaybackSettings(defaults: defaults)
        let expectation = expectation(description: "clearCacheSubject received event")

        var cancellables = Set<AnyCancellable>()
        settings.clearCacheSubject.sink {
            expectation.fulfill()
        }.store(in: &cancellables)

        settings.clearMeasuredLoudness()

        wait(for: [expectation], timeout: 1.0)
    }
}
