import Foundation

// Port of Catalog.kt: the backend-neutral catalog, just enough of a model to browse to a
// playable track. The mapping lives in pure functions, tested without a network call, same
// reasoning as the Kotlin original (D36).
//
// The desktop MVP has no login/likes/playlists/search (D5 of this plan), so unlike the Android
// original this file's neutral model is the *entire* domain layer, not just the browse slice.

public enum Backend: String, CaseIterable, Hashable {
    case phishin
    case relisten

    /// Nil for anything unrecognised — these ids travel in nav routes and, eventually, in
    /// shared progress rows.
    public static func from(_ id: String) -> Backend? {
        Backend.allCases.first { $0.rawValue == id }
    }
}

public struct ArtistRef: Hashable {
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
}

/// A browsable slice of an artist's catalog. On phish.in this is a period and may be a range
/// ("1983-1987"), which is why `id` is carried verbatim — `showsForPeriod` needs it back to
/// pick `year_range=` over `year=` (D11). On Relisten it is a year's uuid.
public struct PeriodRef: Hashable {
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

public struct ShowSummary: Hashable {
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

    public init(
        id: String, title: String, setName: String = "", durationMs: Int64 = 0, url: String,
        waveformURL: String? = nil, showDate: String? = nil, venueName: String? = nil, artURL: String? = nil
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

public protocol MusicSource {
    var backend: Backend { get }

    func artists() async throws -> [ArtistRef]
    func periods(artist: ArtistRef) async throws -> [PeriodRef]
    func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary]

    /// `recordingId` nil takes the source's own default — the best tape, where there's a choice.
    func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail
}

/// Shared by the browse UI and (eventually) any background prefetch — one seam, matching the
/// Android original's use by both MainActivity's screens and PlaybackService's Auto browse tree.
public func sourceFor(_ backend: Backend) -> MusicSource {
    switch backend {
    case .phishin: return PhishInSource()
    case .relisten: return RelistenCatalogSource.shared
    }
}

// ------------------------------------------------------------------- phish.in

/// phish.in is a single-artist archive, so its artist is a constant rather than a fetch.
public let PHISH = ArtistRef(backend: .phishin, id: "phish", name: "Phish")

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
            artURL: showAlbumCoverUrl ?? showArt
        )
    }
}
