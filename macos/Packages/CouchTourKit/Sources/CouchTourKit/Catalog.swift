import Foundation

// Port of Catalog.kt: the backend-neutral catalog, just enough of a model to browse to a
// playable track. The mapping lives in pure functions, tested without a network call, same
// reasoning as the Kotlin original (D36).
//
// The desktop MVP has no login/likes/playlists (D5 of this plan), so unlike the Android
// original this file's neutral model is the *entire* domain layer, not just the browse slice.
// Search was added in D169 — it rides the same browse seam as everything else (see
// `SearchHits` below) rather than needing new destination screens.

public enum Backend: String, CaseIterable, Hashable, Sendable {
    case phishin
    case relisten

    /// Nil for anything unrecognised — these ids travel in nav routes and, eventually, in
    /// shared progress rows.
    public static func from(_ id: String) -> Backend? {
        Backend.allCases.first { $0.rawValue == id }
    }
}

public struct ArtistRef: Hashable, Sendable, Identifiable {
    public let backend: Backend
    /// phish.in has one artist; on Relisten this is the artist's slug, e.g. "grateful-dead".
    public let id: String
    public let name: String
    public let showCount: Int
    /// Relisten's `features.sets`. False means sources carry one wrapper set named "Set", so
    /// a set-name divider would be a meaningless one. Always true for phish.in.
    public let hasSets: Bool
    /// Relisten's `features.multiple_sources`. False means there is no tape to switch.
    public let hasMultipleSources: Bool

    public init(backend: Backend, id: String, name: String, showCount: Int = 0, hasSets: Bool = true, hasMultipleSources: Bool = false) {
        self.backend = backend
        self.id = id
        self.name = name
        self.showCount = showCount
        self.hasSets = hasSets
        self.hasMultipleSources = hasMultipleSources
    }

    /// Stable cross-backend identity for anything that needs to store a reference to an
    /// artist rather than the whole struct — favoriting, eventually sync. Port of
    /// `Catalog.kt`'s `ArtistRef.key`.
    public var key: String { "\(backend.rawValue):\(id)" }
}

/// A browsable slice of an artist's catalog. On phish.in this is a period and may be a range
/// ("1983-1987"), which is why `id` is carried verbatim — `showsForPeriod` needs it back to
/// pick `year_range=` over `year=` (D11). On Relisten it is a year's uuid.
public struct PeriodRef: Hashable, Sendable, Identifiable {
    public let id: String
    public let label: String
    public let showCount: Int
    public let artURL: String?

    public init(id: String, label: String, showCount: Int = 0, artURL: String? = nil) {
        self.id = id
        self.label = label
        self.showCount = showCount
        self.artURL = artURL
    }
}

public struct Tag: Codable, Hashable, Sendable, Identifiable {
    public let name: String
    public let description: String?
    public let color: String?
    public let priority: Int
    public let notes: String?

    public var id: String { name }

    enum CodingKeys: String, CodingKey {
        case name
        case description
        case color
        case priority
        case notes
    }

    public init(name: String, description: String? = nil, color: String? = nil, priority: Int = 0, notes: String? = nil) {
        self.name = name
        self.description = description
        self.color = color
        self.priority = priority
        self.notes = notes
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        description = try c.decodeIfPresent(String.self, forKey: .description)
        color = try c.decodeIfPresent(String.self, forKey: .color)
        priority = try c.decodeIfPresent(Int.self, forKey: .priority) ?? 0
        notes = try c.decodeIfPresent(String.self, forKey: .notes)
    }
}

public struct WindowPopularity: Codable, Hashable, Sendable {
    public let plays: Int
    public let hours: Double
    public let hotScore: Double

    enum CodingKeys: String, CodingKey {
        case plays
        case hours
        case hotScore = "hot_score"
    }

    public init(plays: Int = 0, hours: Double = 0.0, hotScore: Double = 0.0) {
        self.plays = plays
        self.hours = hours
        self.hotScore = hotScore
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        plays = try c.decodeIfPresent(Int.self, forKey: .plays) ?? 0
        hours = try c.decodeIfPresent(Double.self, forKey: .hours) ?? 0.0
        hotScore = try c.decodeIfPresent(Double.self, forKey: .hotScore) ?? 0.0
    }
}

