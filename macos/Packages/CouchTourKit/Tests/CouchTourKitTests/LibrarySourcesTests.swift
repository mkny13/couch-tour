import XCTest
@testable import CouchTourKit

final class LibrarySourcesTests: XCTestCase {
    private func playlist(_ id: String, _ name: String, count: Int = 2, updated: Int64) -> LocalPlaylist {
        LocalPlaylist(id: id, name: name, trackCount: count, createdAt: 0, updatedAt: updated)
    }

    private func record(_ id: String, title: String = "Tweezer", likedAt: Int64? = nil) -> LikedTrackRecord {
        LikedTrackRecord(
            trackId: id, backend: "relisten", showDate: "1997-11-22", artistSlug: "phish",
            title: title, durationMs: 1000, venueName: "Hampton", likedAt: likedAt
        )
    }

    func testPlaylistsMapWithCountSubtitle() {
        let items = LibrarySources.items(
            playlists: [playlist("a", "Jams", count: 1, updated: 5), playlist("b", "Ballads", updated: 9)],
            playlistTracks: [], likedTracks: []
        )
        XCTAssertEqual(["Jams", "Ballads"], items.map(\.name))
        XCTAssertEqual(["1 track", "2 tracks"], items.map(\.subtitle))
        XCTAssertTrue(items.allSatisfy { $0.kind == .playlist })
    }

    func testPlaylistTracksMapAsTrackRows() {
        let row = LocalPlaylistTrack(
            rowId: 3, playlistId: "a", backend: "phishin", trackId: "9", showDate: "2000-01-01",
            title: "Divided Sky", durationMs: 1, venueName: "MSG"
        )
        let items = LibrarySources.items(playlists: [], playlistTracks: [row], likedTracks: [])
        XCTAssertEqual(1, items.count)
        XCTAssertEqual(.track, items[0].kind)
        XCTAssertEqual("2000-01-01 · MSG", items[0].subtitle)
        XCTAssertEqual("phishin", items[0].artist)
    }

    func testLikedRecordsBecomeTrackRowsAndMetadataLessOnesAreOmitted() {
        let bare = LikedTrackRecord(trackId: "uuid-123", backend: "relisten", showDate: "", title: "", durationMs: 0)
        let items = LibrarySources.items(playlists: [], playlistTracks: [], likedTracks: [record("t1"), bare])
        XCTAssertEqual(["Tweezer"], items.map(\.name))
        XCTAssertFalse(items.contains { $0.name.contains("uuid-123") || $0.subtitle.contains("uuid-123") })
        XCTAssertEqual("t1", items[0].likedTrack?.trackId)
    }

    func testRecentlyAddedSortsUndatedLastAndIsStable() {
        let items = LibrarySources.items(
            playlists: [playlist("a", "Old", updated: 1), playlist("b", "New", updated: 9)],
            playlistTracks: [
                LocalPlaylistTrack(rowId: 1, playlistId: "a", backend: "phishin", trackId: "1", showDate: "d", title: "U1", durationMs: 0),
                LocalPlaylistTrack(rowId: 2, playlistId: "a", backend: "phishin", trackId: "2", showDate: "d", title: "U2", durationMs: 0),
            ],
            likedTracks: [record("t1", title: "Liked", likedAt: 5)]
        )
        let sorted = LibrarySources.sorted(items, by: .recentlyAdded)
        XCTAssertEqual(["New", "Liked", "Old", "U1", "U2"], sorted.map(\.name))
    }

    func testTitleAndArtistSorts() {
        let items = LibrarySources.items(
            playlists: [playlist("a", "zebra", updated: 1), playlist("b", "Apple", updated: 2)],
            playlistTracks: [], likedTracks: []
        )
        XCTAssertEqual(["Apple", "zebra"], LibrarySources.sorted(items, by: .title).map(\.name))
        XCTAssertEqual(["zebra", "Apple"], LibrarySources.sorted(items, by: .artist).map(\.name))
    }
}
