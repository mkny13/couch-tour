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

    public init(
        queueKey: String, title: String, subtitle: String, artUrl: String? = nil, trackIndex: Int,
        positionMs: Int64, trackTitle: String, updatedAt: Int64, finished: Bool = false,
        dismissed: Bool = false, artist: String = ""
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
    }
}

/// GRDB wrapper around the `progress` table. Query shapes mirror Android's `ProgressDao`
/// one-for-one so the two clients' notions of "history" and "continue listening" never diverge.
public final class ProgressStore {
    private let dbQueue: DatabaseQueue

    /// Default on-disk location: `~/Library/Application Support/dev.mike.couchtour/phishin.db`.
    /// Same filename as Android for the same reason Android kept it across its own rename — a
    /// future "import from your phone" step is then a file copy, not a translation.
    public static func defaultURL() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = appSupport.appendingPathComponent("dev.mike.couchtour", isDirectory: true)
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
        return migrator
    }

    public func put(_ progress: PlaybackProgress) throws {
        try dbQueue.write { db in try progress.save(db) }
    }

    /// The "Continue listening" row: still going, and not hidden by hand.
    public func inProgress() throws -> [PlaybackProgress] {
        try dbQueue.read { db in
            try PlaybackProgress
                .filter(Column("finished") == false && Column("dismissed") == false)
                .order(Column("updatedAt").desc)
                .limit(25)
                .fetchAll(db)
        }
    }

    /// Everything ever played, including finished and dismissed queues.
    public func history() throws -> [PlaybackProgress] {
        try dbQueue.read { db in
            try PlaybackProgress.order(Column("updatedAt").desc).fetchAll(db)
        }
    }

    public func historyCount() throws -> Int {
        try dbQueue.read { db in try PlaybackProgress.fetchCount(db) }
    }

    /// The bands in history, for grouping it. Blank artists are skipped — see `PlaybackProgress.artist`.
    public func artists() throws -> [String] {
        try dbQueue.read { db in
            try String.fetchAll(
                db,
                sql: "SELECT DISTINCT artist FROM progress WHERE artist != '' ORDER BY artist"
            )
        }
    }

    public func historyFor(artist: String) throws -> [PlaybackProgress] {
        try dbQueue.read { db in
            try PlaybackProgress
                .filter(Column("artist") == artist)
                .order(Column("updatedAt").desc)
                .fetchAll(db)
        }
    }

    public func get(key: String) throws -> PlaybackProgress? {
        try dbQueue.read { db in try PlaybackProgress.fetchOne(db, key: key) }
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

    public func clear(key: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM progress WHERE queueKey = ?", arguments: [key])
        }
    }
}
