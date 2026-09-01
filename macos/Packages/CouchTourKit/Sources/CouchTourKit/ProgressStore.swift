import Foundation
import GRDB

/// One row per queue the user has listened to. `queueKey` is namespaced ("show:1997-11-17")
/// so playlists and Relisten recordings share the same table without a migration — see
/// QueueKey.swift.
///
/// Schema is byte-identical to Android's `progress` table at schema version 6
/// (app/schemas/dev.mike.couchtour.PhishInDb/6.json in the Android repo): same columns, same
/// types, same primary key. That parity is what keeps a future sync feature additive instead
/// of a migration on both sides — see the plan's "sync-readiness contract".
public struct PlaybackProgress: Codable, Equatable, FetchableRecord, PersistableRecord {
    public static let databaseTableName = "progress"

    public var queueKey: String
    public var title: String
    public var subtitle: String
    public var artUrl: String?
    public var trackIndex: Int
    /// Milliseconds — matches Android's Long positionMs.
    public var positionMs: Int64
    public var trackTitle: String
    /// Epoch milliseconds — matches Android's System.currentTimeMillis(), NOT epoch seconds.
    public var updatedAt: Int64
    /// Set when the queue played through to its end.
    public var finished: Bool
    /// Set when the user removes it from "Continue listening" by hand. It stays in history;
    /// playing it again clears the flag and brings it back.
    public var dismissed: Bool
    /// The band, denormalised like the rest of the display fields so history renders without
    /// a network call or a look at which backend the key belongs to.
    public var artist: String
    /// Epoch milliseconds this queue was cleared, or nil while it's live. A tombstone rather
    /// than a real row deletion: a sync client needs to know a row was removed, not just that
    /// it's absent, to avoid a later push from another device silently bringing it back.
    public var deletedAt: Int64?

    public init(
        queueKey: String, title: String, subtitle: String, artUrl: String? = nil, trackIndex: Int,
        positionMs: Int64, trackTitle: String, updatedAt: Int64, finished: Bool = false,
        dismissed: Bool = false, artist: String = "", deletedAt: Int64? = nil
    ) {
        self.queueKey = queueKey
        self.title = title
        self.subtitle = subtitle
        self.artUrl = artUrl
        self.trackIndex = trackIndex
        self.positionMs = positionMs
        self.trackTitle = trackTitle
        self.updatedAt = updatedAt
        self.finished = finished
        self.dismissed = dismissed
        self.artist = artist
        self.deletedAt = deletedAt
    }
}

/// Defunct / non-touring artist tour and year tracking preferences (#68, D190).
/// Persisted in GRDB on macOS and Room on Android (MIGRATION_8_9).
public struct ArtistTourPreference: Codable, Equatable, Hashable, FetchableRecord, PersistableRecord, TableRecord, Sendable {
    public static let databaseTableName = "artist_tour_preferences"

    public var artistKey: String
    public var tourName: String?
    public var year: String?
    public var updatedAt: Int64

    public init(
        artistKey: String,
        tourName: String? = nil,
        year: String? = nil,
        updatedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.artistKey = artistKey
        self.tourName = tourName
        self.year = year
        self.updatedAt = updatedAt
    }
}

/// GRDB wrapper around the `progress` table. Query shapes mirror Android's `ProgressDao`
/// one-for-one so the two clients' notions of "history" and "continue listening" never diverge.
public final class ProgressStore {
    /// Internal, not private: `LocalPlaylistStore` (#59) shares this one connection rather
    /// than opening a second `DatabaseQueue` to the same `phishin.db` file, which GRDB
    /// recommends against within a single process.
    let dbQueue: DatabaseQueue

    /// Default on-disk location: `~/Library/Application Support/dev.mike.couchtour/phishin.db`.
    /// Same filename as Android for the same reason Android kept it across its own rename — a
    /// future "import from your phone" step is then a file copy, not a translation.
    ///
    /// `appSupportDirName` is overridable so the side-installed beta target (see project.yml)
    /// can point at its own subdirectory instead of silently sharing the regular app's
    /// listening history — the regular app must keep this default forever, matching its
    /// already-shipped on-disk path (CLAUDE.md's "names that look wrong and are not").
    public static func defaultURL(appSupportDirName: String = "dev.mike.couchtour") -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = appSupport.appendingPathComponent(appSupportDirName, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("phishin.db")
    }

    public init(url: URL = ProgressStore.defaultURL()) throws {
        dbQueue = try DatabaseQueue(path: url.path)
        try migrator.migrate(dbQueue)
    }

    /// In-memory store for tests.
    public static func inMemory() throws -> ProgressStore {
        try ProgressStore(dbQueue: DatabaseQueue())
    }

    private init(dbQueue: DatabaseQueue) throws {
        self.dbQueue = dbQueue
        try migrator.migrate(dbQueue)
    }

    /// The desktop client has no data predating this table, so it starts directly at the v6
    /// shape rather than replaying Android's v1-v6 migration history (D83, logged in
    /// DECISIONS.md). Any future migration here is still a real `registerMigration`, never
    /// destructive — same rule as CLAUDE.md states for Android.
    private var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("v6_initial") { db in
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
        // Adds the deletedAt tombstone (Android's MIGRATION_6_7). A separate registered
        // migration, not folded into v6_initial, because existing desktop installs have
        // already run that one — GRDB replays only migrations a database hasn't seen yet.
        migrator.registerMigration("v7_deletedAt") { db in
            try db.alter(table: "progress") { t in
                t.add(column: "deletedAt", .integer)
            }
        }
        migrator.registerMigration("v9_artistTourPreferences") { db in
            try db.create(table: "artist_tour_preferences") { t in
                t.column("artistKey", .text).primaryKey()
                t.column("tourName", .text)
                t.column("year", .text)
                t.column("updatedAt", .integer).notNull()
            }
        }
        return migrator
    }

