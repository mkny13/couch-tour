import Foundation

/// Port of Relisten.kt: DTOs, pure mapping, and the request layer for
/// [api.relisten.net](https://api.relisten.net), Relisten's public API.
///
/// Facts baked into the mapping below, verified against the live API (see the Android repo's
/// MULTI-ARTIST-PLAN.md "Verified against the live API" — do not re-derive these):
/// - Track `duration` is **seconds**; the rest of this package is milliseconds everywhere
///   else, so it's multiplied by 1000 on the way in.
/// - `sources` arrive pre-sorted by `avg_rating_weighted` descending, so the default tape is
///   just the first source. Do NOT tie-break on `is_soundboard` — that can rank below the top
///   slot and would override Relisten's own ranking.
/// - `mp3_url` is nullable; tracks without one are dropped, the same rule phish.in's D12
///   applies, so the UI and the queue builder agree on what an index means.

public struct RelistenFeatures: Codable, Equatable {
    public let sets: Bool
    public let multipleSources: Bool

    enum CodingKeys: String, CodingKey {
        case sets
        case multipleSources = "multiple_sources"
    }

    public init(sets: Bool = true, multipleSources: Bool = false) {
        self.sets = sets
        self.multipleSources = multipleSources
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sets = try c.decodeIfPresent(Bool.self, forKey: .sets) ?? true
        multipleSources = try c.decodeIfPresent(Bool.self, forKey: .multipleSources) ?? false
    }
}

public struct RelistenArtist: Codable, Equatable {
    public let uuid: String
    public let slug: String
    public let name: String
    public let showCount: Int
    public let features: RelistenFeatures

    enum CodingKeys: String, CodingKey {
        case uuid, slug, name
        case showCount = "show_count"
        case features
    }

    public init(uuid: String, slug: String, name: String, showCount: Int = 0, features: RelistenFeatures = RelistenFeatures()) {
        self.uuid = uuid
        self.slug = slug
        self.name = name
        self.showCount = showCount
        self.features = features
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uuid = try c.decode(String.self, forKey: .uuid)
        slug = try c.decode(String.self, forKey: .slug)
        name = try c.decode(String.self, forKey: .name)
        showCount = try c.decodeIfPresent(Int.self, forKey: .showCount) ?? 0
        features = try c.decodeIfPresent(RelistenFeatures.self, forKey: .features) ?? RelistenFeatures()
    }
}

/// `/v3/artists/{uuid}/years` — one entry per year (or era, for early ranged periods).
public struct RelistenYear: Codable, Equatable {
    public let uuid: String
    public let year: String
    public let showCount: Int

    enum CodingKeys: String, CodingKey {
        case uuid, year
        case showCount = "show_count"
    }

    public init(uuid: String, year: String, showCount: Int = 0) {
        self.uuid = uuid
        self.year = year
        self.showCount = showCount
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uuid = try c.decode(String.self, forKey: .uuid)
        year = try c.decode(String.self, forKey: .year)
        showCount = try c.decodeIfPresent(Int.self, forKey: .showCount) ?? 0
    }
}

public struct RelistenVenue: Codable, Equatable {
    public let name: String?
    public let location: String?

    public init(name: String? = nil, location: String? = nil) {
        self.name = name
        self.location = location
    }
}

public struct RelistenTour: Codable, Equatable {
    public let name: String?

    public init(name: String? = nil) { self.name = name }
}

/// A show as it appears in a year's `shows` list — no sources, just enough to browse.
public struct RelistenShowSummary: Codable, Equatable {
    public let displayDate: String
    public let venue: RelistenVenue?
    public let tour: RelistenTour?
    public let sourceCount: Int

    enum CodingKeys: String, CodingKey {
        case displayDate = "display_date"
        case venue, tour
        case sourceCount = "source_count"
    }

