import Foundation

// Port of Api.kt. Login landed in #57; likes/playlists/search-account-gating are still out
// of scope (tracked as #58/#59). DTOs and the request layer only — the mapping into the
// backend-neutral model lives in Catalog.swift, matching the Kotlin file split.

public struct CoverArt: Codable, Equatable {
    public let large: String?
    public let medium: String?
    public let small: String?

    public init(large: String? = nil, medium: String? = nil, small: String? = nil) {
        self.large = large
        self.medium = medium
        self.small = small
    }
}

/// /years returns "periods", not plain years: most are a single year ("1997") but the early
/// ones are ranges ("1983-1987"). The two forms need different query params — see
/// `PhishInAPI.showsForPeriod`.
public struct Period: Codable, Equatable {
    public let period: String
    public let showsCount: Int
    public let showsWithAudioCount: Int
    public let era: String?
    public let coverArtUrls: CoverArt?

    enum CodingKeys: String, CodingKey {
        case period
        case showsCount = "shows_count"
        case showsWithAudioCount = "shows_with_audio_count"
        case era
        case coverArtUrls = "cover_art_urls"
    }

    public init(period: String, showsCount: Int = 0, showsWithAudioCount: Int = 0, era: String? = nil, coverArtUrls: CoverArt? = nil) {
        self.period = period
        self.showsCount = showsCount
        self.showsWithAudioCount = showsWithAudioCount
        self.era = era
        self.coverArtUrls = coverArtUrls
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        period = try c.decode(String.self, forKey: .period)
        showsCount = try c.decodeIfPresent(Int.self, forKey: .showsCount) ?? 0
        showsWithAudioCount = try c.decodeIfPresent(Int.self, forKey: .showsWithAudioCount) ?? 0
        era = try c.decodeIfPresent(String.self, forKey: .era)
        coverArtUrls = try c.decodeIfPresent(CoverArt.self, forKey: .coverArtUrls)
    }
}

public struct Venue: Codable, Equatable {
    public let name: String?
    public let location: String?

    public init(name: String? = nil, location: String? = nil) {
        self.name = name
        self.location = location
    }
}

public struct Show: Codable, Equatable {
    public let date: String
    public let venueName: String?
    public let tourName: String?
    public let audioStatus: String
    public let duration: Int64
    public let id: Int64
    public let likesCount: Int
    public let likedByUser: Bool
    public let albumCoverUrl: String?
    public let coverArtUrls: CoverArt?
    public let venue: Venue?
    public let tracks: [Track]
    public let tags: [Tag]

    public var location: String? { venue?.location }

    enum CodingKeys: String, CodingKey {
        case date
        case venueName = "venue_name"
        case tourName = "tour_name"
        case audioStatus = "audio_status"
        case duration, id
        case likesCount = "likes_count"
        case likedByUser = "liked_by_user"
        case albumCoverUrl = "album_cover_url"
        case coverArtUrls = "cover_art_urls"
        case venue, tracks, tags
    }

    public init(
        date: String, venueName: String? = nil, tourName: String? = nil, audioStatus: String = "missing",
        duration: Int64 = 0, id: Int64 = 0, likesCount: Int = 0, likedByUser: Bool = false,
        albumCoverUrl: String? = nil, coverArtUrls: CoverArt? = nil, venue: Venue? = nil, tracks: [Track] = [],
        tags: [Tag] = []
    ) {
        self.date = date
        self.venueName = venueName
        self.tourName = tourName
        self.audioStatus = audioStatus
        self.duration = duration
        self.id = id
        self.likesCount = likesCount
        self.likedByUser = likedByUser
        self.albumCoverUrl = albumCoverUrl
        self.coverArtUrls = coverArtUrls
        self.venue = venue
        self.tracks = tracks
        self.tags = tags
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        date = try c.decode(String.self, forKey: .date)
        venueName = try c.decodeIfPresent(String.self, forKey: .venueName)
        tourName = try c.decodeIfPresent(String.self, forKey: .tourName)
        audioStatus = try c.decodeIfPresent(String.self, forKey: .audioStatus) ?? "missing"
        duration = try c.decodeIfPresent(Int64.self, forKey: .duration) ?? 0
        id = try c.decodeIfPresent(Int64.self, forKey: .id) ?? 0
        likesCount = try c.decodeIfPresent(Int.self, forKey: .likesCount) ?? 0
        likedByUser = try c.decodeIfPresent(Bool.self, forKey: .likedByUser) ?? false
        albumCoverUrl = try c.decodeIfPresent(String.self, forKey: .albumCoverUrl)
        coverArtUrls = try c.decodeIfPresent(CoverArt.self, forKey: .coverArtUrls)
        venue = try c.decodeIfPresent(Venue.self, forKey: .venue)
        tracks = try c.decodeIfPresent([Track].self, forKey: .tracks) ?? []
        tags = try c.decodeIfPresent([Tag].self, forKey: .tags) ?? []
    }
}

