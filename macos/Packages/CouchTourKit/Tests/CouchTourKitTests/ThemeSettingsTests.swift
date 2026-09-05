import CouchTourKit
import XCTest

@MainActor
final class ThemeSettingsTests: XCTestCase {

    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: "dev.mike.couchtour.ThemeSettingsTests")!
        defaults.removePersistentDomain(forName: "dev.mike.couchtour.ThemeSettingsTests")
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: "dev.mike.couchtour.ThemeSettingsTests")
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
