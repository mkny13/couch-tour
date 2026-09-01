import XCTest
import GRDB
@testable import CouchTourKit

/// Port of Android's LocalPlaylistDaoTest.kt: CRUD against an in-memory `LocalPlaylistStore`
/// sharing a `ProgressStore`'s connection, plus `resolveLocalPlaylistTracks`'s fetch-grouping
/// and skip-what-doesn't-resolve behavior against a `MockServer`.
final class LocalPlaylistStoreTests: XCTestCase {
    private var progressStore: ProgressStore!
    private var store: LocalPlaylistStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        progressStore = try ProgressStore.inMemory()
        store = try LocalPlaylistStore(sharing: progressStore)
    }

    private func row(playlistId: String, trackId: String, showDate: String = "1997-11-17") -> LocalPlaylistTrack {
        LocalPlaylistTrack(
            playlistId: playlistId, backend: "phishin", trackId: trackId, showDate: showDate,
            title: "Track \(trackId)", durationMs: 1_000
        )
    }

    func testCreatePlaylistPersistsWithZeroTracks() throws {
        let playlist = try store.createPlaylist(name: "Road Trip", now: 1_000)
        XCTAssertEqual("Road Trip", playlist.name)
        XCTAssertEqual(0, playlist.trackCount)
        XCTAssertEqual([playlist], try store.playlists())
    }

    func testAddTrackAppendsAtTheEndAndBumpsCountAndUpdatedAt() throws {
        let playlist = try store.createPlaylist(name: "Mix", now: 1_000)
        try store.addTrack(row(playlistId: playlist.id, trackId: "1"), toPlaylist: playlist.id, now: 2_000)
        try store.addTrack(row(playlistId: playlist.id, trackId: "2"), toPlaylist: playlist.id, now: 3_000)

        let rows = try store.tracks(playlistId: playlist.id)
        XCTAssertEqual(["1", "2"], rows.map(\.trackId))
        XCTAssertEqual([0, 1], rows.map(\.position))
        XCTAssertEqual(2, try store.playlist(id: playlist.id)!.trackCount)
        XCTAssertEqual(3_000, try store.playlist(id: playlist.id)!.updatedAt)
    }

    func testRemoveTrackDropsItAndDecrementsCount() throws {
        let playlist = try store.createPlaylist(name: "Mix", now: 1_000)
        try store.addTrack(row(playlistId: playlist.id, trackId: "1"), toPlaylist: playlist.id, now: 2_000)
        let rowId = try store.tracks(playlistId: playlist.id).first!.rowId!

        try store.removeTrack(rowId: rowId, fromPlaylist: playlist.id, now: 3_000)

        XCTAssertTrue(try store.tracks(playlistId: playlist.id).isEmpty)
        XCTAssertEqual(0, try store.playlist(id: playlist.id)!.trackCount)
    }

    func testDeletingAPlaylistCascadesItsTracks() throws {
        let playlist = try store.createPlaylist(name: "Mix", now: 1_000)
        try store.addTrack(row(playlistId: playlist.id, trackId: "1"), toPlaylist: playlist.id, now: 2_000)

        try store.deletePlaylist(id: playlist.id)

        XCTAssertNil(try store.playlist(id: playlist.id))
        XCTAssertTrue(try store.tracks(playlistId: playlist.id).isEmpty)
    }

    func testPlaylistsOrderByMostRecentlyUpdatedFirst() throws {
        let a = try store.createPlaylist(name: "A", now: 1_000)
        let b = try store.createPlaylist(name: "B", now: 2_000)
        XCTAssertEqual([b.id, a.id], try store.playlists().map(\.id))
    }

    func testASecondStoreSharingTheSameProgressStoreSeesTheSameData() throws {
        // The whole reason for `sharing:` — one connection, one migrator run, both stores
        // agree on what's there.
        let playlist = try store.createPlaylist(name: "Mix", now: 1_000)
        let second = try LocalPlaylistStore(sharing: progressStore)
        XCTAssertEqual([playlist], try second.playlists())
    }

    func testRenamePlaylistUpdatesNameAndUpdatedAt() throws {
        let playlist = try store.createPlaylist(name: "Old Name", now: 1_000)
        try store.renamePlaylist(id: playlist.id, name: "New Name", now: 5_000)

        let updated = try store.playlist(id: playlist.id)!
        XCTAssertEqual("New Name", updated.name)
        XCTAssertEqual(5_000, updated.updatedAt)
    }

    func testReorderTracksUpdatesPositionsAndUpdatedAt() throws {
        let playlist = try store.createPlaylist(name: "Mix", now: 1_000)
        try store.addTrack(row(playlistId: playlist.id, trackId: "1"), toPlaylist: playlist.id, now: 2_000)
        try store.addTrack(row(playlistId: playlist.id, trackId: "2"), toPlaylist: playlist.id, now: 3_000)
        try store.addTrack(row(playlistId: playlist.id, trackId: "3"), toPlaylist: playlist.id, now: 4_000)

        let initial = try store.tracks(playlistId: playlist.id)
        let id1 = initial.first { $0.trackId == "1" }!.rowId!
        let id2 = initial.first { $0.trackId == "2" }!.rowId!
        let id3 = initial.first { $0.trackId == "3" }!.rowId!

        // Reorder to: 3, 1, 2
        try store.reorderTracks(playlistId: playlist.id, orderedRowIds: [id3, id1, id2], now: 6_000)

        let reordered = try store.tracks(playlistId: playlist.id)
        XCTAssertEqual(["3", "1", "2"], reordered.map(\.trackId))
        XCTAssertEqual([0, 1, 2], reordered.map(\.position))
        XCTAssertEqual(6_000, try store.playlist(id: playlist.id)!.updatedAt)
    }
}