    public init(displayDate: String, venue: RelistenVenue? = nil, tour: RelistenTour? = nil, sourceCount: Int = 0) {
        self.displayDate = displayDate
        self.venue = venue
        self.tour = tour
        self.sourceCount = sourceCount
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        displayDate = try c.decode(String.self, forKey: .displayDate)
        venue = try c.decodeIfPresent(RelistenVenue.self, forKey: .venue)
        tour = try c.decodeIfPresent(RelistenTour.self, forKey: .tour)
        sourceCount = try c.decodeIfPresent(Int.self, forKey: .sourceCount) ?? 0
    }
}

/// `/v3/artists/{artistUuid}/years/{yearUuid}`
public struct RelistenYearWithShows: Codable, Equatable {
    public let year: String
    public let shows: [RelistenShowSummary]

    public init(year: String, shows: [RelistenShowSummary] = []) {
        self.year = year
        self.shows = shows
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        year = try c.decode(String.self, forKey: .year)
        shows = try c.decodeIfPresent([RelistenShowSummary].self, forKey: .shows) ?? []
    }

    enum CodingKeys: String, CodingKey { case year, shows }
}

public struct RelistenSourceTrack: Codable, Equatable {
    public let uuid: String
    public let title: String
    public let trackPosition: Int
    /// Seconds — see the file-level doc. Converted to ms in `toPlayableTrack`.
    public let duration: Int64
    public let mp3Url: String?

    enum CodingKeys: String, CodingKey {
        case uuid, title
        case trackPosition = "track_position"
        case duration
        case mp3Url = "mp3_url"
    }

    public init(uuid: String, title: String, trackPosition: Int = 0, duration: Int64 = 0, mp3Url: String? = nil) {
        self.uuid = uuid
        self.title = title
        self.trackPosition = trackPosition
        self.duration = duration
        self.mp3Url = mp3Url
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uuid = try c.decode(String.self, forKey: .uuid)
        title = try c.decode(String.self, forKey: .title)
        trackPosition = try c.decodeIfPresent(Int.self, forKey: .trackPosition) ?? 0
        duration = try c.decodeIfPresent(Int64.self, forKey: .duration) ?? 0
        mp3Url = try c.decodeIfPresent(String.self, forKey: .mp3Url)
    }
}

public struct RelistenSourceSet: Codable, Equatable {
    public let index: Int
    public let name: String
    public let isEncore: Bool
    public let tracks: [RelistenSourceTrack]

    enum CodingKeys: String, CodingKey {
        case index, name
        case isEncore = "is_encore"
        case tracks
    }

    public init(index: Int = 0, name: String = "", isEncore: Bool = false, tracks: [RelistenSourceTrack] = []) {
        self.index = index
        self.name = name
        self.isEncore = isEncore
        self.tracks = tracks
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        index = try c.decodeIfPresent(Int.self, forKey: .index) ?? 0
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        isEncore = try c.decodeIfPresent(Bool.self, forKey: .isEncore) ?? false
        tracks = try c.decodeIfPresent([RelistenSourceTrack].self, forKey: .tracks) ?? []
    }
}

/// One tape of a show — what `RecordingRef` models.
public struct RelistenSource: Codable, Equatable {
    public let uuid: String
    public let sets: [RelistenSourceSet]
    public let isSoundboard: Bool
    public let avgRatingWeighted: Double
    public let numReviews: Int
    public let taper: String?
    public let lineage: String?

    enum CodingKeys: String, CodingKey {
        case uuid, sets
        case isSoundboard = "is_soundboard"
        case avgRatingWeighted = "avg_rating_weighted"
        case numReviews = "num_reviews"
        case taper, lineage
    }

