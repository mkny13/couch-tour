import XCTest
import GRDB
@testable import CouchTourKit

/// Port of ProgressDaoTest.kt against an in-memory `ProgressStore`, pinning the same query
/// semantics Android's `ProgressDao` guarantees so the two clients' notions of "continue
/// listening" and "history" never diverge.
final class ProgressStoreTests: XCTestCase {

    private var store: ProgressStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        store = try ProgressStore.inMemory()
    }

    private func progress(
        key: String,
        trackIndex: Int = 0,
        positionMs: Int64 = 0,
        finished: Bool = false,
        dismissed: Bool = false,
        updatedAt: Int64 = 1_000,
        trackTitle: String = "Track",
        artist: String = "Phish"
    ) -> PlaybackProgress {
        PlaybackProgress(
            queueKey: key, title: key, subtitle: "subtitle", artUrl: nil, trackIndex: trackIndex,
            positionMs: positionMs, trackTitle: trackTitle, updatedAt: updatedAt,
            finished: finished, dismissed: dismissed, artist: artist
        )
    }

    func testStoresAndReadsBackARow() throws {
        try store.put(progress(key: "show:1997-02-13", trackIndex: 5, positionMs: 35_342))

        let row = try store.get(key: "show:1997-02-13")!
        XCTAssertEqual(5, row.trackIndex)
        XCTAssertEqual(35_342, row.positionMs)
        XCTAssertFalse(row.finished)
    }

    func testReturnsNilForAQueueNeverPlayed() throws {
        XCTAssertNil(try store.get(key: "show:1970-01-01"))
    }

    func testReplacesRatherThanDuplicatingOnTheSameKey() throws {
        try store.put(progress(key: "show:1997-02-13", positionMs: 1_000))
        try store.put(progress(key: "show:1997-02-13", positionMs: 2_000))

        XCTAssertEqual(1, try store.inProgress().count)
        XCTAssertEqual(2_000, try store.get(key: "show:1997-02-13")!.positionMs)
    }

    func testContinueListeningExcludesFinishedQueues() throws {
        try store.put(progress(key: "show:1997-02-13", finished: false))
        try store.put(progress(key: "show:1992-12-02", finished: true))

        XCTAssertEqual(["show:1997-02-13"], try store.inProgress().map { $0.queueKey })
    }

    func testHistoryContainsFinishedAndUnfinishedAlike() throws {
        try store.put(progress(key: "show:1997-02-13", finished: false, updatedAt: 200))
        try store.put(progress(key: "show:1992-12-02", finished: true, updatedAt: 100))

        XCTAssertEqual(
            ["show:1997-02-13", "show:1992-12-02"],
            try store.history().map { $0.queueKey }
        )
    }

    func testDismissingHidesFromContinueListeningButKeepsItInHistory() throws {
        try store.put(progress(key: "show:1997-02-13"))

        try store.dismiss(key: "show:1997-02-13")

        XCTAssertEqual(0, try store.inProgress().count)
        XCTAssertEqual(1, try store.history().count)
        XCTAssertTrue(try store.get(key: "show:1997-02-13")!.dismissed)
    }

    func testDismissingPreservesTheStoredPosition() throws {
        try store.put(progress(key: "show:1997-02-13", trackIndex: 5, positionMs: 35_342))

        try store.dismiss(key: "show:1997-02-13")

        let row = try store.get(key: "show:1997-02-13")!
        XCTAssertEqual(5, row.trackIndex)
        XCTAssertEqual(35_342, row.positionMs)
    }

    func testPlayingADismissedQueueAgainBringsItBack() throws {
        try store.put(progress(key: "show:1997-02-13"))
        try store.dismiss(key: "show:1997-02-13")

        // Saving during playback writes dismissed = false, the same way finished clears.
        try store.put(progress(key: "show:1997-02-13", positionMs: 5_000))

        XCTAssertEqual(1, try store.inProgress().count)
        XCTAssertFalse(try store.get(key: "show:1997-02-13")!.dismissed)
    }

    func testAFinishedQueueStaysOutOfContinueListeningEvenWhenNotDismissed() throws {
        try store.put(progress(key: "show:1992-12-02", finished: true, dismissed: false))

        XCTAssertEqual(0, try store.inProgress().count)
        XCTAssertEqual(1, try store.history().count)
    }

    func testMarkingCompletedMovesItOutOfContinueListening() throws {
        try store.put(progress(key: "show:1997-02-13", trackIndex: 5, positionMs: 35_342))

        try store.markFinished(key: "show:1997-02-13")

        XCTAssertEqual(0, try store.inProgress().count)
        XCTAssertTrue(try store.get(key: "show:1997-02-13")!.finished)
        XCTAssertEqual(1, try store.history().count)
    }

    func testMarkingCompletedLeavesThePositionIntact() throws {
        try store.put(progress(key: "show:1997-02-13", trackIndex: 5, positionMs: 35_342))

        try store.markFinished(key: "show:1997-02-13")

        // Playing it again restarts from the top, but the row itself is not rewritten.
        let row = try store.get(key: "show:1997-02-13")!
        XCTAssertEqual(5, row.trackIndex)
        XCTAssertEqual(35_342, row.positionMs)
    }

    func testHistoryCountCoversEveryState() throws {
        try store.put(progress(key: "show:a"))
        try store.put(progress(key: "show:b", finished: true))
        try store.put(progress(key: "show:c", dismissed: true))

        XCTAssertEqual(3, try store.historyCount())
        XCTAssertEqual(1, try store.inProgress().count)
    }

    func testContinueListeningIsNewestFirst() throws {
        try store.put(progress(key: "show:a", updatedAt: 100))
        try store.put(progress(key: "show:c", updatedAt: 300))
        try store.put(progress(key: "show:b", updatedAt: 200))

        XCTAssertEqual(
            ["show:c", "show:b", "show:a"],
            try store.inProgress().map { $0.queueKey }
        )
    }

    func testHistoryIsNewestFirst() throws {
        try store.put(progress(key: "show:a", finished: true, updatedAt: 100))
        try store.put(progress(key: "show:b", finished: true, updatedAt: 200))

        XCTAssertEqual(["show:b", "show:a"], try store.history().map { $0.queueKey })
    }

    func testReplayingAFinishedQueueReturnsItToContinueListening() throws {
        try store.put(progress(key: "show:1992-12-02", finished: true))
        XCTAssertEqual(0, try store.inProgress().count)

        try store.put(progress(key: "show:1992-12-02", finished: false))

        XCTAssertEqual(1, try store.inProgress().count)
        XCTAssertFalse(try store.get(key: "show:1992-12-02")!.finished)
    }

    func testClearingForgetsAQueueEntirely() throws {
        try store.put(progress(key: "show:1997-02-13"))

        try store.clear(key: "show:1997-02-13", now: 5_000)

        // Unlike dismissing, this leaves no trace in history either — from the store's
        // perspective. The row itself survives as a tombstone; see the deletedAt tests below.
        XCTAssertNil(try store.get(key: "show:1997-02-13"))
        XCTAssertEqual(0, try store.inProgress().count)
        XCTAssertEqual(0, try store.history().count)
        XCTAssertEqual(0, try store.historyCount())
        XCTAssertEqual([], try store.artists())
        XCTAssertEqual([], try store.historyFor(artist: "Phish").map { $0.queueKey })
    }

    func testClearingAnAbsentKeyIsANoOp() throws {
        try store.put(progress(key: "show:1997-02-13"))

        try store.clear(key: "show:nonexistent", now: 5_000)

        XCTAssertEqual(1, try store.inProgress().count)
    }

    func testClearingStampsATombstoneRatherThanDeletingTheRow() throws {
        try store.put(progress(key: "show:1997-02-13", trackIndex: 5, positionMs: 35_342))

        try store.clear(key: "show:1997-02-13", now: 9_999)

        // Bypass the store's own deletedAt filter to see the raw row underneath.
        let row = try store.rawRow(key: "show:1997-02-13")!
        XCTAssertEqual(9_999, row["deletedAt"] as Int64?)
        XCTAssertEqual(9_999, row["updatedAt"] as Int64?)
        // The position it was cleared at survives, even though nothing reads it.
        XCTAssertEqual(5, row["trackIndex"] as Int64?)
    }

    func testPlayingAClearedQueueAgainBringsItBack() throws {
        try store.put(progress(key: "show:1997-02-13"))
        try store.clear(key: "show:1997-02-13", now: 5_000)
        XCTAssertNil(try store.get(key: "show:1997-02-13"))

        // put() replaces the whole row, including deletedAt back to nil — the same
        // un-delete-by-replaying semantic dismissed already has.
        try store.put(progress(key: "show:1997-02-13", positionMs: 8_000))

        XCTAssertEqual(1, try store.inProgress().count)
        let row = try store.get(key: "show:1997-02-13")!
        XCTAssertNil(row.deletedAt)
        XCTAssertEqual(8_000, row.positionMs)
    }

    func testShowsAndPlaylistsCoexistUnderOneTable() throws {
        try store.put(progress(key: showQueueKey("1997-02-13"), updatedAt: 100))
        try store.put(progress(key: playlistQueueKey("key-jams"), updatedAt: 200))

        let keys = try store.inProgress().map { $0.queueKey }
        XCTAssertEqual(["playlist:key-jams", "show:1997-02-13"], keys)
        XCTAssertEqual(QueueKind.playlist, parseQueueKey(keys[0])!.kind)
        XCTAssertEqual(QueueKind.show, parseQueueKey(keys[1])!.kind)
    }

    func testContinueListeningIsCappedSoTheRowCannotGrowWithoutBound() throws {
        for i in 0..<30 { try store.put(progress(key: "show:\(i)", updatedAt: Int64(i))) }

        XCTAssertEqual(25, try store.inProgress().count)
    }

    // ---------------------------------------------------------------- artists

    func testListsTheArtistsInHistoryDistinctAndSorted() throws {
        try store.put(progress(key: "show:1997-02-13", artist: "Phish"))
        try store.put(progress(key: "relisten:grateful-dead/1977-05-08/a", artist: "Grateful Dead"))
        try store.put(progress(key: "relisten:grateful-dead/1972-08-27/b", artist: "Grateful Dead"))
        try store.put(progress(key: "relisten:wsp/2001-04-22/c", artist: "Widespread Panic"))

        XCTAssertEqual(["Grateful Dead", "Phish", "Widespread Panic"], try store.artists())
    }

    func testFiltersHistoryToOneArtistNewestFirst() throws {
        try store.put(progress(key: "relisten:grateful-dead/1972-08-27/b", updatedAt: 100, artist: "Grateful Dead"))
        try store.put(progress(key: "show:1997-02-13", updatedAt: 200, artist: "Phish"))
        try store.put(progress(key: "relisten:grateful-dead/1977-05-08/a", updatedAt: 300, artist: "Grateful Dead"))

        XCTAssertEqual(
            ["relisten:grateful-dead/1977-05-08/a", "relisten:grateful-dead/1972-08-27/b"],
            try store.historyFor(artist: "Grateful Dead").map { $0.queueKey }
        )
    }

    func testARowWithNoArtistIsLeftOutOfTheArtistList() throws {
        // Nothing writes an empty artist today, but a row could predate a future backfill on
        // a database restored from somewhere unexpected. An empty heading is worse than none.
        try store.put(progress(key: "show:1997-02-13", artist: ""))
        try store.put(progress(key: "show:1998-11-02", artist: "Phish"))

        XCTAssertEqual(["Phish"], try store.artists())
    }

    // ------------------------------------------------------------ historyArtists

    func testHistoryArtistsOrdersByLastPlayedNotAlphabetically() throws {
        // Deliberately the opposite of artists()'s alphabetical order — Grateful Dead would
        // sort first alphabetically but played least recently here.
        try store.put(progress(key: "show:1997-02-13", updatedAt: 100, artist: "Grateful Dead"))
        try store.put(progress(key: "show:1998-11-02", updatedAt: 200, artist: "Phish"))
        try store.put(progress(key: "show:1999-06-01", updatedAt: 300, artist: "Widespread Panic"))

        XCTAssertEqual(
            ["Widespread Panic", "Phish", "Grateful Dead"],
            historyArtists(try store.history())
        )
    }

    func testHistoryArtistsDedupesAtTheArtistsMostRecentAppearance() throws {
        try store.put(progress(key: "show:1972-08-27", updatedAt: 100, artist: "Grateful Dead"))
        try store.put(progress(key: "show:1997-02-13", updatedAt: 200, artist: "Phish"))
        try store.put(progress(key: "show:1977-05-08", updatedAt: 300, artist: "Grateful Dead"))

        XCTAssertEqual(["Grateful Dead", "Phish"], historyArtists(try store.history()))
    }

    func testHistoryArtistsSkipsBlankArtistsButTheRowStaysInHistory() throws {
        try store.put(progress(key: "show:1997-02-13", artist: ""))
        try store.put(progress(key: "show:1998-11-02", artist: "Phish"))

        XCTAssertEqual(["Phish"], historyArtists(try store.history()))
        XCTAssertEqual(2, try store.history().count)
    }

    func testHistoryArtistsOfAnEmptyHistoryIsEmpty() throws {
        XCTAssertEqual([], historyArtists(try store.history()))
    }

    // ------------------------------------------------ MARK: - Artist Tour Preferences (#68)

    func testSaveAndGetTourPreference() throws {
        let pref = ArtistTourPreference(
            artistKey: "relisten:grateful-dead",
            tourName: "Spring 1977",
            year: "1977",
            updatedAt: 12_345
        )
        try store.saveTourPreference(pref)

        let retrieved = try store.getTourPreference(artistKey: "relisten:grateful-dead")
        XCTAssertNotNil(retrieved)
        XCTAssertEqual("relisten:grateful-dead", retrieved?.artistKey)
        XCTAssertEqual("Spring 1977", retrieved?.tourName)
        XCTAssertEqual("1977", retrieved?.year)
        XCTAssertEqual(12_345, retrieved?.updatedAt)
    }

    func testSaveTourPreferenceConvenienceMethod() throws {
        try store.saveTourPreference(
            artistKey: "relisten:jgb",
            tourName: "Fall 1989",
            year: "1989",
            now: 54_321
        )

        let retrieved = try store.getTourPreference(artistKey: "relisten:jgb")
        XCTAssertNotNil(retrieved)
        XCTAssertEqual("relisten:jgb", retrieved?.artistKey)
        XCTAssertEqual("Fall 1989", retrieved?.tourName)
        XCTAssertEqual("1989", retrieved?.year)
        XCTAssertEqual(54_321, retrieved?.updatedAt)
    }

    func testUpdateTourPreferenceOverwritesPrevious() throws {
        let pref1 = ArtistTourPreference(
            artistKey: "relisten:grateful-dead",
            tourName: "Spring 1977",
            year: "1977",
            updatedAt: 1_000
        )
        try store.saveTourPreference(pref1)

        let pref2 = ArtistTourPreference(
            artistKey: "relisten:grateful-dead",
            tourName: "Europe '72",
            year: "1972",
            updatedAt: 2_000
        )
        try store.saveTourPreference(pref2)

        let retrieved = try store.getTourPreference(artistKey: "relisten:grateful-dead")
        XCTAssertNotNil(retrieved)
        XCTAssertEqual("Europe '72", retrieved?.tourName)
        XCTAssertEqual("1972", retrieved?.year)
        XCTAssertEqual(2_000, retrieved?.updatedAt)

        let all = try store.getAllTourPreferences()
        XCTAssertEqual(1, all.count)
    }

    func testGetAllTourPreferencesOrderedByUpdatedAtDesc() throws {
        try store.saveTourPreference(ArtistTourPreference(artistKey: "artist:a", tourName: "Tour A", year: "1991", updatedAt: 100))
        try store.saveTourPreference(ArtistTourPreference(artistKey: "artist:b", tourName: "Tour B", year: "1992", updatedAt: 300))
        try store.saveTourPreference(ArtistTourPreference(artistKey: "artist:c", tourName: "Tour C", year: "1993", updatedAt: 200))

        let all = try store.getAllTourPreferences()
        XCTAssertEqual(["artist:b", "artist:c", "artist:a"], all.map { $0.artistKey })
    }

    func testDeleteTourPreference() throws {
        let pref = ArtistTourPreference(artistKey: "relisten:grateful-dead", tourName: "1977", year: "1977")
        try store.saveTourPreference(pref)
        XCTAssertNotNil(try store.getTourPreference(artistKey: "relisten:grateful-dead"))

        try store.deleteTourPreference(artistKey: "relisten:grateful-dead")
        XCTAssertNil(try store.getTourPreference(artistKey: "relisten:grateful-dead"))
    }

    func testDeleteNonExistentTourPreferenceIsNoOp() throws {
        try store.saveTourPreference(ArtistTourPreference(artistKey: "artist:a", tourName: "Tour A", year: "1991"))
        XCTAssertNoThrow(try store.deleteTourPreference(artistKey: "artist:nonexistent"))
        XCTAssertEqual(1, try store.getAllTourPreferences().count)
    }

    func testTrackedTourStoreSharingProgressStore() throws {
        let trackedStore = try TrackedTourStore(sharing: store)

        let pref = ArtistTourPreference(artistKey: "relisten:wsp", tourName: "Spring 1996", year: "1996", updatedAt: 9_999)
        try trackedStore.savePreference(pref)

        let fromProgressStore = try store.getTourPreference(artistKey: "relisten:wsp")
        let fromTrackedStore = try trackedStore.preference(for: "relisten:wsp")

        XCTAssertEqual(pref, fromProgressStore)
        XCTAssertEqual(pref, fromTrackedStore)

        let all = try trackedStore.allPreferences()
        XCTAssertEqual(1, all.count)
        XCTAssertEqual("relisten:wsp", all.first?.artistKey)

        try trackedStore.deletePreference(for: "relisten:wsp")
        XCTAssertNil(try store.getTourPreference(artistKey: "relisten:wsp"))
        XCTAssertNil(try trackedStore.preference(for: "relisten:wsp"))
    }

    func testV9MigrationPreservesExistingData() throws {
        let queue = try DatabaseQueue()

        var partialMigrator = DatabaseMigrator()
        partialMigrator.registerMigration("v6_initial") { db in
            try db.create(table: "progress") { t in
                t.column("queueKey", .text).primaryKey()
                t.column("title", .text).notNull()
                t.column("subtitle", .text).notNull()
                t.column("artUrl", .text)
                t.column("trackIndex", .integer).notNull()
                t.column("positionMs", .integer).notNull()
                t.column("trackTitle", .text).notNull()
                t.column("updatedAt", .integer).notNull()
                t.column("finished", .boolean).notNull().defaults(to: false)
                t.column("dismissed", .boolean).notNull().defaults(to: false)
                t.column("artist", .text).notNull().defaults(to: "")
            }
        }
        partialMigrator.registerMigration("v7_deletedAt") { db in
            try db.alter(table: "progress") { t in
                t.add(column: "deletedAt", .integer)
            }
        }
        try partialMigrator.migrate(queue)

        // Insert pre-migration row
        try queue.write { db in
            let prog = self.progress(key: "show:1997-11-17", trackIndex: 3, positionMs: 12_000)
            try prog.save(db)
        }

        // Now run full migrator including v9_artistTourPreferences
        var fullMigrator = partialMigrator
        fullMigrator.registerMigration("v9_artistTourPreferences") { db in
            try db.create(table: "artist_tour_preferences") { t in
                t.column("artistKey", .text).primaryKey()
                t.column("tourName", .text)
                t.column("year", .text)
                t.column("updatedAt", .integer).notNull()
            }
        }
        try fullMigrator.migrate(queue)

        // Verify pre-existing data is intact
        try queue.read { db in
            let prog = try PlaybackProgress.fetchOne(db, key: "show:1997-11-17")
            XCTAssertNotNil(prog)
            XCTAssertEqual(3, prog?.trackIndex)
            XCTAssertEqual(12_000, prog?.positionMs)
        }

        // Verify artist_tour_preferences table works on migrated database
        try queue.write { db in
            let pref = ArtistTourPreference(artistKey: "relisten:grateful-dead", tourName: "Spring 1977", year: "1977")
            try pref.save(db)
        }

        try queue.read { db in
            let pref = try ArtistTourPreference.fetchOne(db, key: "relisten:grateful-dead")
            XCTAssertNotNil(pref)
            XCTAssertEqual("Spring 1977", pref?.tourName)
        }
    }
}
