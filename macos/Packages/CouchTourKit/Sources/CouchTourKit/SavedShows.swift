import Foundation

/// Saved/bookmarked shows: user-saved shows for quick library access and "On this date"
/// library badges, backed by `UserDefaults` mirroring `Favorites` and `LikedTracks`.
@MainActor
public final class SavedShows: ObservableObject {
    private let defaults: UserDefaults
    private let storageKey = "saved_show_keys"

    @Published public private(set) var keys: Set<String>

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.keys = Set(defaults.stringArray(forKey: storageKey) ?? [])
    }

    public func contains(_ key: String) -> Bool {
        keys.contains(key)
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
