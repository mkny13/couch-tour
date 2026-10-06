import Foundation

/// What a locally liked Relisten track needs to render and play in the Library without a
/// network call (#539, port of Android's record-backed likes from D302). Mirrors the
/// denormalized fields of `LocalPlaylistTrack` so playback reuses `resolveLocalPlaylistTracks`.
public struct LikedTrackRecord: Codable, Equatable, Sendable {
    public var trackId: String
    public var backend: String
    public var showDate: String
    public var artistSlug: String?
    public var recordingId: String?
    public var title: String
    public var durationMs: Int64
    public var venueName: String?
    public var artUrl: String?
    /// Epoch ms; nil for records that predate the timestamp.
    public var likedAt: Int64?

    public init(
        trackId: String, backend: String, showDate: String, artistSlug: String? = nil,
        recordingId: String? = nil, title: String, durationMs: Int64, venueName: String? = nil,
        artUrl: String? = nil, likedAt: Int64? = nil
    ) {
        self.trackId = trackId
        self.backend = backend
        self.showDate = showDate
        self.artistSlug = artistSlug
        self.recordingId = recordingId
        self.title = title
        self.durationMs = durationMs
        self.venueName = venueName
        self.artUrl = artUrl
        self.likedAt = likedAt
    }

    /// A row with no title or show date would render as a bare id, so it isn't listable.
    public var hasDisplayMetadata: Bool {
        !title.trimmingCharacters(in: .whitespaces).isEmpty
            && !showDate.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// The playlist-row shape `resolveLocalPlaylistTracks` plays from.
    public var asPlaylistTrack: LocalPlaylistTrack {
        LocalPlaylistTrack(
            playlistId: "", backend: backend, trackId: trackId, showDate: showDate,
            artistSlug: artistSlug, recordingId: recordingId, title: title,
            durationMs: durationMs, venueName: venueName, artUrl: artUrl
        )
    }
}

/// Local likes for Relisten tracks (#58, port of Android's LikedTracks.kt). Relisten has no
/// account system, so there's nothing to route a like through server-side — this lives in
/// `UserDefaults`, same as `Favorites`, deliberately not routed through
/// `PhishInAPI.like`/`unlike`, which is phish.in-only.
///
/// `ids` is the liked state and stays the flat `Set<String>` it always was, so likes made before
/// records existed survive upgrade; `records` adds the display metadata (#539) for tracks liked
/// since. An id without a record is liked but not listable in the Library.
@MainActor
public final class LikedTracks: ObservableObject {
    private let defaults: UserDefaults
    private let storageKey = "liked_relisten_track_ids"
    private let recordsKey = "liked_relisten_track_records"

    @Published public private(set) var ids: Set<String>
    @Published public private(set) var records: [String: LikedTrackRecord]

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let liked = Set(defaults.stringArray(forKey: storageKey) ?? [])
        self.ids = liked
        let stored = defaults.data(forKey: recordsKey)
            .flatMap { try? JSONDecoder().decode([LikedTrackRecord].self, from: $0) } ?? []
        // A record whose id was since unliked is stale; drop it rather than resurrect the like.
        self.records = Dictionary(
            stored.filter { liked.contains($0.trackId) }.map { ($0.trackId, $0) },
            uniquingKeysWith: { _, last in last }
        )
    }

    /// Toggles by id alone; liking this way stores no record, so the track isn't listable.
    public func toggle(_ id: String) {
        toggle(id, record: nil)
    }

    /// Toggles a like and, when liking, persists the record that makes it listable.
    public func toggle(_ record: LikedTrackRecord) {
        toggle(record.trackId, record: record)
    }

    private func toggle(_ id: String, record: LikedTrackRecord?) {
        if ids.contains(id) {
            ids.remove(id)
            records[id] = nil
        } else {
            ids.insert(id)
            if let record { records[id] = record }
        }
        defaults.set(Array(ids), forKey: storageKey)
        if let data = try? JSONEncoder().encode(Array(records.values)) {
            defaults.set(data, forKey: recordsKey)
        }
    }

    /// Records safe to show: liked, and carrying enough metadata not to render a raw id.
    public var listableRecords: [LikedTrackRecord] {
        records.values.filter { ids.contains($0.trackId) && $0.hasDisplayMetadata }
    }
}