    public init(
        uuid: String, sets: [RelistenSourceSet] = [], isSoundboard: Bool = false,
        avgRatingWeighted: Double = 0, numReviews: Int = 0, taper: String? = nil, lineage: String? = nil
    ) {
        self.uuid = uuid
        self.sets = sets
        self.isSoundboard = isSoundboard
        self.avgRatingWeighted = avgRatingWeighted
        self.numReviews = numReviews
        self.taper = taper
        self.lineage = lineage
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uuid = try c.decode(String.self, forKey: .uuid)
        sets = try c.decodeIfPresent([RelistenSourceSet].self, forKey: .sets) ?? []
        isSoundboard = try c.decodeIfPresent(Bool.self, forKey: .isSoundboard) ?? false
        avgRatingWeighted = try c.decodeIfPresent(Double.self, forKey: .avgRatingWeighted) ?? 0
        numReviews = try c.decodeIfPresent(Int.self, forKey: .numReviews) ?? 0
        taper = try c.decodeIfPresent(String.self, forKey: .taper)
        lineage = try c.decodeIfPresent(String.self, forKey: .lineage)
    }
}

/// `/v2/artists/{artistIdOrSlug}/shows/{date}` — a show with every tape of it.
public struct RelistenShowWithSources: Codable, Equatable {
    public let displayDate: String
    public let venue: RelistenVenue?
    public let tour: RelistenTour?
    public let sources: [RelistenSource]

    enum CodingKeys: String, CodingKey {
        case displayDate = "display_date"
        case venue, tour, sources
    }

    public init(displayDate: String, venue: RelistenVenue? = nil, tour: RelistenTour? = nil, sources: [RelistenSource] = []) {
        self.displayDate = displayDate
        self.venue = venue
        self.tour = tour
        self.sources = sources
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        displayDate = try c.decode(String.self, forKey: .displayDate)
        venue = try c.decodeIfPresent(RelistenVenue.self, forKey: .venue)
        tour = try c.decodeIfPresent(RelistenTour.self, forKey: .tour)
        sources = try c.decodeIfPresent([RelistenSource].self, forKey: .sources) ?? []
    }
}

// ---------------------------------------------------------------- mapping

extension RelistenArtist {
    public func toArtistRef() -> ArtistRef {
        ArtistRef(backend: .relisten, id: slug, name: name, showCount: showCount, hasSets: features.sets, hasMultipleSources: features.multipleSources)
    }
}

extension RelistenYear {
    public func toPeriodRef() -> PeriodRef { PeriodRef(id: uuid, label: year, showCount: showCount) }
}

extension RelistenShowSummary {
    public func toShowSummary(artist: ArtistRef) -> ShowSummary {
        ShowSummary(
            artist: artist,
            date: displayDate,
            venue: venue?.name,
            location: venue?.location,
            tourName: tour?.name,
            recordingCount: max(sourceCount, 1)
        )
    }
}

extension RelistenSource {
    public func toRecordingRef() -> RecordingRef {
        RecordingRef(
            id: uuid,
            label: taper ?? (isSoundboard ? "Soundboard" : "Audience"),
            isSoundboard: isSoundboard,
            rating: avgRatingWeighted,
            reviewCount: numReviews,
            taper: taper,
            lineage: lineage
        )
    }
}

extension RelistenSourceTrack {
    public func toPlayableTrack(artist: ArtistRef, showDate: String, venueName: String?, setName: String) -> PlayableTrack {
        PlayableTrack(
            id: uuid,
            title: title,
            // Suppressed for an artist without real sets (verified live: Dead sources carry
            // one wrapper set literally named "Set") rather than every screen re-checking hasSets.
            setName: artist.hasSets ? setName : "",
            durationMs: duration * 1000,
            url: mp3Url ?? "",
            showDate: showDate,
            venueName: venueName
        )
    }
}