public typealias RelistenPopularityWindow = WindowPopularity

public struct RelistenPopularity: Codable, Hashable, Sendable {
    public let momentumScore: Double
    public let trendRatio: Double
    public let windows: [String: WindowPopularity]

    enum CodingKeys: String, CodingKey {
        case momentumScore = "momentum_score"
        case trendRatio = "trend_ratio"
        case windows
    }

    public init(momentumScore: Double = 0.0, trendRatio: Double = 0.0, windows: [String: WindowPopularity] = [:]) {
        self.momentumScore = momentumScore
        self.trendRatio = trendRatio
        self.windows = windows
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        momentumScore = try c.decodeIfPresent(Double.self, forKey: .momentumScore) ?? 0.0
        trendRatio = try c.decodeIfPresent(Double.self, forKey: .trendRatio) ?? 0.0
        windows = try c.decodeIfPresent([String: WindowPopularity].self, forKey: .windows) ?? [:]
    }

    public var hotScore48h: Double { windows["48h"]?.hotScore ?? 0.0 }
    public var hotScore7d: Double { windows["7d"]?.hotScore ?? 0.0 }
    public var hotScore30d: Double { windows["30d"]?.hotScore ?? 0.0 }
    public var plays48h: Int { windows["48h"]?.plays ?? 0 }
    public var plays7d: Int { windows["7d"]?.plays ?? 0 }
    public var plays30d: Int { windows["30d"]?.plays ?? 0 }
}

public enum ShowSortOption: String, CaseIterable, Identifiable, Sendable {
    case dateDesc
    case dateAsc
    case ratingDesc
    case trending48h
    case hot7d
    case popular30d
    case momentum

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .dateDesc: return "Date (Newest First)"
        case .dateAsc: return "Date (Oldest First)"
        case .ratingDesc: return "Top Rated"
        case .trending48h: return "Trending (48h)"
        case .hot7d: return "Hot (7d)"
        case .popular30d: return "Popular (30d)"
        case .momentum: return "Momentum"
        }
    }
}

public func sortShows(_ shows: [ShowSummary], by option: ShowSortOption) -> [ShowSummary] {
    switch option {
    case .dateDesc:
        return shows.sorted { lhs, rhs in
            lhs.date > rhs.date
        }
    case .dateAsc:
        return shows.sorted { lhs, rhs in
            lhs.date < rhs.date
        }
    case .ratingDesc:
        return shows.sorted { lhs, rhs in
            if lhs.rating != rhs.rating {
                return lhs.rating > rhs.rating
            }
            return lhs.date > rhs.date
        }
    case .trending48h:
        return shows.sorted { lhs, rhs in
            let lhsScore = lhs.hotScore48h
            let rhsScore = rhs.hotScore48h
            if lhsScore != rhsScore {
                return lhsScore > rhsScore
            }
            if lhs.momentumScore != rhs.momentumScore {
                return lhs.momentumScore > rhs.momentumScore
            }
            return lhs.date > rhs.date
        }
    case .hot7d:
        return shows.sorted { lhs, rhs in
            let lhsScore = lhs.hotScore7d
            let rhsScore = rhs.hotScore7d
            if lhsScore != rhsScore {
                return lhsScore > rhsScore
            }
            if lhs.momentumScore != rhs.momentumScore {
                return lhs.momentumScore > rhs.momentumScore
            }
            return lhs.date > rhs.date
        }
    case .popular30d:
        return shows.sorted { lhs, rhs in
            let lhsScore = lhs.hotScore30d
            let rhsScore = rhs.hotScore30d
            if lhsScore != rhsScore {
                return lhsScore > rhsScore
            }
            if lhs.momentumScore != rhs.momentumScore {
                return lhs.momentumScore > rhs.momentumScore
            }
            return lhs.date > rhs.date
        }
    case .momentum:
        return shows.sorted { lhs, rhs in
            if lhs.momentumScore != rhs.momentumScore {
                return lhs.momentumScore > rhs.momentumScore
            }
            if lhs.trendRatio != rhs.trendRatio {
                return lhs.trendRatio > rhs.trendRatio
            }
            return lhs.date > rhs.date
        }
    }
}