final class ResolveLocalPlaylistTracksTests: XCTestCase {
    private var server: MockServer!

    override func setUp() async throws {
        try await super.setUp()
        server = MockServer()
        server.start()
        PhishInAPI.baseURL = URL(string: "https://mock.test/api/v2")!
        RelistenAPI.baseURL = URL(string: "https://mock.test/api")!
        // resolveLocalPlaylistTracks resolves shows through sourceFor(_:), which hands back
        // the process-wide RelistenCatalogSource.shared/PhishInCatalogCache.shared (#61) —
        // without a reset here, a show cached by an earlier test with the same artist/date
        // would silently serve stale data instead of hitting this test's own mock server.
        await PhishInCatalogCache.shared.resetCache()
        await RelistenCatalogSource.shared.resetCache()
    }

    override func tearDown() async throws {
        server.shutdown()
        PhishInAPI.baseURL = URL(string: "https://phish.in/api/v2")!
        RelistenAPI.baseURL = URL(string: "https://api.relisten.net/api")!
        await PhishInCatalogCache.shared.resetCache()
        await RelistenCatalogSource.shared.resetCache()
        try await super.tearDown()
    }

    func testResolvesPhishInTracksFetchingTheShowOnce() async throws {
        server.enqueue(try fixtureString("show.json"))
        let rows = [
            LocalPlaylistTrack(playlistId: "p", position: 0, backend: "phishin", trackId: "8435", showDate: "1997-11-17", title: "x", durationMs: 0),
            LocalPlaylistTrack(playlistId: "p", position: 1, backend: "phishin", trackId: "8436", showDate: "1997-11-17", title: "x", durationMs: 0),
        ]

        let resolved = await resolveLocalPlaylistTracks(rows)

        XCTAssertEqual(["Tweezer", "Reba"], resolved.map(\.title))
        XCTAssertEqual(1, server.requestCount)
    }

    func testResolvesRelistenTracksUsingTheArtistSlugAndRecordingId() async throws {
        server.enqueue(try fixtureString("relisten_show.json"))
        let rows = [
            LocalPlaylistTrack(
                playlistId: "p", position: 0, backend: "relisten", trackId: "160c100c-75a9-6568-7ef1-12aecdabcafe",
                showDate: "1977-05-08", artistSlug: "grateful-dead", title: "x", durationMs: 0
            )
        ]

        let resolved = await resolveLocalPlaylistTracks(rows)

        XCTAssertEqual(["Minglewood Blues"], resolved.map(\.title))
    }

