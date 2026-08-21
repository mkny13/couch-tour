import Foundation

/// Local likes for Relisten tracks (#58, port of Android's LikedTracks.kt). Relisten has no
/// account system, so there's nothing to route a like through server-side — this is a set of
/// `PlayableTrack.id`s (Relisten track uuids), same `UserDefaults` shape as `Favorites`,
/// deliberately not routed through `PhishInAPI.like`/`unlike`, which is phish.in-only.
@MainActor
public final class LikedTracks: ObservableObject {
    private let defaults: UserDefaults
    private let storageKey = "liked_relisten_track_ids"

    @Published public private(set) var ids: Set<String>

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.ids = Set(defaults.stringArray(forKey: storageKey) ?? [])
    }

    public func toggle(_ id: String) {
        if ids.contains(id) {
            ids.remove(id)
        } else {
            ids.insert(id)
        }
        defaults.set(Array(ids), forKey: storageKey)
    }
}