public func filterShowsByTag(_ shows: [ShowSummary], tagName: String) -> [ShowSummary] {
    let trimmed = tagName.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty || trimmed.caseInsensitiveCompare("All") == .orderedSame {
        return shows
    }
    return shows.filter { show in
        show.tags.contains { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }
    }
}

public func filterTracksByTag(_ tracks: [PlayableTrack], tagName: String) -> [PlayableTrack] {
    let trimmed = tagName.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty || trimmed.caseInsensitiveCompare("All") == .orderedSame {
        return tracks
    }
    return tracks.filter { track in
        track.tags.contains { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }
    }
}

extension Array where Element == ShowSummary {
    public func filterByTag(_ tagName: String) -> [ShowSummary] {
        filterShowsByTag(self, tagName: tagName)
    }

    public func sorted(by option: ShowSortOption) -> [ShowSummary] {
        sortShows(self, by: option)
    }
}

extension Array where Element == PlayableTrack {
    public func filterByTag(_ tagName: String) -> [PlayableTrack] {
        filterTracksByTag(self, tagName: tagName)
    }
}

/// Search results' sort control (#91). `.relevance` is the order the backends already
/// returned (exact show first, etc.) — it exists so a sort menu can offer "clear the sort"
/// rather than forcing one of the other three at all times. Port of `Catalog.kt`'s
/// `SearchSortMode`.
public enum SearchSortMode: String, CaseIterable, Identifiable, Sendable {
    case relevance
    case dateDesc
    case dateAsc
    case mostLiked

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .relevance: return "Relevance"
        case .dateDesc: return "Newest"
        case .dateAsc: return "Oldest"
        case .mostLiked: return "Most liked"
        }
    }
}

extension Array where Element == ShowSummary {
    /// Named `sortedForSearch` rather than overloading `sorted(by:)` a second time — with
    /// `ShowSortOption` also carrying a `.dateAsc` case, `.dateAsc` shorthand at a call site
    /// becomes ambiguous between the two enums otherwise (Swift picks the overload by argument
    /// type, but can't do that until the shorthand itself resolves). Same shape as `Catalog.kt`
    /// needing `@JvmName` for the same two-enums-one-case-name collision.
    ///
    /// Relisten shows default `likesCount` to 0 (see `ShowSummary`), so they settle after
    /// every phish.in hit under `.mostLiked` with no branch needed for the mixed-backend case.
    public func sortedForSearch(by mode: SearchSortMode) -> [ShowSummary] {
        switch mode {
        case .relevance: return self
        case .dateDesc: return sorted { $0.date > $1.date }
        case .dateAsc: return sorted { $0.date < $1.date }
        case .mostLiked:
            return sorted { lhs, rhs in
                lhs.likesCount != rhs.likesCount ? lhs.likesCount > rhs.likesCount : lhs.date > rhs.date
            }
        }
    }
}

extension Array where Element == Track {
    public func sortedForSearch(by mode: SearchSortMode) -> [Track] {
        switch mode {
        case .relevance: return self
        case .dateDesc: return sorted { ($0.showDate ?? "") > ($1.showDate ?? "") }
        case .dateAsc: return sorted { ($0.showDate ?? "") < ($1.showDate ?? "") }
        case .mostLiked:
            return sorted { lhs, rhs in
                lhs.likesCount != rhs.likesCount
                    ? lhs.likesCount > rhs.likesCount
                    : (lhs.showDate ?? "") > (rhs.showDate ?? "")
            }
        }
    }
}

public struct ShowSummary: Hashable, Sendable {
    public let artist: ArtistRef
    public let date: String
    public let venue: String?
    public let location: String?
    public let tourName: String?
    public let artURL: String?
    /// Some of the audio is missing. phish.in's `audio_status`; Relisten has no analogue.
    public let partial: Bool
    public let recordingCount: Int
    public let rating: Double
    public let tags: [Tag]
    public let popularity: RelistenPopularity?
    /// phish.in's `Show.likesCount` (#91's search sort) — Relisten has no equivalent and
    /// leaves this at the default 0, which is what lets `SearchSortMode.mostLiked` sort
    /// Relisten hits after every phish.in one with no special-cased branch.
    public let likesCount: Int

