import XCTest
@testable import CouchTourKit

/// Port of Android's FavoritesTest — toggle/persist round trip on an isolated defaults suite
/// so it never touches the real `UserDefaults.standard`.
@MainActor
final class FavoritesTests: XCTestCase {
    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "FavoritesTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suiteName) }
        return defaults
    }

    func testTogglingAKeyAddsIt() {
        let favorites = Favorites(defaults: isolatedDefaults())
        favorites.toggle("relisten:grateful-dead")
        XCTAssertEqual(["relisten:grateful-dead"], favorites.keys)
    }

    func testTogglingAnAlreadyFavoritedKeyRemovesIt() {
        let favorites = Favorites(defaults: isolatedDefaults())
        favorites.toggle("relisten:grateful-dead")
        favorites.toggle("relisten:grateful-dead")
        XCTAssertTrue(favorites.keys.isEmpty)
    }

    func testFavoritesPersistAcrossInstancesSharingTheSameDefaults() {
        let defaults = isolatedDefaults()
        Favorites(defaults: defaults).toggle("relisten:wsp")
        XCTAssertEqual(["relisten:wsp"], Favorites(defaults: defaults).keys)
    }

    func testChangedSinceIncludesTombstonesAndApplyFromSyncUsesLww() {
        let defaults = isolatedDefaults()
        let favorites = Favorites(defaults: defaults)
        favorites.toggle("phish")
        let first = favorites.changedSince(0).first!
        XCTAssertNil(first.deletedAt)

        favorites.toggle("phish")
        let tombstone = favorites.changedSince(first.updatedAt).first!
        XCTAssertNotNil(tombstone.deletedAt)

        _ = favorites.applyFromSync([FavoriteArtistSyncRow(artistKey: "phish", updatedAt: tombstone.updatedAt - 1, deletedAt: nil)])
        XCTAssertTrue(favorites.keys.isEmpty)

        _ = favorites.applyFromSync([FavoriteArtistSyncRow(artistKey: "phish", updatedAt: tombstone.updatedAt + 1, deletedAt: nil)])
        XCTAssertEqual(Set(["phish"]), favorites.keys)
    }

    func testLegacyMigrationPersistsRowsJsonAndPreservesTimestampsAcrossInit() {
        let defaults = isolatedDefaults()
        defaults.set(["relisten:grateful-dead"], forKey: "favorite_artist_keys")

        let favorites1 = Favorites(defaults: defaults)
        XCTAssertEqual(["relisten:grateful-dead"], favorites1.keys)
        let initialRows = favorites1.changedSince(0)
        XCTAssertEqual(1, initialRows.count)
        let initialTimestamp = initialRows[0].updatedAt

        let rawJson = defaults.string(forKey: "favorite_artist_rows_json")
        XCTAssertNotNil(rawJson)

        // Subsequent init should preserve original timestamp and not re-mint fresh timestamp
        let favorites2 = Favorites(defaults: defaults)
        let afterReinit = favorites2.changedSince(0)
        XCTAssertEqual(1, afterReinit.count)
        XCTAssertEqual(initialTimestamp, afterReinit[0].updatedAt)
    }

    func testLegacyMigrationDoesNotReviveTombstonesOnSubsequentInit() {
        let defaults = isolatedDefaults()
        defaults.set(["relisten:grateful-dead"], forKey: "favorite_artist_keys")

        let favorites1 = Favorites(defaults: defaults)
        let initialTimestamp = favorites1.changedSince(0)[0].updatedAt

        let tombstoneTime = initialTimestamp + 1_000
        _ = favorites1.applyFromSync([
            FavoriteArtistSyncRow(artistKey: "relisten:grateful-dead", updatedAt: tombstoneTime, deletedAt: tombstoneTime)
        ])
        XCTAssertTrue(favorites1.keys.isEmpty)

        // Subsequent init must not revive from legacy keys
        let favorites2 = Favorites(defaults: defaults)
        XCTAssertTrue(favorites2.keys.isEmpty)
        let rows = favorites2.changedSince(0)
        XCTAssertEqual(1, rows.count)
        XCTAssertEqual(tombstoneTime, rows[0].updatedAt)
        XCTAssertNotNil(rows[0].deletedAt)
    }
}
