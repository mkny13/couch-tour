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

public struct ArtistRef: Hashable, Sendable {
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
public struct PeriodRef: Hashable, Sendable {
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

    public init(
        artist: ArtistRef, date: String, venue: String? = nil, location: String? = nil,
        tourName: String? = nil, artURL: String? = nil, partial: Bool = false, recordingCount: Int = 1
    ) {
        self.artist = artist
        self.date = date
        self.venue = venue
        self.location = location
        self.tourName = tourName
        self.artURL = artURL
        self.partial = partial
        self.recordingCount = recordingCount
    }

    /// "McNichols Arena · Denver, CO"
    public var where_: String {
        [venue, location].compactMap { $0 }.joined(separator: " · ")
    }
}

/// One tape of one show.
///
/// This is the concept phish.in doesn't have. Relisten carries around nine recordings of an
/// average Grateful Dead show — different tapers, different lineage, different soundboard or
/// audience provenance — and each splits the music into its own tracks. That last part is why
/// a recording is part of a queue key and not a display detail.
public struct RecordingRef: Hashable {
    public let id: String
    public let label: String
    public let isSoundboard: Bool
    public let rating: Double
    public let reviewCount: Int
    public let taper: String?
    public let lineage: String?

    public init(id: String, label: String, isSoundboard: Bool = false, rating: Double = 0, reviewCount: Int = 0, taper: String? = nil, lineage: String? = nil) {
        self.id = id
        self.label = label
        self.isSoundboard = isSoundboard
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
}

public struct PlayableTrack: Equatable {
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
    /// phish.in only (#58) — 0/false for Relisten, which has no server-side like concept;
    /// Relisten likes are tracked locally by id instead, see `LikedTracks`.
    public let likesCount: Int
    public let likedByUser: Bool

    public init(
        id: String, title: String, setName: String = "", durationMs: Int64 = 0, url: String,
        waveformURL: String? = nil, showDate: String? = nil, venueName: String? = nil, artURL: String? = nil,
        likesCount: Int = 0, likedByUser: Bool = false
    ) {
        self.id = id
        self.title = title
        self.setName = setName
        self.durationMs = durationMs
        self.url = url
        self.waveformURL = waveformURL
        self.showDate = showDate
        self.venueName = venueName
        self.artURL = artURL
        self.likesCount = likesCount
        self.likedByUser = likedByUser
    }
}

public struct ShowDetail: Equatable {
    public let summary: ShowSummary
    public let recording: RecordingRef?
    public let alternates: [RecordingRef]
    public let tracks: [PlayableTrack]

    public init(summary: ShowSummary, recording: RecordingRef? = nil, alternates: [RecordingRef] = [], tracks: [PlayableTrack] = []) {
        self.summary = summary
        self.recording = recording
        self.alternates = alternates
        self.tracks = tracks
    }

    /// Where this queue's progress is stored, or nil if it isn't resumable.
    ///
    /// A phish.in show keys itself exactly as it always has, matching Android's `show:<date>`
    /// key so a future shared `progress` table needs no migration on either side. A Relisten
    /// show without a chosen tape has no key at all rather than a broken one — recording
    /// nothing beats recording a position that parses back to nothing, the same call shuffle
    /// makes (D42).
    public var queueKey: String? {
        switch summary.artist.backend {
        case .phishin:
            return showQueueKey(summary.date)
        case .relisten:
            guard let recording else { return nil }
            return recordingQueueKey(summary.artist.id, summary.date, recording.id)
        }
    }
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
    let rest = relistenArtists.filter { $0.name.caseInsensitiveCompare(PHISH.name) != .orderedSame }
    let (favorited, unfavorited) = rest.reduce(into: ([ArtistRef](), [ArtistRef]())) { acc, artist in
        if favorites.contains(artist.key) {
            acc.0.append(artist)
        } else {
            acc.1.append(artist)
        }
    }
    return [PHISH]
        + favorited.sorted { $0.showCount > $1.showCount }
        + unfavorited.sorted { $0.showCount > $1.showCount }
}

/// Adapts `PhishInAPI` to the seam. Deliberately thin: it reuses the client untouched,
/// including the period/year-range branch and the audio filtering, so nothing about the Phish
/// path changes and its tests pin the exact same traps DECISIONS.md already paid for.
public struct PhishInSource: MusicSource {
    public let backend = Backend.phishin

    public init() {}

    public func artists() async throws -> [ArtistRef] { [PHISH] }

    public func periods(artist: ArtistRef) async throws -> [PeriodRef] {
        try await PhishInAPI.years().map { $0.toPeriodRef() }
    }

    public func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary] {
        try await PhishInAPI.showsForPeriod(period.id).map { $0.toShowSummary() }
    }

    public func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail {
        try await PhishInAPI.show(date).toShowDetail()
    }

    public func search(term: String) async throws -> SearchHits {
        try await PhishInAPI.search(term).toSearchHits()
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
            recordingCount: 1
        )
    }

    public func toShowDetail() -> ShowDetail {
        let summary = toShowSummary()
        // Filtering here is what keeps the UI and the queue builder agreeing on what index 4
        // means (D12) — they both read this list rather than filtering separately.
        let playableTracks = tracks.filter { $0.playable }.map { $0.toPlayableTrack(showArt: summary.artURL) }
        return ShowDetail(summary: summary, tracks: playableTracks)
    }
}

extension Track {
    public func toPlayableTrack(showArt: String?) -> PlayableTrack {
        PlayableTrack(
            id: String(id),
            title: title,
            setName: setName,
            // Already milliseconds. Relisten's are seconds — see RelistenSourceTrack.toPlayableTrack.
            durationMs: duration,
            url: mp3Url ?? "",
            waveformURL: waveformImageUrl,
            showDate: showDate,
            venueName: venueName,
            artURL: showAlbumCoverUrl ?? showArt,
            likesCount: likesCount,
            likedByUser: likedByUser
        )
    }
}