    public init(
        artist: ArtistRef,
        date: String,
        venue: String? = nil,
        location: String? = nil,
        tourName: String? = nil,
        artURL: String? = nil,
        partial: Bool = false,
        recordingCount: Int = 1,
        rating: Double = 0.0,
        tags: [Tag] = [],
        popularity: RelistenPopularity? = nil,
        likesCount: Int = 0
    ) {
        self.artist = artist
        self.date = date
        self.venue = venue
        self.location = location
        self.tourName = tourName
        self.artURL = artURL
        self.partial = partial
        self.recordingCount = recordingCount
        self.rating = rating
        self.tags = tags
        self.popularity = popularity
        self.likesCount = likesCount
    }

    /// "McNichols Arena · Denver, CO"
    public var where_: String {
        [venue, location].compactMap { $0 }.joined(separator: " · ")
    }

    public var momentumScore: Double { popularity?.momentumScore ?? 0.0 }
    public var trendRatio: Double { popularity?.trendRatio ?? 0.0 }
    public var hotScore48h: Double { popularity?.hotScore48h ?? 0.0 }
    public var hotScore7d: Double { popularity?.hotScore7d ?? 0.0 }
    public var hotScore30d: Double { popularity?.hotScore30d ?? 0.0 }
}

/// Where a click on the player bar's (or Now Playing inspector's) identity block should land.
/// Both macOS surfaces render the same track/date/artist text outside of any browse
/// `NavigationStack` (#99), so `AppModel.navigate(to:)` uses this to tell the Artists section
/// what to push.
public enum PlayerBarDestination: Hashable, Sendable {
    case show(ShowSummary)
    case artist(ArtistRef)
}

/// One tape of one show.
///
/// This is the concept phish.in doesn't have. Relisten carries around nine recordings of an
/// average Grateful Dead show — different tapers, different lineage, different soundboard or
/// audience provenance — and each splits the music into its own tracks. That last part is why
/// a recording is part of a queue key and not a display detail.
public struct RecordingRef: Hashable, Sendable {
    public let id: String
    public let label: String
    public let isSoundboard: Bool
    public let hasFlac: Bool
    public let rating: Double
    public let reviewCount: Int
    public let taper: String?
    public let lineage: String?

    public init(id: String, label: String, isSoundboard: Bool = false, hasFlac: Bool = false, rating: Double = 0, reviewCount: Int = 0, taper: String? = nil, lineage: String? = nil) {
        self.id = id
        self.label = label
        self.isSoundboard = isSoundboard
        self.hasFlac = hasFlac
        self.rating = rating
        self.reviewCount = reviewCount
        self.taper = taper
        self.lineage = lineage
    }

    /// Relisten has no structured "matrix" flag — only `isSoundboard`. Where a matrix mix
    /// exists at all, it's folded into free text on `taper` or `lineage` (e.g. "SBD/AUD
    /// Matrix"). This is a heuristic substring match on that text, not a guaranteed signal:
    /// it can miss a matrix worded some other way, or flag something that mentions "matrix"
    /// for an unrelated reason. Treat it as a hint in the UI, not a fact.
    public var looksLikeMatrix: Bool {
        [taper, lineage].compactMap { $0 }.contains { $0.range(of: "matrix", options: .caseInsensitive) != nil }
    }

    public var tags: [Tag] {
        var result: [Tag] = []
        if isSoundboard {
            result.append(Tag(name: "SBD", description: "Soundboard recording", priority: 10))
        }
        if looksLikeMatrix {
            result.append(Tag(name: "Matrix", description: "Matrix recording (SBD + AUD)", priority: 8))
        }
        if hasFlac {
            result.append(Tag(name: "FLAC", description: "Lossless FLAC audio", priority: 5))
        }
        return result
    }
}

public struct PlayableTrack: Equatable, Sendable {
    public let id: String
    public let title: String
    public let setName: String
    /// Milliseconds. Relisten reports seconds and is converted on the way in.
    public let durationMs: Int64
    public let url: String
    public let waveformURL: String?
    /// The show this track was played at — the album an external scrobbler reads (D50).
    public let showDate: String?
    public let venueName: String?
    public let artURL: String?
    public let flacUrl: String?
    /// phish.in only (#58) — 0/false for Relisten, which has no server-side like concept;
    /// Relisten likes are tracked locally by id instead, see `LikedTracks`.
    public let likesCount: Int
    public let likedByUser: Bool
    public let tags: [Tag]
    public let position: Int
    public let popularity: RelistenPopularity?

