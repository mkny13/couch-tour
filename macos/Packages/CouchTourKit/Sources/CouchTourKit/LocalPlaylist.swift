import Foundation
import GRDB

// Local playlists (#59, port of Android's LocalPlaylist.kt + Progress.kt's MIGRATION_7_8).
// Account-free, spans both backends: an ordered list of refs (backend, show date, and for
// Relisten an artist slug and tape id) resolved back to real tracks at play time, since
// neither backend has a fetch-track-by-id endpoint (D161 in DECISIONS.md). New tables only,
// on the same phishin.db/dbQueue ProgressStore already opens — sharing its one connection
// rather than a second one to the same file, see ProgressStore.dbQueue.

public struct LocalPlaylist: Codable, Hashable, FetchableRecord, PersistableRecord {
    public static let databaseTableName = "local_playlists"

    public var id: String
    public var name: String
    /// Denormalized, bumped alongside every add/remove, so the playlists list renders a
    /// count with no second query per row.
    public var trackCount: Int
    public var createdAt: Int64
    public var updatedAt: Int64

    public init(id: String, name: String, trackCount: Int = 0, createdAt: Int64, updatedAt: Int64) {
        self.id = id
        self.name = name
        self.trackCount = trackCount
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

/// One entry in a playlist. `trackId`/`showDate`/`artistSlug`/`recordingId` are what
/// `resolveLocalPlaylistTracks` re-fetches with; `title`/`durationMs`/`venueName`/`artUrl`
/// are denormalized purely for rendering the playlist's own list without a network call —
/// playback always uses the freshly-fetched track, never these display fields, since a
/// track's `mp3Url` isn't stored here at all.
public struct LocalPlaylistTrack: Codable, Equatable, FetchableRecord, MutablePersistableRecord {
    public static let databaseTableName = "local_playlist_tracks"

    public var rowId: Int64?
    public var playlistId: String
    public var position: Int
    public var backend: String
    public var trackId: String
    public var showDate: String
    /// Relisten only.
    public var artistSlug: String?
    /// Relisten only; nil means the tape that was the default at add-time.
    public var recordingId: String?
    public var title: String
    public var durationMs: Int64
    public var venueName: String?
    public var artUrl: String?

    public init(
        rowId: Int64? = nil, playlistId: String, position: Int = 0, backend: String, trackId: String,
        showDate: String, artistSlug: String? = nil, recordingId: String? = nil, title: String,
        durationMs: Int64, venueName: String? = nil, artUrl: String? = nil
    ) {
        self.rowId = rowId
        self.playlistId = playlistId
        self.position = position
        self.backend = backend
        self.trackId = trackId
        self.showDate = showDate
        self.artistSlug = artistSlug
        self.recordingId = recordingId
        self.title = title
        self.durationMs = durationMs
        self.venueName = venueName
        self.artUrl = artUrl
    }

    public mutating func didInsert(_ inserted: InsertionSuccess) {
        rowId = inserted.rowID
    }
}

/// GRDB wrapper mirroring Android's `LocalPlaylistDao` one-for-one.
public final class LocalPlaylistStore {
    private let dbQueue: DatabaseQueue

    public init(sharing progressStore: ProgressStore) throws {
        dbQueue = progressStore.dbQueue
        try migrator.migrate(dbQueue)
    }

    /// In-memory store for tests, not sharing a `ProgressStore`.
    public static func inMemory() throws -> LocalPlaylistStore {
        try LocalPlaylistStore(dbQueue: DatabaseQueue())
    }

    private init(dbQueue: DatabaseQueue) throws {
        self.dbQueue = dbQueue
        try migrator.migrate(dbQueue)
    }

    /// New tables only — `progress` is untouched, matching Android's `MIGRATION_7_8` (#12,
    /// D161). Registered under its own name so it applies once, whichever store (this one or
    /// `ProgressStore`) happens to open the file first.
    private var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("v8_localPlaylists") { db in
            try db.create(table: "local_playlists") { t in
                t.column("id", .text).primaryKey()
                t.column("name", .text).notNull()
                t.column("trackCount", .integer).notNull().defaults(to: 0)
                t.column("createdAt", .integer).notNull()
                t.column("updatedAt", .integer).notNull()
            }
            try db.create(table: "local_playlist_tracks") { t in
                t.autoIncrementedPrimaryKey("rowId")
                t.column("playlistId", .text).notNull().indexed()
                    .references("local_playlists", column: "id", onDelete: .cascade)
                t.column("position", .integer).notNull()
                t.column("backend", .text).notNull()
                t.column("trackId", .text).notNull()
                t.column("showDate", .text).notNull()
                t.column("artistSlug", .text)
                t.column("recordingId", .text)
                t.column("title", .text).notNull()
                t.column("durationMs", .integer).notNull()
                t.column("venueName", .text)
                t.column("artUrl", .text)
            }
        }
        return migrator
    }

    public func playlists() throws -> [LocalPlaylist] {
        try dbQueue.read { db in
            try LocalPlaylist.order(Column("updatedAt").desc).fetchAll(db)
        }
    }

    public func playlist(id: String) throws -> LocalPlaylist? {
        try dbQueue.read { db in try LocalPlaylist.fetchOne(db, key: id) }
    }

    public func tracks(playlistId: String) throws -> [LocalPlaylistTrack] {
        try dbQueue.read { db in
            try LocalPlaylistTrack
                .filter(Column("playlistId") == playlistId)
                .order(Column("position"))
                .fetchAll(db)
        }
    }

    @discardableResult
    public func createPlaylist(name: String, now: Int64) throws -> LocalPlaylist {
        let playlist = LocalPlaylist(id: UUID().uuidString, name: name, createdAt: now, updatedAt: now)
        try dbQueue.write { db in try playlist.insert(db) }
        return playlist
    }

    public func deletePlaylist(id: String) throws {
        try dbQueue.write { db in _ = try LocalPlaylist.deleteOne(db, key: id) }
    }

    public func renamePlaylist(id: String, name: String, now: Int64) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE local_playlists SET name = ?, updatedAt = ? WHERE id = ?",
                arguments: [name, now, id]
            )
        }
    }

    /// Appends at the end.
    public func addTrack(_ track: LocalPlaylistTrack, toPlaylist playlistId: String, now: Int64) throws {
        try dbQueue.write { db in
            let maxPosition = try Int.fetchOne(
                db, sql: "SELECT MAX(position) FROM local_playlist_tracks WHERE playlistId = ?", arguments: [playlistId]
            ) ?? -1
            var row = track
            row.rowId = nil
            row.position = maxPosition + 1
            try row.insert(db)
            try db.execute(
                sql: "UPDATE local_playlists SET trackCount = trackCount + 1, updatedAt = ? WHERE id = ?",
                arguments: [now, playlistId]
            )
        }
    }

    public func removeTrack(rowId: Int64, fromPlaylist playlistId: String, now: Int64) throws {
        try dbQueue.write { db in
            _ = try LocalPlaylistTrack.deleteOne(db, key: rowId)
            try db.execute(
                sql: "UPDATE local_playlists SET trackCount = MAX(trackCount - 1, 0), updatedAt = ? WHERE id = ?",
                arguments: [now, playlistId]
            )
        }
    }

    /// Reorders the playlist's tracks to match `orderedRowIds` and updates `updatedAt`.
    public func reorderTracks(playlistId: String, orderedRowIds: [Int64], now: Int64) throws {
        try dbQueue.write { db in
            for (index, rowId) in orderedRowIds.enumerated() {
                try db.execute(
                    sql: "UPDATE local_playlist_tracks SET position = ? WHERE rowId = ? AND playlistId = ?",
                    arguments: [index, rowId, playlistId]
                )
            }
            try db.execute(
                sql: "UPDATE local_playlists SET updatedAt = ? WHERE id = ?",
                arguments: [now, playlistId]
            )
        }
    }
}