public struct Track: Codable, Equatable, Sendable {
    public let id: Int64
    public let title: String
    public let likesCount: Int
    public let likedByUser: Bool
    public let position: Int
    /// Milliseconds.
    public let duration: Int64
    public let setName: String
    public let audioStatus: String
    public let mp3Url: String?
    public let waveformImageUrl: String?
    // Present on search results, /tracks and playlist entries; absent when nested in a show.
    public let showDate: String?
    public let venueName: String?
    public let venueLocation: String?
    public let showAlbumCoverUrl: String?
    public let tags: [Tag]

    public var playable: Bool {
        guard let url = mp3Url, !url.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        return audioStatus != "missing"
    }

    enum CodingKeys: String, CodingKey {
        case id, title
        case likesCount = "likes_count"
        case likedByUser = "liked_by_user"
        case position, duration
        case setName = "set_name"
        case audioStatus = "audio_status"
        case mp3Url = "mp3_url"
        case waveformImageUrl = "waveform_image_url"
        case showDate = "show_date"
        case venueName = "venue_name"
        case venueLocation = "venue_location"
        case showAlbumCoverUrl = "show_album_cover_url"
        case tags
    }

    public init(
        id: Int64, title: String, likesCount: Int = 0, likedByUser: Bool = false, position: Int = 0,
        duration: Int64 = 0, setName: String = "", audioStatus: String = "missing", mp3Url: String? = nil,
        waveformImageUrl: String? = nil, showDate: String? = nil, venueName: String? = nil,
        venueLocation: String? = nil, showAlbumCoverUrl: String? = nil, tags: [Tag] = []
    ) {
        self.id = id
        self.title = title
        self.likesCount = likesCount
        self.likedByUser = likedByUser
        self.position = position
        self.duration = duration
        self.setName = setName
        self.audioStatus = audioStatus
        self.mp3Url = mp3Url
        self.waveformImageUrl = waveformImageUrl
        self.showDate = showDate
        self.venueName = venueName
        self.venueLocation = venueLocation
        self.showAlbumCoverUrl = showAlbumCoverUrl
        self.tags = tags
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        title = try c.decode(String.self, forKey: .title)
        likesCount = try c.decodeIfPresent(Int.self, forKey: .likesCount) ?? 0
        likedByUser = try c.decodeIfPresent(Bool.self, forKey: .likedByUser) ?? false
        position = try c.decodeIfPresent(Int.self, forKey: .position) ?? 0
        duration = try c.decodeIfPresent(Int64.self, forKey: .duration) ?? 0
        setName = try c.decodeIfPresent(String.self, forKey: .setName) ?? ""
        audioStatus = try c.decodeIfPresent(String.self, forKey: .audioStatus) ?? "missing"
        mp3Url = try c.decodeIfPresent(String.self, forKey: .mp3Url)
        waveformImageUrl = try c.decodeIfPresent(String.self, forKey: .waveformImageUrl)
        showDate = try c.decodeIfPresent(String.self, forKey: .showDate)
        venueName = try c.decodeIfPresent(String.self, forKey: .venueName)
        venueLocation = try c.decodeIfPresent(String.self, forKey: .venueLocation)
        showAlbumCoverUrl = try c.decodeIfPresent(String.self, forKey: .showAlbumCoverUrl)
        tags = try c.decodeIfPresent([Tag].self, forKey: .tags) ?? []
    }
}

