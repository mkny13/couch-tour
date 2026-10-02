import Foundation

// Public phish.in playlists (#428): the community-made, read-only lists behind phish.in's
// /play/<slug> pages. Distinct from `LocalPlaylist` (device-local, editable) and from the
// `.playlist` queue kind — playing one uses `playlistQueueKey(slug)`. Shapes mirror Android's
// `Playlist`/`PlaylistEntry` in Api.kt.

/// One row of `GET /playlists`.
public struct PublicPlaylistSummary: Codable, Hashable, Sendable {
    public let id: Int64
    public let name: String
    public let slug: String
    public let description: String?
    /// Milliseconds (the API's `duration`).
    public let durationMs: Int64
    public let tracksCount: Int
    public let likesCount: Int
    public let likedByUser: Bool
    /// The API's `username`.
    public let author: String?

    enum CodingKeys: String, CodingKey {
        case id, name, slug, description
        case author = "username"
        case durationMs = "duration"
        case tracksCount = "tracks_count"
        case likesCount = "likes_count"
        case likedByUser = "liked_by_user"
    }

    public init(
        id: Int64 = 0, name: String, slug: String, description: String? = nil, durationMs: Int64 = 0,
        tracksCount: Int = 0, likesCount: Int = 0, likedByUser: Bool = false, author: String? = nil
    ) {
        self.id = id
        self.name = name
        self.slug = slug
        self.description = description
        self.durationMs = durationMs
        self.tracksCount = tracksCount
        self.likesCount = likesCount
        self.likedByUser = likedByUser
        self.author = author
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(Int64.self, forKey: .id) ?? 0
        name = try c.decode(String.self, forKey: .name)
        slug = try c.decode(String.self, forKey: .slug)
        description = try c.decodeIfPresent(String.self, forKey: .description)
        durationMs = try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 0
        tracksCount = try c.decodeIfPresent(Int.self, forKey: .tracksCount) ?? 0
        likesCount = try c.decodeIfPresent(Int.self, forKey: .likesCount) ?? 0
        likedByUser = try c.decodeIfPresent(Bool.self, forKey: .likedByUser) ?? false
        author = try c.decodeIfPresent(String.self, forKey: .author)
    }
}

struct PublicPlaylistsPage: Decodable {
    let playlists: [PublicPlaylistSummary]

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        playlists = try c.decodeIfPresent([PublicPlaylistSummary].self, forKey: .playlists) ?? []
    }

    enum CodingKeys: String, CodingKey { case playlists }
}

/// One element of `entries` in `GET /playlists/<slug>`: a full `Track` plus the entry's own
/// position and effective length. An entry can be an excerpt of its track —
/// `startsAtSecond`/`endsAtSecond` are decoded but the player doesn't clip yet, so an excerpt
/// plays the whole track (its `duration` is still the clipped span).
public struct PublicPlaylistEntry: Codable, Equatable, Sendable {
    public let track: Track
    public let position: Int
    /// Milliseconds — the clipped span, not the whole track.
    public let duration: Int64
    public let startsAtSecond: Int?
    public let endsAtSecond: Int?

    enum CodingKeys: String, CodingKey {
        case track, position, duration
        case startsAtSecond = "starts_at_second"
        case endsAtSecond = "ends_at_second"
    }

    public init(
        track: Track, position: Int = 0, duration: Int64 = 0, startsAtSecond: Int? = nil, endsAtSecond: Int? = nil
    ) {
        self.track = track
        self.position = position
        self.duration = duration
        self.startsAtSecond = startsAtSecond
        self.endsAtSecond = endsAtSecond
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        track = try c.decode(Track.self, forKey: .track)
        position = try c.decodeIfPresent(Int.self, forKey: .position) ?? 0
        duration = try c.decodeIfPresent(Int64.self, forKey: .duration) ?? 0
        startsAtSecond = try c.decodeIfPresent(Int.self, forKey: .startsAtSecond)
        endsAtSecond = try c.decodeIfPresent(Int.self, forKey: .endsAtSecond)
    }

    public var playable: Bool { track.playable }
}

public struct PublicPlaylist: Decodable, Equatable, Sendable {
    public let summary: PublicPlaylistSummary
    public let entries: [PublicPlaylistEntry]

    public var name: String { summary.name }
    public var slug: String { summary.slug }

    enum CodingKeys: String, CodingKey { case entries }

    public init(summary: PublicPlaylistSummary, entries: [PublicPlaylistEntry] = []) {
        self.summary = summary
        self.entries = entries
    }

    public init(from decoder: Decoder) throws {
        summary = try PublicPlaylistSummary(from: decoder)
        let c = try decoder.container(keyedBy: CodingKeys.self)
        entries = try c.decodeIfPresent([PublicPlaylistEntry].self, forKey: .entries) ?? []
    }

    /// Playable entries only, so the UI list and the queue agree on indexes (D12).
    public var playableEntries: [PublicPlaylistEntry] { entries.filter(\.playable) }

    /// Wrapped in a `ShowDetail` so it flows through `Player.play(detail:)` like every other
    /// queue (same trick as a local playlist); the playlist's name and track count stand in for
    /// the artist and date.
    public func toShowDetail() -> ShowDetail {
        let playable = playableEntries.map { entry -> PlayableTrack in
            let t = entry.track
            // The entry's duration is the effective (possibly clipped) length.
            let sized = Track(
                id: t.id, slug: t.slug, title: t.title, likesCount: t.likesCount, likedByUser: t.likedByUser,
                position: entry.position, duration: entry.duration > 0 ? entry.duration : t.duration,
                setName: t.setName, audioStatus: t.audioStatus, mp3Url: t.mp3Url,
                waveformImageUrl: t.waveformImageUrl, showDate: t.showDate, venueName: t.venueName,
                venueLocation: t.venueLocation, showAlbumCoverUrl: t.showAlbumCoverUrl, tags: t.tags)
            return sized.toPlayableTrack(showArt: nil)
        }
        let artist = ArtistRef(backend: .phishin, id: "playlist:\(slug)", name: name)
        let label = "\(playable.count) \(playable.count == 1 ? "track" : "tracks")"
        return ShowDetail(
            summary: ShowSummary(artist: artist, date: label), tracks: playable, queueKey: playlistQueueKey(slug))
    }
}
