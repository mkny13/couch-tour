import Foundation

public struct FavoriteArtistSyncRow: Codable, Equatable, Sendable {
    public let artistKey: String
    public let updatedAt: Int64
    public let deletedAt: Int64?

    public init(artistKey: String, updatedAt: Int64, deletedAt: Int64? = nil) {
        self.artistKey = artistKey
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }
}

/// Favorited artists (#56, port of Android's `Favorites.kt`/#14): low-cardinality preference
/// data, so plain `UserDefaults` rather than a GRDB table — same reasoning as Android's choice
/// of `SharedPreferences` over Room. Unencrypted on purpose too: an artist name a user likes
/// isn't a credential, unlike the sync device token in `Keychain.swift`.
@MainActor
public final class Favorites: ObservableObject {
    private let defaults: UserDefaults
    private static let storageKey = "favorite_artist_keys"
    private static let rowsStorageKey = "favorite_artist_rows_json"

    /// `ArtistRef.key`s.
    @Published public private(set) var keys: Set<String>
    private var rows: [String: FavoriteArtistSyncRow]

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let (loadedRows, migrated) = Favorites.loadRows(defaults: defaults)
        self.rows = Dictionary(uniqueKeysWithValues: loadedRows.map { ($0.artistKey, $0) })
        self.keys = Set(loadedRows.filter { $0.deletedAt == nil }.map(\.artistKey))
        if migrated {
            persist()
        }
    }

    public func toggle(_ key: String) {
        let now = max(Int64(Date().timeIntervalSince1970 * 1000), (rows[key]?.updatedAt ?? 0) + 1)
        if keys.contains(key) {
            rows[key] = FavoriteArtistSyncRow(artistKey: key, updatedAt: now, deletedAt: now)
            keys.remove(key)
        } else {
            rows[key] = FavoriteArtistSyncRow(artistKey: key, updatedAt: now, deletedAt: nil)
            keys.insert(key)
        }
        persist()
    }

    public func changedSince(_ since: Int64) -> [FavoriteArtistSyncRow] {
        rows.values.filter { $0.updatedAt > since }.sorted { $0.updatedAt < $1.updatedAt }
    }

    @discardableResult
    public func applyFromSync(_ changes: [FavoriteArtistSyncRow]) -> Int {
        var accepted = 0
        for change in changes {
            if let existing = rows[change.artistKey], existing.updatedAt > change.updatedAt {
                continue
            }
            rows[change.artistKey] = change
            accepted += 1
        }
        guard accepted > 0 else { return 0 }
        keys = Set(rows.values.filter { $0.deletedAt == nil }.map(\.artistKey))
        persist()
        return accepted
    }

    private func persist() {
        let sorted = rows.values.sorted { $0.artistKey < $1.artistKey }
        if let encoded = try? JSONEncoder().encode(sorted),
           let raw = String(data: encoded, encoding: .utf8) {
            defaults.set(raw, forKey: Self.rowsStorageKey)
        }
        defaults.set(Array(keys).sorted(), forKey: Self.storageKey)
    }

    private static func loadRows(defaults: UserDefaults) -> (rows: [FavoriteArtistSyncRow], migrated: Bool) {
        if let raw = defaults.string(forKey: rowsStorageKey) {
            if let data = raw.data(using: .utf8),
               let decoded = try? JSONDecoder().decode([FavoriteArtistSyncRow].self, from: data) {
                return (decoded, false)
            }
            return ([], false)
        }
        let legacy = defaults.stringArray(forKey: storageKey) ?? []
        guard !legacy.isEmpty else { return ([], false) }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return (legacy.map { FavoriteArtistSyncRow(artistKey: $0, updatedAt: now, deletedAt: nil) }, true)
    }
}
