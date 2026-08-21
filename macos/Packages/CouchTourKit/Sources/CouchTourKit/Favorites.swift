import Foundation

/// Favorited artists (#56, port of Android's `Favorites.kt`/#14): low-cardinality preference
/// data, so plain `UserDefaults` rather than a GRDB table — same reasoning as Android's choice
/// of `SharedPreferences` over Room. Unencrypted on purpose too: an artist name a user likes
/// isn't a credential, unlike the sync device token in `Keychain.swift`.
@MainActor
public final class Favorites: ObservableObject {
    private let defaults: UserDefaults
    private let storageKey = "favorite_artist_keys"

    /// `ArtistRef.key`s.
    @Published public private(set) var keys: Set<String>

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.keys = Set(defaults.stringArray(forKey: storageKey) ?? [])
    }

    public func toggle(_ key: String) {
        if keys.contains(key) {
            keys.remove(key)
        } else {
            keys.insert(key)
        }
        defaults.set(Array(keys), forKey: storageKey)
    }
}