    func testAShowThatFailsToFetchDropsJustItsOwnRowsNotTheWholePlaylist() async throws {
        // Path-routed, not FIFO — the two dates' fetches now run concurrently (D175), so
        // whichever happens to reach MockServer first must still get its own response.
        server.enqueue("", code: 500, forPathContaining: "1990-01-01")
        server.enqueue(try fixtureString("show.json"), forPathContaining: "1997-11-17")
        let rows = [
            LocalPlaylistTrack(playlistId: "p", position: 0, backend: "phishin", trackId: "missing", showDate: "1990-01-01", title: "x", durationMs: 0),
            LocalPlaylistTrack(playlistId: "p", position: 1, backend: "phishin", trackId: "8435", showDate: "1997-11-17", title: "x", durationMs: 0),
        ]

        let resolved = await resolveLocalPlaylistTracks(rows)

        XCTAssertEqual(["Tweezer"], resolved.map(\.title))
    }

    func testATrackIdThatIsNoLongerInTheFetchedShowIsSkipped() async throws {
        server.enqueue(try fixtureString("show.json"))
        let rows = [
            LocalPlaylistTrack(playlistId: "p", position: 0, backend: "phishin", trackId: "8435", showDate: "1997-11-17", title: "x", durationMs: 0),
            LocalPlaylistTrack(playlistId: "p", position: 1, backend: "phishin", trackId: "999999", showDate: "1997-11-17", title: "x", durationMs: 0),
        ]

        let resolved = await resolveLocalPlaylistTracks(rows)

        XCTAssertEqual(["Tweezer"], resolved.map(\.title))
    }

    func testPreservesStoredOrderAcrossMultipleShows() async throws {
        // Path-routed, not FIFO — both backends' fetches now run concurrently (D175) and
        // share one mock host, so enqueue order no longer matches request order.
        server.enqueue(try fixtureString("relisten_show.json"), forPathContaining: "1977-05-08")
        server.enqueue(try fixtureString("show.json"), forPathContaining: "1997-11-17")
        let rows = [
            LocalPlaylistTrack(
                playlistId: "p", position: 0, backend: "relisten", trackId: "160c100c-75a9-6568-7ef1-12aecdabcafe",
                showDate: "1977-05-08", artistSlug: "grateful-dead", title: "x", durationMs: 0
            ),
            LocalPlaylistTrack(playlistId: "p", position: 1, backend: "phishin", trackId: "8435", showDate: "1997-11-17", title: "x", durationMs: 0),
        ]

        let resolved = await resolveLocalPlaylistTracks(rows)

        XCTAssertEqual(["Minglewood Blues", "Tweezer"], resolved.map(\.title))
    }

    /// D175: distinct shows now fetch concurrently, not one at a time, so responses can land
    /// in a different order than the requests that triggered them — this pins each still
    /// resolves to its own correct show regardless. Path-routed rather than FIFO-enqueued
    /// (`forPathContaining`, added alongside this fix) is what actually proves that: a plain
    /// enqueue sequence would pass even if the two shows' tracks got swapped, as long as
    /// requests happened to arrive in enqueue order.
    func testEachShowResolvesToItsOwnTracksRegardlessOfConcurrentResponseOrder() async throws {
        server.enqueue(try fixtureString("show.json"), forPathContaining: "1997-11-17")
        server.enqueue(#"{"date":"1997-11-22","tracks":[{"id":1,"title":"Character Zero","mp3_url":"https://x/a.mp3","audio_status":"complete"}]}"#, forPathContaining: "1997-11-22")
        let rows = [
            LocalPlaylistTrack(playlistId: "p", position: 0, backend: "phishin", trackId: "8435", showDate: "1997-11-17", title: "x", durationMs: 0),
            LocalPlaylistTrack(playlistId: "p", position: 1, backend: "phishin", trackId: "1", showDate: "1997-11-22", title: "x", durationMs: 0),
        ]

        let resolved = await resolveLocalPlaylistTracks(rows)

        XCTAssertEqual(["Tweezer", "Character Zero"], resolved.map(\.title))
    }
}
