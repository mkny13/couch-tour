import XCTest
import GRDB
@testable import CouchTourKit

@MainActor
final class MigrationTests: XCTestCase {
    private var root: URL!
    private var suite: String!
    private var defaults: UserDefaults!
    private let bundleID = "dev.mike.couchtour.mac.test"

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("migration-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(
            at: root.appendingPathComponent("Library/Preferences"), withIntermediateDirectories: true)
        suite = "migration-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: root)
    }

    private func writePlist(_ dict: [String: Any]) throws {
        let data = try PropertyListSerialization.data(fromPropertyList: dict, format: .binary, options: 0)
        try data.write(to: root.appendingPathComponent("Library/Preferences/\(bundleID).plist"))
    }

    private func sourceDB() -> URL {
        root.appendingPathComponent("Library/Application Support/dev.test/phishin.db")
    }

    private func makeStore(at url: URL) throws -> ProgressStore {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        return try ProgressStore(url: url)
    }

    private func row(_ key: String, at updatedAt: Int64, title: String = "t", deletedAt: Int64? = nil) -> PlaybackProgress {
        PlaybackProgress(
            queueKey: key, title: title, subtitle: "", trackIndex: 0, positionMs: 0,
            trackTitle: "", updatedAt: updatedAt, deletedAt: deletedAt)
    }

    private func migrate(dest: URL, sandboxed: Bool = true) -> UnsandboxedMigration.Outcome {
        UnsandboxedMigration.migrateIfNeeded(
            bundleID: bundleID, appSupportDirName: "dev.test", defaults: defaults,
            destinationDatabase: dest, realHome: root, sandboxed: sandboxed)
    }

    func testUnsandboxedProcessIsLeftAlone() throws {
        try writePlist(["favorite_artist_keys": ["phish"]])
        let dest = root.appendingPathComponent("dest/phishin.db")
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        XCTAssertEqual(migrate(dest: dest, sandboxed: false), .notSandboxed)
        XCTAssertNil(defaults.stringArray(forKey: "favorite_artist_keys"))
        XCTAssertFalse(defaults.bool(forKey: UnsandboxedMigration.doneKey))
    }

    func testSetsAreUnioned() throws {
        try writePlist([
            "favorite_artist_keys": ["phish", "moe", "grateful-dead"],
            "liked_relisten_track_ids": ["a"],
        ])
        defaults.set(["moe", "phish"], forKey: "favorite_artist_keys")
        defaults.set(["b"], forKey: "liked_relisten_track_ids")
        let dest = root.appendingPathComponent("dest/phishin.db")
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)

        XCTAssertEqual(migrate(dest: dest), .migrated)
        XCTAssertEqual(Set(defaults.stringArray(forKey: "favorite_artist_keys")!), ["phish", "moe", "grateful-dead"])
        XCTAssertEqual(Set(defaults.stringArray(forKey: "liked_relisten_track_ids")!), ["a", "b"])
        XCTAssertTrue(Favorites(defaults: defaults).keys.contains("grateful-dead"))
    }

    func testScalarSettingsFillOnlyWhereContainerHasNone() throws {
        try writePlist([
            "skip_filler_tracks": true, "level_volume": true,
            "app_theme_mode": "dark", "playerVolume": Float(0.5),
        ])
        defaults.set(false, forKey: "level_volume")
        let dest = root.appendingPathComponent("dest/phishin.db")
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)

        migrate(dest: dest)
        XCTAssertTrue(defaults.bool(forKey: "skip_filler_tracks"))
        XCTAssertFalse(defaults.bool(forKey: "level_volume"), "container's own value wins")
        XCTAssertEqual(defaults.string(forKey: "app_theme_mode"), "dark")
        XCTAssertEqual(defaults.float(forKey: "playerVolume"), 0.5)
    }

    func testSyncCursorsTakeTheSafeSide() throws {
        try writePlist([
            "sync.lastSeq": 50, "sync.lastPushWatermark": 900,
            "sync.lastFavoritesPushWatermark": 1200, "sync.lastSyncedAt": 5000,
        ])
        defaults.set(40, forKey: "sync.lastSeq")
        defaults.set(1000, forKey: "sync.lastPushWatermark")
        defaults.set(1300, forKey: "sync.lastFavoritesPushWatermark")
        defaults.set(7000, forKey: "sync.lastSyncedAt")
        let dest = root.appendingPathComponent("dest/phishin.db")
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)

        migrate(dest: dest)
        XCTAssertEqual(defaults.integer(forKey: "sync.lastSeq"), 40)
        XCTAssertEqual(defaults.integer(forKey: "sync.lastPushWatermark"), 900)
        XCTAssertEqual(defaults.integer(forKey: "sync.lastFavoritesPushWatermark"), 1200)
        XCTAssertEqual(defaults.integer(forKey: "sync.lastSyncedAt"), 7000)
    }

    func testDatabaseRowsMergeByUpdatedAt() throws {
        let src = try makeStore(at: sourceDB())
        try src.put(row("show:only-src", at: 100))
        try src.put(row("show:src-newer", at: 300, title: "src"))
        try src.put(row("show:dest-newer", at: 100, title: "src"))
        try src.put(row("show:src-tombstone", at: 400, deletedAt: 400))
        try src.saveTourPreference(artistKey: "a", tourName: "src", now: 10)
        try src.saveTaperPreference(taperName: "taper", preference: TaperPreference.avoided, now: 10)

        let destURL = root.appendingPathComponent("dest/phishin.db")
        let dest = try makeStore(at: destURL)
        try dest.put(row("show:only-dest", at: 100))
        try dest.put(row("show:src-newer", at: 200, title: "dest"))
        try dest.put(row("show:dest-newer", at: 200, title: "dest"))
        try dest.put(row("show:src-tombstone", at: 100))
        try dest.saveTourPreference(artistKey: "a", tourName: "dest", now: 20)
        try dest.saveTaperPreference(taperName: "taper", preference: TaperPreference.preferred, now: 5)

        XCTAssertEqual(migrate(dest: destURL), .migrated)

        XCTAssertNotNil(try dest.get(key: "show:only-src"))
        XCTAssertNotNil(try dest.get(key: "show:only-dest"))
        XCTAssertEqual(try dest.get(key: "show:src-newer")?.title, "src")
        XCTAssertEqual(try dest.get(key: "show:dest-newer")?.title, "dest")
        XCTAssertNil(try dest.get(key: "show:src-tombstone"))
        XCTAssertEqual(try dest.rawRow(key: "show:src-tombstone")?["deletedAt"] as Int64?, 400)
        XCTAssertEqual(try dest.getTourPreference(artistKey: "a")?.tourName, "dest")
        XCTAssertEqual(try dest.getTaperPreferences().first?.preference, TaperPreference.avoided)
        XCTAssertEqual(try dest.dbQueue.read { try PlaybackProgress.fetchCount($0) }, 5)
    }

    func testIsIdempotent() throws {
        try writePlist(["favorite_artist_keys": ["phish"]])
        let src = try makeStore(at: sourceDB())
        try src.put(row("show:a", at: 100))
        let destURL = root.appendingPathComponent("dest/phishin.db")
        let dest = try makeStore(at: destURL)

        XCTAssertEqual(migrate(dest: destURL), .migrated)
        XCTAssertTrue(defaults.bool(forKey: UnsandboxedMigration.doneKey))

        try dest.clear(key: "show:a", now: 500)
        Favorites(defaults: defaults).toggle("phish")

        XCTAssertEqual(migrate(dest: destURL), .alreadyDone)
        XCTAssertNil(try dest.get(key: "show:a"), "a second run must not resurrect deleted rows")
        XCTAssertFalse(Favorites(defaults: defaults).keys.contains("phish"))
    }

    func testNoSourceStoresStillCompletes() throws {
        let destURL = root.appendingPathComponent("dest/phishin.db")
        try FileManager.default.createDirectory(at: destURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        XCTAssertEqual(migrate(dest: destURL), .migrated)
    }
}
