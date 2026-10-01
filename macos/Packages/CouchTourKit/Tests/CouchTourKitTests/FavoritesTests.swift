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
}
