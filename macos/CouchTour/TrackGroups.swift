import CouchTourKit

struct TrackGroup {
    let setName: String
    let tracks: [PlayableTrack]
}

/// Preserves first-appearance order of set names, since sets already arrive in index order from
/// the catalog layer (Catalog.swift / RelistenAPI.swift) — grouping here must not re-sort them.
/// Shared by ShowDetailView and NowPlayingInspector so both group a show's tracks the same way.
func trackGroups(_ tracks: [PlayableTrack]) -> [TrackGroup] {
    var order: [String] = []
    var buckets: [String: [PlayableTrack]] = [:]
    for track in tracks {
        if buckets[track.setName] == nil { order.append(track.setName) }
        buckets[track.setName, default: []].append(track)
    }
    return order.map { TrackGroup(setName: $0, tracks: buckets[$0] ?? []) }
}
