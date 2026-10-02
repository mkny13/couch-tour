import Foundation

// Public phish.in playlists (#428): the community-made, read-only lists behind phish.in's
// /play/<slug> pages. Distinct from `LocalPlaylist` (device-local, editable) and from the
// `.playlist` queue kind — playing one uses `playlistQueueKey(slug)`.

/// One row of `GET /playlists`.
public struct PublicPlaylistSummary: Codable, Hashable, Sendable {
    public let name: String
    public let slug: String
    public let description: String?
    public let durationMs: Int64
    public let tracksCount: Int
    public let likesCount: Int
    public let author: String?

    enum CodingKeys: String, CodingKey {
        case name, slug, description, author
        case durationMs = "duration_ms"
        case tracksCount = "tracks_count"
        case likesCount = "likes_count"
    }

    public init(
        name: String, slug: String, description: String? = nil, durationMs: Int64 = 0,
        tracksCount: Int = 0, likesCount: Int = 0, author: String? = nil
    ) {
        self.name = name
        self.slug = slug
        self.description = description
        self.durationMs = durationMs
        self.tracksCount = tracksCount
        self.likesCount = likesCount
        self.author = author
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        slug = try c.decode(String.self, forKey: .slug)
        description = try c.decodeIfPresent(String.self, forKey: .description)
        durationMs = try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 0
        tracksCount = try c.decodeIfPresent(Int.self, forKey: .tracksCount) ?? 0
        likesCount = try c.decodeIfPresent(Int.self, forKey: .likesCount) ?? 0
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

/// One entry of `GET /playlists/<slug>`. Unlike `Track` it has no numeric id — position within
/// the playlist and the track's page URL are all the API gives.
public struct PublicPlaylistTrack: Codable, Equatable, Sendable {
    public let position: Int
    public let title: String
    public let date: String?
    public let venue: String?
    public let location: String?
    public let durationMs: Int64
    public let url: String?
    public let mp3Url: String?
    public let waveformImageUrl: String?
    public let tags: [Tag]

    enum CodingKeys: String, CodingKey {
        case position, title, date, venue, location, url, tags
        case durationMs = "duration_ms"
        case mp3Url = "mp3_url"
        case waveformImageUrl = "waveform_image_url"
    }

    public init(
        position: Int, title: String, date: String? = nil, venue: String? = nil, location: String? = nil,
        durationMs: Int64 = 0, url: String? = nil, mp3Url: String? = nil, waveformImageUrl: String? = nil,
        tags: [Tag] = []
    ) {
        self.position = position
        self.title = title
        self.date = date
        self.venue = venue
        self.location = location
        self.durationMs = durationMs
        self.url = url
        self.mp3Url = mp3Url
        self.waveformImageUrl = waveformImageUrl
        self.tags = tags
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        position = try c.decodeIfPresent(Int.self, forKey: .position) ?? 0
        title = try c.decode(String.self, forKey: .title)
        date = try c.decodeIfPresent(String.self, forKey: .date)
        venue = try c.decodeIfPresent(String.self, forKey: .venue)
        location = try c.decodeIfPresent(String.self, forKey: .location)
        durationMs = try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 0
        url = try c.decodeIfPresent(String.self, forKey: .url)
        mp3Url = try c.decodeIfPresent(String.self, forKey: .mp3Url)
        waveformImageUrl = try c.decodeIfPresent(String.self, forKey: .waveformImageUrl)
        tags = try c.decodeIfPresent([Tag].self, forKey: .tags) ?? []
    }

    public var playable: Bool {
        guard let mp3Url else { return false }
        return !mp3Url.trimmingCharacters(in: .whitespaces).isEmpty
    }

    public func toPlayableTrack(playlistSlug: String, artURL: String?) -> PlayableTrack {
        PlayableTrack(
            // No numeric id on playlist entries; slug+position is unique within a queue.
            id: "\(playlistSlug)#\(position)",
            slug: url.flatMap { URL(string: $0)?.lastPathComponent },
            title: title,
            position: position,
            durationMs: durationMs,
            url: mp3Url ?? "",
            waveformURL: waveformImageUrl,
            showDate: date,
            venueName: venue,
            artURL: artURL,
            tags: tags
        )
    }
}

public struct PublicPlaylist: Decodable, Equatable, Sendable {
    public let name: String
    public let slug: String
    public let description: String?
    public let durationMs: Int64
    public let username: String?
    public let coverArtUrl: String?
    public let tracks: [PublicPlaylistTrack]

    enum CodingKeys: String, CodingKey {
        case name, slug, description, username, tracks
        case durationMs = "duration_ms"
        case coverArtUrl = "cover_art_url"
    }

    public init(
        name: String, slug: String, description: String? = nil, durationMs: Int64 = 0,
        username: String? = nil, coverArtUrl: String? = nil, tracks: [PublicPlaylistTrack] = []
    ) {
        self.name = name
        self.slug = slug
        self.description = description
        self.durationMs = durationMs
        self.username = username
        self.coverArtUrl = coverArtUrl
        self.tracks = tracks
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        slug = try c.decode(String.self, forKey: .slug)
        description = try c.decodeIfPresent(String.self, forKey: .description)
        durationMs = try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 0
        username = try c.decodeIfPresent(String.self, forKey: .username)
        coverArtUrl = try c.decodeIfPresent(String.self, forKey: .coverArtUrl)
        tracks = try c.decodeIfPresent([PublicPlaylistTrack].self, forKey: .tracks) ?? []
    }

    /// Wrapped in a `ShowDetail` so it flows through `Player.play(detail:)` like every other
    /// queue (same trick as a local playlist); the playlist's name and track count stand in for
    /// the artist and date. Unplayable entries are dropped so UI and queue agree on indexes (D12).
    public func toShowDetail() -> ShowDetail {
        let playable = tracks.filter(\.playable).map { $0.toPlayableTrack(playlistSlug: slug, artURL: coverArtUrl) }
        let artist = ArtistRef(backend: .phishin, id: "playlist:\(slug)", name: name)
        let summary = ShowSummary(artist: artist, date: "\(playable.count) \(playable.count == 1 ? "track" : "tracks")", artURL: coverArtUrl)
        return ShowDetail(summary: summary, tracks: playable, queueKey: playlistQueueKey(slug))
    }
}