    public init(
        id: String,
        title: String,
        setName: String = "",
        position: Int = 0,
        durationMs: Int64 = 0,
        url: String,
        waveformURL: String? = nil,
        showDate: String? = nil,
        venueName: String? = nil,
        artURL: String? = nil,
        flacUrl: String? = nil,
        likesCount: Int = 0,
        likedByUser: Bool = false,
        tags: [Tag] = [],
        popularity: RelistenPopularity? = nil
    ) {
        self.id = id
        self.title = title
        self.setName = setName
        self.position = position
        self.durationMs = durationMs
        self.url = url
        self.waveformURL = waveformURL
        self.showDate = showDate
        self.venueName = venueName
        self.artURL = artURL
        self.flacUrl = flacUrl
        self.likesCount = likesCount
        self.likedByUser = likedByUser
        self.tags = tags
        self.popularity = popularity
    }
}

public struct ShowDetail: Equatable, Sendable {
    public let summary: ShowSummary
    public let recording: RecordingRef?
    public let alternates: [RecordingRef]
    public let tracks: [PlayableTrack]
    public let tags: [Tag]
    public let popularity: RelistenPopularity?
    /// Set only for a local playlist queue (#59), which spans arbitrary shows so the derived
    /// key below doesn't apply — the caller passes `localPlaylistQueueKey(id)` in directly.
    private let explicitQueueKey: String?

    public init(
        summary: ShowSummary,
        recording: RecordingRef? = nil,
        alternates: [RecordingRef] = [],
        tracks: [PlayableTrack] = [],
        tags: [Tag]? = nil,
        popularity: RelistenPopularity? = nil,
        queueKey: String? = nil
    ) {
        self.summary = summary
        self.recording = recording
        self.alternates = alternates
        self.tracks = tracks
        self.tags = tags ?? summary.tags
        self.popularity = popularity ?? summary.popularity
        self.explicitQueueKey = queueKey
    }

    /// Where this queue's progress is stored, or nil if it isn't resumable.
    ///
    /// A phish.in show keys itself exactly as it always has, matching Android's `show:<date>`
    /// key so a future shared `progress` table needs no migration on either side. A Relisten
    /// show without a chosen tape has no key at all rather than a broken one — recording
    /// nothing beats recording a position that parses back to nothing, the same call shuffle
    /// makes (D42).
    public var queueKey: String? {
        if let explicitQueueKey { return explicitQueueKey }
        switch summary.artist.backend {
        case .phishin:
            return showQueueKey(summary.date)
        case .relisten:
            guard let recording else { return nil }
            return recordingQueueKey(summary.artist.id, summary.date, recording.id)
        }
    }
}

public func recordingShowKey(_ artistSlug: String, _ date: String) -> String {
    "relisten:\(artistSlug)/\(date)"
}

// ------------------------------------------------------------------- search

/// What a song or venue hit resolves to: a named slice of one artist's catalog.
public enum SliceKind: Hashable, Sendable {
    case song
    case venue

    public var heading: String {
        switch self {
        case .song: return "Songs"
        case .venue: return "Venues"
        }
    }
}

public struct SliceHit: Hashable, Sendable {
    public let kind: SliceKind
    public let artist: ArtistRef
    public let period: PeriodRef

    public init(kind: SliceKind, artist: ArtistRef, period: PeriodRef) {
        self.kind = kind
        self.artist = artist
        self.period = period
    }
}

/// The merged result of searching every backend. Kept flat (not grouped by artist) so the UI
/// can render one section per type; `artistsPresent` and `filteredTo` are what let it narrow
/// to one artist without a second fetch.
///
/// No `playlists` bucket, unlike the Android original — the desktop MVP has no login/likes/
/// playlists (D5), so there is no playlists screen for a hit to land on.
public struct SearchHits: Equatable, Sendable {
    public let artists: [ArtistRef]
    public let shows: [ShowSummary]
    public let slices: [SliceHit]
    /// phish.in only, raw DTOs on purpose: opening a hit navigates to its show
    /// (`ShowDetailView`), an account feature Relisten has no analogue for.
    public let tracks: [Track]
    /// Backends whose search failed, so partial results can say so instead of reading as
    /// "nothing matched".
    public let failed: Set<Backend>