// -------------------------------------------------------------------- search

/// Port of Api.kt's `SearchResults`. phish.in's search response also carries `songs`/
/// `venues`/`tags`/`playlists` — Android ignores all four (no song/venue browse endpoint on
/// this backend, and this MVP has no playlists screen, D5) and so does this: they're simply
/// omitted from `CodingKeys`, and Swift ignores unknown keys by default.
public struct SearchResults: Decodable, Equatable {
    public let exactShow: Show?
    public let otherShows: [Show]
    public let tracks: [Track]

    enum CodingKeys: String, CodingKey {
        case exactShow = "exact_show"
        case otherShows = "other_shows"
        case tracks
    }

    public init(exactShow: Show? = nil, otherShows: [Show] = [], tracks: [Track] = []) {
        self.exactShow = exactShow
        self.otherShows = otherShows
        self.tracks = tracks
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        exactShow = try c.decodeIfPresent(Show.self, forKey: .exactShow)
        otherShows = try c.decodeIfPresent([Show].self, forKey: .otherShows) ?? []
        tracks = try c.decodeIfPresent([Track].self, forKey: .tracks) ?? []
    }

    public var shows: [Show] { [exactShow].compactMap { $0 } + otherShows }
}

extension SearchResults {
    public func toSearchHits() -> SearchHits {
        SearchHits(shows: shows.map { $0.toShowSummary() }, tracks: tracks)
    }
}

private struct ShowsPage: Decodable {
    let shows: [Show]

    enum CodingKeys: String, CodingKey {
        case shows
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        shows = try c.decodeIfPresent([Show].self, forKey: .shows) ?? []
    }
}

// -------------------------------------------------------------------- auth (#57)

public struct LoginResponse: Decodable, Equatable {
    public let jwt: String
    public let username: String
    public let email: String

    public init(jwt: String, username: String, email: String) {
        self.jwt = jwt
        self.username = username
        self.email = email
    }
}

private struct LoginRequest: Encodable {
    let email: String
    let password: String
}

// -------------------------------------------------------------------- likes (#58)

/// Matches the API's `likable_type` values.
public enum Likable: String, Encodable {
    case show = "Show"
    case track = "Track"
    case playlist = "Playlist"
}

private struct LikeRequest: Encodable {
    let likableType: String
    let likableId: Int64

    enum CodingKeys: String, CodingKey {
        case likableType = "likable_type"
        case likableId = "likable_id"
    }
}

public struct APIException: Error {
    public let message: String
    public let code: Int

    public init(_ message: String, code: Int = 0) {
        self.message = message
        self.code = code
    }

    public var unauthorized: Bool { code == 401 }
}

/// Matches `PhishInApi`'s shape: an overridable `baseURL` for tests, no third-party HTTP
/// framework.
public enum PhishInAPI {
    private static let defaultBase = URL(string: "https://phish.in/api/v2")!

    /// Overridden by tests to point at a local mock server.
    public static var baseURL: URL = defaultBase

    /// Set by `PhishInSession` once a login (or a restored one) is active. Attached as
    /// `X-Auth-Token` on every request — deliberately not `Authorization: Bearer`, which the
    /// API also accepts but treats identically to a wrong/expired token: both produce an
    /// indistinguishable 401, the same footgun `Api.kt`'s own comment warns about.
    public static var authToken: String?

    /// Fired when a request that carried `authToken` comes back 401. Gated on the request
    /// having actually carried a token so a bad-password 401 from `login` itself — which never
    /// attaches one — surfaces as a login error instead of silently logging out. Async and
    /// awaited so `PhishInSession.logout()` (MainActor-isolated) has finished before this
    /// request's caller sees the resulting error, rather than racing a detached `Task`.
    public static var onUnauthorized: (() async -> Void)?