    public func put(_ progress: PlaybackProgress) throws {
        try dbQueue.write { db in try progress.save(db) }
    }

    /// The "Continue listening" row: still going, and not hidden by hand.
    public func inProgress() throws -> [PlaybackProgress] {
        try dbQueue.read { db in
            try PlaybackProgress
                .filter(Column("finished") == false && Column("dismissed") == false
                    && Column("deletedAt") == nil)
                .order(Column("updatedAt").desc)
                .limit(25)
                .fetchAll(db)
        }
    }

    /// Everything ever played, including finished and dismissed queues.
    public func history() throws -> [PlaybackProgress] {
        try dbQueue.read { db in
            try PlaybackProgress
                .filter(Column("deletedAt") == nil)
                .order(Column("updatedAt").desc)
                .fetchAll(db)
        }
    }

    public func historyCount() throws -> Int {
        try dbQueue.read { db in
            try PlaybackProgress.filter(Column("deletedAt") == nil).fetchCount(db)
        }
    }

    /// The bands in history, for grouping it. Blank artists are skipped — see `PlaybackProgress.artist`.
    public func artists() throws -> [String] {
        try dbQueue.read { db in
            try String.fetchAll(
                db,
                sql: "SELECT DISTINCT artist FROM progress WHERE artist != '' AND deletedAt IS NULL ORDER BY artist"
            )
        }
    }

    public func historyFor(artist: String) throws -> [PlaybackProgress] {
        try dbQueue.read { db in
            try PlaybackProgress
                .filter(Column("artist") == artist && Column("deletedAt") == nil)
                .order(Column("updatedAt").desc)
                .fetchAll(db)
        }
    }

    public func get(key: String) throws -> PlaybackProgress? {
        try dbQueue.read { db in
            try PlaybackProgress
                .filter(Column("queueKey") == key && Column("deletedAt") == nil)
                .fetchOne(db)
        }
    }

    public func dismiss(key: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: "UPDATE progress SET dismissed = 1 WHERE queueKey = ?", arguments: [key])
        }
    }

    public func markFinished(key: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: "UPDATE progress SET finished = 1 WHERE queueKey = ?", arguments: [key])
        }
    }

    /// Tombstones the row rather than deleting it, so a sync client can tell "removed" apart
    /// from "never existed" — see `PlaybackProgress.deletedAt`. Every read query filters it
    /// back out, so this is invisible to the rest of the app; `put` un-deletes by writing a
    /// fresh row with `deletedAt = nil`, the same way it already clears `dismissed`.
    public func clear(key: String, now: Int64) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE progress SET deletedAt = ?, updatedAt = ? WHERE queueKey = ?",
                arguments: [now, now, key]
            )
        }
    }

    /// Test-only: the raw row bypassing every public read's `deletedAt` filter, to verify the
    /// tombstone itself rather than what the rest of the app can see. Internal, not public —
    /// visible to the test target via `@testable import`.
    func rawRow(key: String) throws -> Row? {
        try dbQueue.read { db in
            try Row.fetchOne(db, sql: "SELECT * FROM progress WHERE queueKey = ?", arguments: [key])
        }
    }

    /// Rows to push on the next sync: everything touched since the last successful push,
    /// tombstones included — a delete has to reach the other device too. Deliberately does
    /// NOT filter `deletedAt IS NULL`, unlike every other read here — see Android's
    /// `ProgressDao.changedSince` for the same query.
    public func changedSince(_ since: Int64) throws -> [PlaybackProgress] {
        try dbQueue.read { db in
            try PlaybackProgress
                .filter(Column("updatedAt") > since)
                .fetchAll(db)
        }
    }

    // MARK: - Artist Tour Preferences (#68)

    public func saveTourPreference(_ preference: ArtistTourPreference) throws {
        try dbQueue.write { db in
            try preference.save(db)
        }
    }

    public func saveTourPreference(
        artistKey: String,
        tourName: String? = nil,
        year: String? = nil,
        now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) throws {
        let pref = ArtistTourPreference(artistKey: artistKey, tourName: tourName, year: year, updatedAt: now)
        try saveTourPreference(pref)
    }

    public func getTourPreference(artistKey: String) throws -> ArtistTourPreference? {
        try dbQueue.read { db in
            try ArtistTourPreference.fetchOne(db, key: artistKey)
        }
    }

    public func getAllTourPreferences() throws -> [ArtistTourPreference] {
        try dbQueue.read { db in
            try ArtistTourPreference.order(Column("updatedAt").desc).fetchAll(db)
        }
    }

    public func deleteTourPreference(artistKey: String) throws {
        try dbQueue.write { db in
            _ = try ArtistTourPreference.deleteOne(db, key: artistKey)
        }
    }
}

/// The artists in `rows`, most-recently-played first, for `HistoryView`'s filter — deliberately
/// not `ProgressStore.artists()`, which orders alphabetically, or `historyFor(artist:)`, which
/// would be an N+1 against a list already in memory. `history()` is already `updatedAt` desc,
/// so first appearance in `rows` already is last-played order; no second query, no sort. Blank
/// artists are skipped, same as `artists()` — see `PlaybackProgress.artist`.
public func historyArtists(_ rows: [PlaybackProgress]) -> [String] {
    var seen = Set<String>()
    var result: [String] = []
    for row in rows where !row.artist.isEmpty {
        if seen.insert(row.artist).inserted { result.append(row.artist) }
    }
    return result
}