    public init(
        artists: [ArtistRef] = [], shows: [ShowSummary] = [], slices: [SliceHit] = [],
        tracks: [Track] = [], failed: Set<Backend> = []
    ) {
        self.artists = artists
        self.shows = shows
        self.slices = slices
        self.tracks = tracks
        self.failed = failed
    }

    public var isEmpty: Bool {
        artists.isEmpty && shows.isEmpty && slices.isEmpty && tracks.isEmpty
    }

    public static func + (lhs: SearchHits, rhs: SearchHits) -> SearchHits {
        SearchHits(
            artists: lhs.artists + rhs.artists,
            shows: lhs.shows + rhs.shows,
            slices: lhs.slices + rhs.slices,
            tracks: lhs.tracks + rhs.tracks,
            failed: lhs.failed.union(rhs.failed)
        )
    }

    /// The chip row's contents — every backend+artist that produced at least one hit.
    public var artistsPresent: [ArtistRef] {
        var seen = Set<String>()
        var result: [ArtistRef] = []
        for artist in artists + shows.map(\.artist) + slices.map(\.artist) + tracks.map({ _ in PHISH }) {
            let key = "\(artist.backend.rawValue)/\(artist.id)"
            if seen.insert(key).inserted { result.append(artist) }
        }
        return result
    }

    /// Narrows to one artist's hits, or returns everything for a nil `key`. phish.in's tracks
    /// are Phish's alone, so they drop out for any other artist.
    public func filteredTo(_ key: ArtistRef?) -> SearchHits {
        guard let key else { return self }
        return SearchHits(
            artists: artists.filter { $0.backend == key.backend && $0.id == key.id },
            shows: shows.filter { $0.artist.backend == key.backend && $0.artist.id == key.id },
            slices: slices.filter { $0.artist.backend == key.backend && $0.artist.id == key.id },
            tracks: (key.backend == .phishin && key.id == PHISH.id) ? tracks : [],
            failed: failed
        )
    }
}

public protocol MusicSource {
    var backend: Backend { get }

    func artists() async throws -> [ArtistRef]
    func periods(artist: ArtistRef) async throws -> [PeriodRef]
    func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary]

    /// `recordingId` nil takes the source's own default — the best tape, where there's a choice.
    func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail

    /// `term` is at least 3 characters — both APIs return nothing below that. A backend with
    /// nothing to offer returns empty hits rather than throwing.
    func search(term: String) async throws -> SearchHits
}

/// Shared by the browse UI and (eventually) any background prefetch — one seam, matching the
/// Android original's use by both MainActivity's screens and PlaybackService's Auto browse tree.
public func sourceFor(_ backend: Backend) -> MusicSource {
    switch backend {
    case .phishin: return PhishInSource()
    case .relisten: return RelistenCatalogSource.shared
    }
}

/// Fans out a search across every backend; one backend's failure doesn't cost the rest. Each
/// child task builds its own source from `backend` rather than capturing a `MusicSource`
/// existential across the concurrency boundary.
public func searchAll(_ term: String) async -> SearchHits {
    await withTaskGroup(of: SearchHits.self) { group in
        for backend in Backend.allCases {
            group.addTask {
                do {
                    return try await sourceFor(backend).search(term: term)
                } catch {
                    return SearchHits(failed: [backend])
                }
            }
        }
        var result = SearchHits()
        for await hits in group {
            result = result + hits
        }
        return result
    }
}

// ------------------------------------------------------------------- phish.in

/// phish.in is a single-artist archive, so its artist is a constant rather than a fetch.
public let PHISH = ArtistRef(backend: .phishin, id: "phish", name: "Phish")

/// Orders the merged artist list: Phish always first — it is the only artist with an
/// account, likes, and playlists behind it, so favoriting never displaces it — then
/// favorited artists by show count descending, then everyone else by show count descending.
/// Relisten separately archives Phish too, under its own taper-community show count, so its
/// copy is dropped rather than shown as a second, confusing "Phish". Port of `Catalog.kt`'s
/// `mergeArtists` (Android's #14/#56).
public func mergeArtists(relistenArtists: [ArtistRef], favorites: Set<String>) -> [ArtistRef] {
    let groups = groupArtistsForBrowse(relistenArtists: relistenArtists, favorites: favorites)
    return [groups.phish].compactMap { $0 }
        + groups.favorited.sorted { $0.showCount > $1.showCount }
        + groups.others.sorted { $0.showCount > $1.showCount }
}

