import XCTest
@testable import CouchTourKit

final class SidebarFavoritesTests: XCTestCase {
    func testLoadingWithArtists() {
        let artists = [ArtistRef(backend: .relisten, id: "goose", name: "Goose", showCount: 412)]
        let state = sidebarFavoritesState(artists: artists, isLoaded: false)
        XCTAssertEqual(state, .loading)
    }

    func testLoadedEmpty() {
        let state = sidebarFavoritesState(artists: [], isLoaded: true)
        XCTAssertEqual(state, .empty)
    }

    func testLoadedNonEmptyOrderingAndVerbatim() {
        let artist1 = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead", showCount: 1102)
        let artist2 = ArtistRef(backend: .relisten, id: "goose", name: "Goose", showCount: 412)
        
        let state = sidebarFavoritesState(artists: [artist1, artist2], isLoaded: true)
        
        if case let .artists(sortedArtists) = state {
            XCTAssertEqual(sortedArtists.count, 2)
            XCTAssertEqual(sortedArtists[0].id, "goose")
            XCTAssertEqual(sortedArtists[1].id, "grateful-dead")
        } else {
            XCTFail("Expected .artists state")
        }
    }
}