extension RelistenShowWithSources {
    /// `recordingId` nil takes the default tape — the first source, since Relisten already
    /// sorts them by rating. A non-nil id that matches nothing (a stale queue key against a
    /// tape that's since been removed) falls back to the default rather than an empty show.
    public func toShowDetail(artist: ArtistRef, recordingId: String? = nil) -> ShowDetail {
        let chosen: RelistenSource? = {
            if let recordingId, let match = sources.first(where: { $0.uuid == recordingId }) { return match }
            return sources.first
        }()

        let tracks: [PlayableTrack] = (chosen?.sets ?? [])
            .sorted { $0.index < $1.index }
            .flatMap { set in
                set.tracks
                    .filter { !($0.mp3Url ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
                    .map { $0.toPlayableTrack(artist: artist, showDate: displayDate, venueName: venue?.name, setName: set.name) }
            }

        return ShowDetail(
            summary: ShowSummary(
                artist: artist,
                date: displayDate,
                venue: venue?.name,
                location: venue?.location,
                tourName: tour?.name,
                recordingCount: max(sources.count, 1)
            ),
            recording: chosen?.toRecordingRef(),
            alternates: sources.filter { $0.uuid != chosen?.uuid }.map { $0.toRecordingRef() },
            tracks: tracks
        )
    }
}

// ------------------------------------------------------------------- requests

/// Plain reads, no key and no auth. `/v3` carries artists and years; the per-show endpoint
/// with every tape is still `/v2` — Relisten hasn't moved it.
public enum RelistenAPI {
    private static let defaultBase = URL(string: "https://api.relisten.net/api")!

    /// Overridden by tests to point at a local mock server, same pattern as PhishInAPI.
    public static var baseURL: URL = defaultBase

    private static let decoder = JSONDecoder()

    private static func path(_ segments: String...) -> URL {
        var url = baseURL
        for segment in segments { url.appendPathComponent(segment) }
        return url
    }

    private static func get(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIException("No HTTP response") }
        guard (200...299).contains(http.statusCode) else { throw APIException("HTTP \(http.statusCode)", code: http.statusCode) }
        return data
    }

    public static func artists() async throws -> [RelistenArtist] {
        try decoder.decode([RelistenArtist].self, from: try await get(path("v3", "artists")))
    }

    public static func years(artistUuid: String) async throws -> [RelistenYear] {
        try decoder.decode([RelistenYear].self, from: try await get(path("v3", "artists", artistUuid, "years")))
    }

    public static func year(artistUuid: String, yearUuid: String) async throws -> RelistenYearWithShows {
        try decoder.decode(RelistenYearWithShows.self, from: try await get(path("v3", "artists", artistUuid, "years", yearUuid)))
    }

    public static func show(artistIdOrSlug: String, date: String) async throws -> RelistenShowWithSources {
        try decoder.decode(RelistenShowWithSources.self, from: try await get(path("v2", "artists", artistIdOrSlug, "shows", date)))
    }
}

/// Wires `RelistenAPI` and the mapping above behind the `MusicSource` seam. `/v3/artists/{uuid
/// or slug}/years` and its `.../years/{yearUuid}` sibling both accept the slug directly
/// (confirmed live on Android), so `ArtistRef.id` — already the slug — needs no uuid lookup to
/// feed either call.
public actor RelistenCatalogSource: MusicSource {
    public nonisolated let backend = Backend.relisten

    public static let shared = RelistenCatalogSource()

    // The one cache the plan calls for: a ~200-entry list re-fetched on every back-navigation
    // is the one case worth it; a real catalog cache stays out of scope for the MVP.
    private var cachedArtists: [ArtistRef]?

    public init() {}

    public func artists() async throws -> [ArtistRef] {
        if let cachedArtists { return cachedArtists }
        let artists = try await RelistenAPI.artists().map { $0.toArtistRef() }
        cachedArtists = artists
        return artists
    }

    /// Test-only hook: `cachedArtists` above is private to the actor, so tests reset caching
    /// state through the shared instance rather than reaching in directly.
    public func resetCache() { cachedArtists = nil }

    public func periods(artist: ArtistRef) async throws -> [PeriodRef] {
        try await RelistenAPI.years(artistUuid: artist.id).map { $0.toPeriodRef() }
    }

    public func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary] {
        try await RelistenAPI.year(artistUuid: artist.id, yearUuid: period.id).shows.map { $0.toShowSummary(artist: artist) }
    }

    public func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail {
        try await RelistenAPI.show(artistIdOrSlug: artist.id, date: date).toShowDetail(artist: artist, recordingId: recordingId)
    }
}
