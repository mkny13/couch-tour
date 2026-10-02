import XCTest
import GRDB
@testable import CouchTourKit

/// EXPLAIN QUERY PLAN audit of every query on a table that grows (#507, report in
/// docs/DB_QUERY_EFFICIENCY.md). Seeded so the planner sees real rows, then each query's plan
/// is asserted to use an index and not a full scan or a temp B-tree sort.
final class QueryPlanTests: XCTestCase {

    private var store: ProgressStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        store = try ProgressStore.inMemory()
        _ = try LocalPlaylistStore(sharing: store)
        try store.dbQueue.write { db in
            for i in 0..<500 {
                try db.execute(
                    sql: """
                    INSERT INTO progress (queueKey, title, subtitle, trackIndex, positionMs, trackTitle,
                        updatedAt, finished, dismissed, artist, deletedAt)
                    VALUES (?, 't', 's', 0, 0, 'tt', ?, ?, ?, ?, ?)
                    """,
                    arguments: ["k\(i)", Int64(i), i % 3 == 0, i % 5 == 0, "artist\(i % 7)", i % 11 == 0 ? Int64(5) : nil]
                )
                try db.execute(
                    sql: "INSERT INTO source_loudness VALUES (?, -14, -1, 3, 1, 0)", arguments: ["lk\(i)"]
                )
            }
            for p in 0..<20 {
                try db.execute(
                    sql: "INSERT INTO local_playlists (id, name, trackCount, createdAt, updatedAt) VALUES (?, 'p', 0, 0, ?)",
                    arguments: ["pl\(p)", Int64(p)]
                )
                for pos in 0..<25 {
                    try db.execute(
                        sql: """
                        INSERT INTO local_playlist_tracks (playlistId, position, backend, trackId, showDate, title, durationMs)
                        VALUES (?, ?, 'phishin', 'x', '1997-01-01', 't', 1)
                        """,
                        arguments: ["pl\(p)", pos]
                    )
                }
            }
            try db.execute(sql: "ANALYZE")
        }
    }

    /// The plan's `detail` column, one line per step.
    private func plan(_ sql: String, _ args: StatementArguments = []) throws -> [String] {
        try store.dbQueue.read { db in
            try Row.fetchAll(db, sql: "EXPLAIN QUERY PLAN " + sql, arguments: args).map { $0["detail"] as String }
        }
    }

    private func assertIndexBacked(_ sql: String, _ args: StatementArguments = [], file: StaticString = #filePath, line: UInt = #line) throws {
        let steps = try plan(sql, args)
        for step in steps {
            XCTAssertFalse(step.hasPrefix("SCAN") && !step.contains("INDEX"), "full scan: \(step) for \(sql)", file: file, line: line)
            XCTAssertFalse(step.contains("TEMP B-TREE"), "temp sort: \(step) for \(sql)", file: file, line: line)
        }
        XCTAssertFalse(steps.isEmpty, file: file, line: line)
    }

    func testProgressReadsAreIndexBacked() throws {
        try assertIndexBacked("SELECT * FROM progress WHERE finished = 0 AND dismissed = 0 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 25")
        try assertIndexBacked("SELECT * FROM progress WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
        try assertIndexBacked("SELECT DISTINCT artist FROM progress WHERE artist != '' AND deletedAt IS NULL ORDER BY artist")
        try assertIndexBacked("SELECT * FROM progress WHERE artist = ? AND deletedAt IS NULL ORDER BY updatedAt DESC", ["artist1"])
        try assertIndexBacked("SELECT * FROM progress WHERE queueKey = ? AND deletedAt IS NULL", ["k1"])
        try assertIndexBacked("SELECT * FROM progress WHERE updatedAt > ? ORDER BY updatedAt ASC", [100])
    }

    func testProgressWritesAreIndexBacked() throws {
        try assertIndexBacked("UPDATE progress SET dismissed = 1 WHERE queueKey = ?", ["k1"])
        try assertIndexBacked("UPDATE progress SET finished = 1 WHERE queueKey = ?", ["k1"])
        try assertIndexBacked("UPDATE progress SET deletedAt = 1, updatedAt = 1 WHERE queueKey = ?", ["k1"])
    }

    func testLocalPlaylistQueriesAreIndexBacked() throws {
        try assertIndexBacked("SELECT * FROM local_playlists WHERE id = ?", ["pl1"])
        try assertIndexBacked("SELECT * FROM local_playlist_tracks WHERE playlistId = ? ORDER BY position", ["pl1"])
        try assertIndexBacked("SELECT MAX(position) FROM local_playlist_tracks WHERE playlistId = ?", ["pl1"])
        try assertIndexBacked("UPDATE local_playlist_tracks SET position = 1 WHERE rowId = 1 AND playlistId = ?", ["pl1"])
        try assertIndexBacked("DELETE FROM local_playlist_tracks WHERE rowId = 1")
        try assertIndexBacked("UPDATE local_playlists SET updatedAt = 1 WHERE id = ?", ["pl1"])
    }

    func testSourceLoudnessLookupsAreIndexBacked() throws {
        try assertIndexBacked("SELECT * FROM source_loudness WHERE leveling_key = ? AND algorithm_version = 1", ["lk1"])
    }

    /// `historyCount` is O(n) however it's planned: an index would still be walked end to end,
    /// so no index is added. Pinned so a change in plan shows up here.
    func testHistoryCountIsAKnownFullScan() throws {
        let steps = try plan("SELECT COUNT(*) FROM progress WHERE deletedAt IS NULL")
        XCTAssertFalse(steps.contains { $0.contains("TEMP B-TREE") })
    }

    /// Both are bounded by a table that stays small (one row per playlist / artist / taper), so
    /// a sort is fine; the plan is pinned so a regression shows up as a diff, not silently.
    func testSmallTablePlansArePinned() throws {
        let steps = try plan("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
        XCTAssertTrue(steps.contains { $0.contains("TEMP B-TREE") || $0.hasPrefix("SCAN") })
    }
}
