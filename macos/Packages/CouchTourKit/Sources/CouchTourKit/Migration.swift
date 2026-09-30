import Foundation
import GRDB

/// One-time merge of the unsandboxed stores into the sandboxed container's (#345).
///
/// The Sparkle (CI) build is App Sandbox'd (D104); a locally installed build used to be
/// re-signed without entitlements and so wrote to `~/Library/Preferences` and
/// `~/Library/Application Support` instead of the container. Flipping between the two made
/// favorites, settings and history swap or vanish. Local installs now stay sandboxed, and
/// the first sandboxed launch folds whatever the unsandboxed side holds into the container,
/// never discarding either side.
public enum UnsandboxedMigration {
    public static let doneKey = "migration_done_v1"

    public enum Outcome: Equatable {
        case notSandboxed
        case alreadyDone
        case migrated
        /// A source existed but couldn't be read (e.g. denied by the sandbox); the done flag
        /// is left unset so the next launch retries.
        case deferred
    }

    /// True when running inside an App Sandbox container. `APP_SANDBOX_CONTAINER_ID` is set
    /// by the system for sandboxed processes.
    public static var isSandboxed: Bool {
        ProcessInfo.processInfo.environment["APP_SANDBOX_CONTAINER_ID"] != nil
    }

    /// The user's real home, which `NSHomeDirectory()` hides behind the container when sandboxed.
    public static func realHomeDirectory() -> URL {
        if let pw = getpwuid(getuid()), let dir = pw.pointee.pw_dir {
            return URL(fileURLWithPath: String(cString: dir), isDirectory: true)
        }
        return URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
    }

    private static let setKeys = ["favorite_artist_keys", "liked_relisten_track_ids"]
    /// Copied only when the container has no value of its own.
    private static let fillKeys = ["skip_filler_tracks", "level_volume", "app_theme_mode", "playerVolume"]
    /// Cursors: the lower of the two, so a re-pull or re-push is the worst case rather than a
    /// skipped row. The merged database already holds the union either way.
    private static let minKeys = ["sync.lastSeq", "sync.lastPushWatermark"]
    private static let maxKeys = ["sync.lastSyncedAt"]

    /// - Parameters:
    ///   - bundleID: whose unsandboxed preferences plist to read (`<realHome>/Library/Preferences/<bundleID>.plist`).
    ///   - appSupportDirName: the GRDB directory name, as passed to `ProgressStore.defaultURL`.
    ///   - sandboxed: overridable for tests; defaults to `isSandboxed`.
    @discardableResult
    public static func migrateIfNeeded(
        bundleID: String,
        appSupportDirName: String,
        defaults: UserDefaults = .standard,
        destinationDatabase: URL,
        realHome: URL = realHomeDirectory(),
        sandboxed: Bool = isSandboxed
    ) -> Outcome {
        guard sandboxed else { return .notSandboxed }
        guard !defaults.bool(forKey: doneKey) else { return .alreadyDone }

        let plist = realHome.appendingPathComponent("Library/Preferences/\(bundleID).plist")
        let sourceDB = realHome
            .appendingPathComponent("Library/Application Support/\(appSupportDirName)/phishin.db")

        var deferred = false
        do {
            try mergeDefaults(from: plist, into: defaults)
        } catch {
            NSLog("Couldn't migrate unsandboxed preferences: \(error)")
            deferred = true
        }
        do {
            try mergeDatabase(from: sourceDB, into: destinationDatabase)
        } catch {
            NSLog("Couldn't migrate unsandboxed listening history: \(error)")
            deferred = true
        }
        if deferred { return .deferred }
        defaults.set(true, forKey: doneKey)
        return .migrated
    }

    static func mergeDefaults(from plist: URL, into defaults: UserDefaults) throws {
        guard FileManager.default.fileExists(atPath: plist.path) else { return }
        let data = try Data(contentsOf: plist)
        guard let source = try PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        else { return }

        for key in setKeys {
            guard let theirs = source[key] as? [String] else { continue }
            let merged = Set(defaults.stringArray(forKey: key) ?? []).union(theirs)
            defaults.set(Array(merged).sorted(), forKey: key)
        }
        for key in fillKeys where defaults.object(forKey: key) == nil {
            if let value = source[key] { defaults.set(value, forKey: key) }
        }
        for key in minKeys {
            guard let theirs = (source[key] as? NSNumber)?.int64Value else { continue }
            if defaults.object(forKey: key) == nil {
                defaults.set(Int(theirs), forKey: key)
            } else {
                defaults.set(Int(min(theirs, Int64(defaults.integer(forKey: key)))), forKey: key)
            }
        }
        for key in maxKeys {
            guard let theirs = (source[key] as? NSNumber)?.int64Value else { continue }
            defaults.set(Int(max(theirs, Int64(defaults.integer(forKey: key)))), forKey: key)
        }
    }

    /// Merges progress, tour-preference and taper-preference rows, keeping whichever side has
    /// the higher `updatedAt` (tombstones included, so a deletion on either side survives).
    /// Source is opened as a scratch copy: the sandbox only grants read access to it, and
    /// SQLite's WAL mode needs to write `-shm`.
    static func mergeDatabase(from source: URL, into destination: URL) throws {
        let fm = FileManager.default
        guard fm.fileExists(atPath: source.path) else { return }

        let scratch = fm.temporaryDirectory
            .appendingPathComponent("couchtour-migration-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: scratch) }

        let copy = scratch.appendingPathComponent("phishin.db")
        for suffix in ["", "-wal", "-shm"] {
            let from = URL(fileURLWithPath: source.path + suffix)
            if fm.fileExists(atPath: from.path) {
                try fm.copyItem(at: from, to: URL(fileURLWithPath: copy.path + suffix))
            }
        }

        // Opening through ProgressStore also brings an older source schema up to date.
        let sourceStore = try ProgressStore(url: copy)
        let destStore = try ProgressStore(url: destination)

        let progress = try sourceStore.dbQueue.read { db in try PlaybackProgress.fetchAll(db) }
        let tours = try sourceStore.dbQueue.read { db in try ArtistTourPreference.fetchAll(db) }
        let tapers = try sourceStore.dbQueue.read { db in try TaperPreference.fetchAll(db) }

        try destStore.dbQueue.write { db in
            for row in progress {
                let mine = try PlaybackProgress.fetchOne(db, key: row.queueKey)
                if mine == nil || row.updatedAt > mine!.updatedAt { try row.save(db) }
            }
            for row in tours {
                let mine = try ArtistTourPreference.fetchOne(db, key: row.artistKey)
                if mine == nil || row.updatedAt > mine!.updatedAt { try row.save(db) }
            }
            for row in tapers {
                let mine = try TaperPreference.fetchOne(db, key: row.taperName)
                if mine == nil || row.updatedAt > mine!.updatedAt { try row.save(db) }
            }
        }
    }
}