/// Resolves a playlist's stored refs back into playable tracks: one fetch per distinct
/// show/tape rather than per track, matching `LocalPlaylist.kt`'s
/// `resolveLocalPlaylistTracks`. A show that fails to fetch — deleted, network error, a
/// stale ref — drops just its own rows from the result rather than failing the whole
/// playlist, since neither backend has fetch-track-by-id to retry against.
public func resolveLocalPlaylistTracks(_ rows: [LocalPlaylistTrack]) async -> [PlayableTrack] {
    struct GroupKey: Hashable {
        let backend: String
        let showDate: String
        let artistSlug: String?
        let recordingId: String?
    }

    func key(for row: LocalPlaylistTrack) -> GroupKey {
        GroupKey(backend: row.backend, showDate: row.showDate, artistSlug: row.artistSlug, recordingId: row.recordingId)
    }

    var order: [GroupKey] = []
    var seen = Set<GroupKey>()
    for row in rows {
        let k = key(for: row)
        if seen.insert(k).inserted { order.append(k) }
    }

    // Concurrent, not a sequential for-loop: N distinct shows/tapes used to pay N sequential
    // HTTP round trips before playback could start, reproducing Android's 30+ second resume
    // stall on a wide mixtape playlist (D175 — the bug was found and fixed there first).
    var detailsByKey: [GroupKey: ShowDetail] = [:]
    await withTaskGroup(of: (GroupKey, ShowDetail?).self) { group in
        for k in order {
            group.addTask {
                guard let backend = Backend.from(k.backend) else { return (k, nil) }
                let artist: ArtistRef
                switch backend {
                case .phishin:
                    artist = PHISH
                case .relisten:
                    guard let slug = k.artistSlug else { return (k, nil) }
                    artist = ArtistRef(backend: .relisten, id: slug, name: "")
                }
                let detail = try? await sourceFor(backend).show(artist: artist, date: k.showDate, recordingId: k.recordingId)
                return (k, detail)
            }
        }
        for await (k, detail) in group {
            if let detail { detailsByKey[k] = detail }
        }
    }

    return rows.compactMap { row in
        detailsByKey[key(for: row)]?.tracks.first { $0.id == row.trackId }
    }
}

/// Search within a local playlist (#90). Returns matches paired with their index in the
/// original, unfiltered `tracks` array — both playback and reordering (`.onMove`,
/// `moveUp`/`moveDown` in `LocalPlaylistView`) key off that original position, so the index
/// has to survive filtering intact rather than being recomputed from the filtered list's own
/// position. Port of `Catalog.kt`'s `filterByTitleIndexed`.
public func filterByTitleIndexed(_ tracks: [LocalPlaylistTrack], query: String) -> [(offset: Int, element: LocalPlaylistTrack)] {
    let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
    return tracks.enumerated().filter { _, track in
        trimmed.isEmpty || track.title.range(of: trimmed, options: .caseInsensitive) != nil
    }
}

extension Array where Element == LocalPlaylistTrack {
    public func filterByTitleIndexed(_ query: String) -> [(offset: Int, element: LocalPlaylistTrack)] {
        CouchTourKit.filterByTitleIndexed(self, query: query)
    }
}
