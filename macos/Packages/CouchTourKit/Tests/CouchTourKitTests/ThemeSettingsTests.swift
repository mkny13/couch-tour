import CouchTourKit
import XCTest

@MainActor
final class ThemeSettingsTests: XCTestCase {

    private var defaults: UserDefaults!
    /// Unique per test: `swift test --parallel` runs tests concurrently, and a fixed suite
    /// name made them clobber each other's stored values.
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "dev.mike.couchtour.ThemeSettingsTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    func testDefaultThemeModeIsAuto() {
        let settings = ThemeSettings(defaults: defaults)
        XCTAssertEqual(settings.themeMode, .auto)
    }

    func testThemeModePersistsAcrossInstances() {
        let settings = ThemeSettings(defaults: defaults)
        settings.themeMode = .light
        XCTAssertEqual(settings.themeMode, .light)

        let reloaded = ThemeSettings(defaults: defaults)
        XCTAssertEqual(reloaded.themeMode, .light)

        settings.themeMode = .dark
        XCTAssertEqual(settings.themeMode, .dark)

        let reloaded2 = ThemeSettings(defaults: defaults)
        XCTAssertEqual(reloaded2.themeMode, .dark)

        settings.themeMode = .auto
        let reloaded3 = ThemeSettings(defaults: defaults)
        XCTAssertEqual(reloaded3.themeMode, .auto)
    }

    func testThemeModeProperties() {
        XCTAssertEqual(ThemeMode.auto.title, "Auto")
        XCTAssertEqual(ThemeMode.light.title, "Light")
        XCTAssertEqual(ThemeMode.dark.title, "Dark")

        XCTAssertEqual(ThemeMode.auto.id, "auto")
        XCTAssertEqual(ThemeMode.light.id, "light")
        XCTAssertEqual(ThemeMode.dark.id, "dark")

        XCTAssertNil(ThemeMode.auto.colorScheme)
        XCTAssertEqual(ThemeMode.light.colorScheme, .light)
        XCTAssertEqual(ThemeMode.dark.colorScheme, .dark)
    }
}