/// Artist list sort control (#116). Port of `Catalog.kt`'s `ArtistSortMode`.
public enum ArtistSortMode: String, CaseIterable, Identifiable, Sendable {
    case popular
    case alphabetical

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .popular: return "Most shows"
        case .alphabetical: return "A–Z"
        }
    }
}

extension Array where Element == ArtistRef {
    public func sorted(by mode: ArtistSortMode) -> [ArtistRef] {
        switch mode {
        case .popular: return sorted { $0.showCount > $1.showCount }
        case .alphabetical: return sorted { $0.name.lowercased() < $1.name.lowercased() }
        }
    }

    public func filterByName(_ query: String) -> [ArtistRef] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return self }
        return filter { $0.name.range(of: trimmed, options: .caseInsensitive) != nil }
    }
}

/// The same phish-pinned / favorited / everyone-else split `mergeArtists` flattens, kept
/// apart so `ArtistsView` can render favorites as their own pinned section (#116) and re-sort
/// or filter each group independently while Phish — whose position-1 slot predates favoriting
/// and isn't earned by being liked, see `mergeArtists`'s doc — stays outside either section.
/// Port of `Catalog.kt`'s `ArtistGroups`.
public struct ArtistGroups: Equatable, Sendable {
    public let phish: ArtistRef?
    public let favorited: [ArtistRef]
    public let others: [ArtistRef]

    public init(phish: ArtistRef?, favorited: [ArtistRef], others: [ArtistRef]) {
        self.phish = phish
        self.favorited = favorited
        self.others = others
    }
}

public func groupArtistsForBrowse(relistenArtists: [ArtistRef], favorites: Set<String>) -> ArtistGroups {
    let rest = relistenArtists.filter { $0.name.caseInsensitiveCompare(PHISH.name) != .orderedSame }
    let (favorited, others) = rest.reduce(into: ([ArtistRef](), [ArtistRef]())) { acc, artist in
        if favorites.contains(artist.key) {
            acc.0.append(artist)
        } else {
            acc.1.append(artist)
        }
    }
    return ArtistGroups(phish: PHISH, favorited: favorited, others: others)
}

/// Adapts `PhishInAPI` to the seam. Deliberately thin: it reuses the client untouched,
/// including the period/year-range branch and the audio filtering, so nothing about the Phish
/// path changes and its tests pin the exact same traps DECISIONS.md already paid for.
///
/// A `struct` rather than an actor like `RelistenCatalogSource`, so a shared cache (#61) can't
/// live on `self` — `sourceFor` builds a fresh `PhishInSource()` on every call, and any
/// per-instance state would be discarded with it. `PhishInCatalogCache.shared` is the actor
/// that actually holds the state; this struct just delegates to it.
public struct PhishInSource: MusicSource {
    public let backend = Backend.phishin

    public init() {}

    public func artists() async throws -> [ArtistRef] { [PHISH] }

    public func periods(artist: ArtistRef) async throws -> [PeriodRef] {
        if let cached = await PhishInCatalogCache.shared.periods.get(PhishInCatalogCache.periodsKey) { return cached }
        let periods = try await PhishInAPI.years().map { $0.toPeriodRef() }
        await PhishInCatalogCache.shared.periods.put(PhishInCatalogCache.periodsKey, periods)
        return periods
    }

    public func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary] {
        if let cached = await PhishInCatalogCache.shared.shows.get(period.id) { return cached }
        let shows = try await PhishInAPI.showsForPeriod(period.id).map { $0.toShowSummary() }
        await PhishInCatalogCache.shared.shows.put(period.id, shows)
        return shows
    }

    public func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail {
        if let cached = await PhishInCatalogCache.shared.showDetail.get(date) { return cached }
        let detail = try await PhishInAPI.show(date).toShowDetail()
        await PhishInCatalogCache.shared.showDetail.put(date, detail)
        return detail
    }

    public func search(term: String) async throws -> SearchHits {
        try await PhishInAPI.search(term).toSearchHits()
    }
}