    private static let decoder: JSONDecoder = JSONDecoder()
    private static let encoder: JSONEncoder = JSONEncoder()

    private static func send(_ request: URLRequest, authenticated: Bool = true) async throws -> Data {
        var request = request
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let carriedToken = authenticated ? authToken : nil
        if let carriedToken { request.setValue(carriedToken, forHTTPHeaderField: "X-Auth-Token") }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIException("No HTTP response") }
        if http.statusCode == 401 && carriedToken != nil { await onUnauthorized?() }
        guard (200...299).contains(http.statusCode) else { throw APIException("HTTP \(http.statusCode)", code: http.statusCode) }
        return data
    }

    private static func get(_ url: URL) async throws -> Data {
        try await send(URLRequest(url: url))
    }

    private static func post<Body: Encodable>(_ url: URL, body: Body, authenticated: Bool = true) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await send(request, authenticated: authenticated)
    }

    private static func delete(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        return try await send(request)
    }

    private static func path(_ segments: String...) -> URLComponents {
        var url = baseURL
        for segment in segments { url.appendPathComponent(segment) }
        return URLComponents(url: url, resolvingAgainstBaseURL: false)!
    }

    public static func years() async throws -> [Period] {
        let url = path("years").url!
        let periods = try decoder.decode([Period].self, from: try await get(url))
        return periods.filter { $0.showsWithAudioCount > 0 }
    }

    /// A period is either "1997" or "1983-1987"; the API wants `year=` for the former and
    /// `year_range=` for the latter. Passing a range to `year=` silently returns nothing (D11).
    public static func showsForPeriod(_ period: String) async throws -> [Show] {
        var components = path("shows")
        let yearParam = period.contains("-") ? "year_range" : "year"
        components.queryItems = [
            URLQueryItem(name: yearParam, value: period),
            URLQueryItem(name: "audio_status", value: "complete_or_partial"),
            URLQueryItem(name: "sort", value: "date:asc"),
            URLQueryItem(name: "per_page", value: "1000"),
        ]
        let page = try decoder.decode(ShowsPage.self, from: try await get(components.url!))
        return page.shows
    }

    public static func show(_ date: String) async throws -> Show {
        let url = path("shows", date).url!
        return try decoder.decode(Show.self, from: try await get(url))
    }

    /// The API rejects terms shorter than 3 characters. `term` goes in the path, not a query
    /// param — and `path(_:)`'s `URL.appendPathComponent` treats "/" as a separator, so a raw
    /// slash would silently become an extra path segment and 404. Percent-encoded by hand,
    /// keeping "/" escaped rather than treated as one, instead of routing through `path(_:)`.
    public static func search(_ term: String) async throws -> SearchResults {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))
        let encodedTerm = term.addingPercentEncoding(withAllowedCharacters: allowed) ?? term
        components.percentEncodedPath += "/search/" + encodedTerm
        components.queryItems = [URLQueryItem(name: "audio_status", value: "complete_or_partial")]
        return try decoder.decode(SearchResults.self, from: try await get(components.url!))
    }

    public static func login(email: String, password: String) async throws -> LoginResponse {
        let url = path("auth", "login").url!
        let data = try await post(url, body: LoginRequest(email: email, password: password), authenticated: false)
        return try decoder.decode(LoginResponse.self, from: data)
    }

    /// Requires auth; unauthenticated the API rejects the request rather than silently
    /// no-op'ing, same as Android's `like`/`unlike`.
    public static func like(_ type: Likable, _ id: Int64) async throws {
        let url = path("likes").url!
        _ = try await post(url, body: LikeRequest(likableType: type.rawValue, likableId: id))
    }

    public static func unlike(_ type: Likable, _ id: Int64) async throws {
        var components = path("likes")
        components.queryItems = [
            URLQueryItem(name: "likable_type", value: type.rawValue),
            URLQueryItem(name: "likable_id", value: String(id)),
        ]
        _ = try await delete(components.url!)
    }
}