/// The shared cache state `PhishInSource` delegates to (#61) — see its doc comment for why a
/// stateless struct needs a separate holder. `periodsKey` is a constant because phish.in's
/// "artist list" is the single `PHISH` constant, so there's only ever one periods list to
/// cache; a `TTLCache` is still the right shape for it so `resetCache` and the TTL logic
/// aren't duplicated.
actor PhishInCatalogCache {
    static let shared = PhishInCatalogCache()
    static let periodsKey = "phish"

    let periods = TTLCache<String, [PeriodRef]>(ttl: catalogCacheTTL, maxEntries: 1)
    let shows = TTLCache<String, [ShowSummary]>(ttl: catalogCacheTTL, maxEntries: 60)
    let showDetail = TTLCache<String, ShowDetail>(ttl: catalogCacheTTL, maxEntries: 200)

    /// Test-only hook: clears every cache in one call, the way `RelistenCatalogSource.resetCache()` does.
    func resetCache() async {
        await periods.clear()
        await shows.clear()
        await showDetail.clear()
    }
}

extension Period {
    public func toPeriodRef() -> PeriodRef {
        PeriodRef(id: period, label: period, showCount: showsWithAudioCount, artURL: coverArtUrls?.medium)
    }
}

extension Show {
    public func toShowSummary() -> ShowSummary {
        ShowSummary(
            artist: PHISH,
            date: date,
            venue: venueName,
            location: location,
            tourName: tourName,
            artURL: albumCoverUrl ?? coverArtUrls?.medium,
            partial: audioStatus == "partial",
            recordingCount: 1,
            rating: Double(likesCount),
            tags: tags,
            popularity: nil,
            likesCount: likesCount
        )
    }

    public func toShowDetail() -> ShowDetail {
        let summary = toShowSummary()
        // Filtering here is what keeps the UI and the queue builder agreeing on what index 4
        // means (D12) — they both read this list rather than filtering separately.
        let playableTracks = tracks.filter { $0.playable }.map { $0.toPlayableTrack(showArt: summary.artURL) }
        return ShowDetail(summary: summary, tracks: playableTracks, tags: tags)
    }
}

extension Track {
    public func toPlayableTrack(showArt: String?) -> PlayableTrack {
        PlayableTrack(
            id: String(id),
            title: title,
            setName: setName,
            position: position,
            // Already milliseconds. Relisten's are seconds — see RelistenSourceTrack.toPlayableTrack.
            durationMs: duration,
            url: mp3Url ?? "",
            waveformURL: waveformImageUrl,
            showDate: showDate,
            venueName: venueName,
            artURL: showAlbumCoverUrl ?? showArt,
            flacUrl: nil,
            likesCount: likesCount,
            likedByUser: likedByUser,
            tags: tags,
            popularity: nil
        )
    }
}

public enum CatalogError: LocalizedError, Sendable {
    case noArtists
    case noPeriods
    case noShows

    public var errorDescription: String? {
        switch self {
        case .noArtists: return "No artists available"
        case .noPeriods: return "No years/periods available for this artist"
        case .noShows: return "No shows available for this period"
        }
    }
}

/// Restricts the "Surprise Me" draw to favorited artists, so the result is a show the user has
/// actually expressed interest in rather than any of the ~200+ artist merged catalog (#101,
/// supersedes D157). Falls back to the full merged list when nothing is favorited yet — the
/// only way the button stays usable before a first-run user has starred anything.
public func surpriseMeArtists(favorited: [ArtistRef], merged: [ArtistRef]) -> [ArtistRef] {
    favorited.isEmpty ? merged : favorited
}

/// Picks a random show across artists for the "Surprise Me" feature.
public func pickRandomShow(
    artists: [ArtistRef],
    source: (Backend) -> MusicSource = sourceFor
) async throws -> ShowSummary {
    guard let artist = artists.randomElement() else {
        throw CatalogError.noArtists
    }
    let src = source(artist.backend)
    let periods = try await src.periods(artist: artist)
    guard let period = periods.randomElement() else {
        throw CatalogError.noPeriods
    }
    let shows = try await src.shows(artist: artist, period: period)
    let completeShows = shows.filter { !$0.partial }
    let candidates = completeShows.isEmpty ? shows : completeShows
    guard let show = candidates.randomElement() else {
        throw CatalogError.noShows
    }
    return show
}

