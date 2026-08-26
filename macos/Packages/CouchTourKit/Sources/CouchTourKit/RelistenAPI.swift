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
    public let popularity: RelistenPopularity?

    enum CodingKeys: String, CodingKey {
        case uuid, year
        case showCount = "show_count"
        case popularity
    }

    public init(uuid: String, year: String, showCount: Int = 0, popularity: RelistenPopularity? = nil) {
        self.uuid = uuid
        self.year = year
        self.showCount = showCount
        self.popularity = popularity
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uuid = try c.decode(String.self, forKey: .uuid)
        year = try c.decode(String.self, forKey: .year)
        showCount = try c.decodeIfPresent(Int.self, forKey: .showCount) ?? 0
        popularity = try c.decodeIfPresent(RelistenPopularity.self, forKey: .popularity)
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
    public let avgRating: Double
    public let hasSoundboardSource: Bool
    public let hasStreamableFlacSource: Bool
    public let popularity: RelistenPopularity?

    enum CodingKeys: String, CodingKey {
        case displayDate = "display_date"
        case venue, tour
        case sourceCount = "source_count"
        case avgRating = "avg_rating"
        case hasSoundboardSource = "has_soundboard_source"
        case hasStreamableFlacSource = "has_streamable_flac_source"
        case popularity
    }

    public init(
        displayDate: String,
        venue: RelistenVenue? = nil,
        tour: RelistenTour? = nil,
        sourceCount: Int = 0,
        avgRating: Double = 0.0,
        hasSoundboardSource: Bool = false,
        hasStreamableFlacSource: Bool = false,
        popularity: RelistenPopularity? = nil
    ) {
        self.displayDate = displayDate
        self.venue = venue
        self.tour = tour
        self.sourceCount = sourceCount
        self.avgRating = avgRating
        self.hasSoundboardSource = hasSoundboardSource
        self.hasStreamableFlacSource = hasStreamableFlacSource
        self.popularity = popularity
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        displayDate = try c.decode(String.self, forKey: .displayDate)
        venue = try c.decodeIfPresent(RelistenVenue.self, forKey: .venue)
        tour = try c.decodeIfPresent(RelistenTour.self, forKey: .tour)
        sourceCount = try c.decodeIfPresent(Int.self, forKey: .sourceCount) ?? 0
        avgRating = try c.decodeIfPresent(Double.self, forKey: .avgRating) ?? 0.0
        hasSoundboardSource = try c.decodeIfPresent(Bool.self, forKey: .hasSoundboardSource) ?? false
        hasStreamableFlacSource = try c.decodeIfPresent(Bool.self, forKey: .hasStreamableFlacSource) ?? false
        popularity = try c.decodeIfPresent(RelistenPopularity.self, forKey: .popularity)
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
    public let flacUrl: String?

    enum CodingKeys: String, CodingKey {
        case uuid, title
        case trackPosition = "track_position"
        case duration
        case mp3Url = "mp3_url"
        case flacUrl = "flac_url"
    }

    public init(uuid: String, title: String, trackPosition: Int = 0, duration: Int64 = 0, mp3Url: String? = nil, flacUrl: String? = nil) {
        self.uuid = uuid
        self.title = title
        self.trackPosition = trackPosition
        self.duration = duration
        self.mp3Url = mp3Url
        self.flacUrl = flacUrl
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uuid = try c.decode(String.self, forKey: .uuid)
        title = try c.decode(String.self, forKey: .title)
        trackPosition = try c.decodeIfPresent(Int.self, forKey: .trackPosition) ?? 0
        duration = try c.decodeIfPresent(Int64.self, forKey: .duration) ?? 0
        mp3Url = try c.decodeIfPresent(String.self, forKey: .mp3Url)
        flacUrl = try c.decodeIfPresent(String.self, forKey: .flacUrl)
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
    public let avgRating: Double
    public let hasSoundboardSource: Bool
    public let hasStreamableFlacSource: Bool
    public let sourceCount: Int
    public let popularity: RelistenPopularity?

    enum CodingKeys: String, CodingKey {
        case displayDate = "display_date"
        case venue, tour, sources
        case avgRating = "avg_rating"
        case hasSoundboardSource = "has_soundboard_source"
        case hasStreamableFlacSource = "has_streamable_flac_source"
        case sourceCount = "source_count"
        case popularity
    }

    public init(
        displayDate: String,
        venue: RelistenVenue? = nil,
        tour: RelistenTour? = nil,
        sources: [RelistenSource] = [],
        avgRating: Double = 0.0,
        hasSoundboardSource: Bool = false,
        hasStreamableFlacSource: Bool = false,
        sourceCount: Int = 0,
        popularity: RelistenPopularity? = nil
    ) {
        self.displayDate = displayDate
        self.venue = venue
        self.tour = tour
        self.sources = sources
        self.avgRating = avgRating
        self.hasSoundboardSource = hasSoundboardSource
        self.hasStreamableFlacSource = hasStreamableFlacSource
        self.sourceCount = sourceCount
        self.popularity = popularity
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        displayDate = try c.decode(String.self, forKey: .displayDate)
        venue = try c.decodeIfPresent(RelistenVenue.self, forKey: .venue)
        tour = try c.decodeIfPresent(RelistenTour.self, forKey: .tour)
        sources = try c.decodeIfPresent([RelistenSource].self, forKey: .sources) ?? []
        avgRating = try c.decodeIfPresent(Double.self, forKey: .avgRating) ?? 0.0
        hasSoundboardSource = try c.decodeIfPresent(Bool.self, forKey: .hasSoundboardSource) ?? false
        hasStreamableFlacSource = try c.decodeIfPresent(Bool.self, forKey: .hasStreamableFlacSource) ?? false
        sourceCount = try c.decodeIfPresent(Int.self, forKey: .sourceCount) ?? sources.count
        popularity = try c.decodeIfPresent(RelistenPopularity.self, forKey: .popularity)
    }
}

// -------------------------------------------------------------------- search

/// The artist embedded in a search hit — a slimmer projection than `RelistenArtist`, missing
/// `showCount`/`features`, which is fine since every destination screen re-resolves the real
/// `ArtistRef` through `artists()`.
public struct RelistenSlimArtist: Codable, Equatable {
    public let slug: String
    public let name: String

    public init(slug: String, name: String) {
        self.slug = slug
        self.name = name
    }
}

public struct RelistenSearchShow: Codable, Equatable {
    public let slimArtist: RelistenSlimArtist
    public let displayDate: String
    public let sourceCount: Int

    enum CodingKeys: String, CodingKey {
        case slimArtist = "slim_artist"
        case displayDate = "display_date"
        case sourceCount = "source_count"
    }

    public init(slimArtist: RelistenSlimArtist, displayDate: String, sourceCount: Int = 0) {
        self.slimArtist = slimArtist
        self.displayDate = displayDate
        self.sourceCount = sourceCount
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        slimArtist = try c.decode(RelistenSlimArtist.self, forKey: .slimArtist)
        displayDate = try c.decode(String.self, forKey: .displayDate)
        sourceCount = try c.decodeIfPresent(Int.self, forKey: .sourceCount) ?? 0
    }
}

public struct RelistenSearchSong: Codable, Equatable {
    public let slimArtist: RelistenSlimArtist
    public let name: String
    public let uuid: String
    public let showsPlayedAt: Int

    enum CodingKeys: String, CodingKey {
        case slimArtist = "slim_artist"
        case name, uuid
        case showsPlayedAt = "shows_played_at"
    }

    public init(slimArtist: RelistenSlimArtist, name: String, uuid: String, showsPlayedAt: Int = 0) {
        self.slimArtist = slimArtist
        self.name = name
        self.uuid = uuid
        self.showsPlayedAt = showsPlayedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        slimArtist = try c.decode(RelistenSlimArtist.self, forKey: .slimArtist)
        name = try c.decode(String.self, forKey: .name)
        uuid = try c.decode(String.self, forKey: .uuid)
        showsPlayedAt = try c.decodeIfPresent(Int.self, forKey: .showsPlayedAt) ?? 0
    }
}

public struct RelistenSearchVenue: Codable, Equatable {
    public let slimArtist: RelistenSlimArtist
    public let name: String
    public let location: String?
    public let uuid: String

    enum CodingKeys: String, CodingKey {
        case slimArtist = "slim_artist"
        case name, location, uuid
    }

    public init(slimArtist: RelistenSlimArtist, name: String, location: String? = nil, uuid: String) {
        self.slimArtist = slimArtist
        self.name = name
        self.location = location
        self.uuid = uuid
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        slimArtist = try c.decode(RelistenSlimArtist.self, forKey: .slimArtist)
        name = try c.decode(String.self, forKey: .name)
        location = try c.decodeIfPresent(String.self, forKey: .location)
        uuid = try c.decode(String.self, forKey: .uuid)
    }
}

/// `/v3/search?q=` — six buckets; `Sources` and `Tours` are dropped just by omitting them from
/// `CodingKeys`, since neither has a screen to land on (see MULTI-ARTIST-PLAN.md).
public struct RelistenSearchResults: Codable, Equatable {
    public let artists: [RelistenArtist]
    public let shows: [RelistenSearchShow]
    public let songs: [RelistenSearchSong]
    public let venues: [RelistenSearchVenue]

    enum CodingKeys: String, CodingKey {
        case artists = "Artists"
        case shows = "Shows"
        case songs = "Songs"
        case venues = "Venues"
    }

    public init(artists: [RelistenArtist] = [], shows: [RelistenSearchShow] = [], songs: [RelistenSearchSong] = [], venues: [RelistenSearchVenue] = []) {
        self.artists = artists
        self.shows = shows
        self.songs = songs
        self.venues = venues
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        artists = try c.decodeIfPresent([RelistenArtist].self, forKey: .artists) ?? []
        shows = try c.decodeIfPresent([RelistenSearchShow].self, forKey: .shows) ?? []
        songs = try c.decodeIfPresent([RelistenSearchSong].self, forKey: .songs) ?? []
        venues = try c.decodeIfPresent([RelistenSearchVenue].self, forKey: .venues) ?? []
    }
}

/// `/v3/artists/{slug}/songs/{uuid}` and `/v3/artists/{slug}/venues/{uuid}` share this shape:
/// the entity's own name plus the shows it appears in.
public struct RelistenSliceWithShows: Codable, Equatable {
    public let name: String
    public let shows: [RelistenShowSummary]

    public init(name: String, shows: [RelistenShowSummary] = []) {
        self.name = name
        self.shows = shows
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        shows = try c.decodeIfPresent([RelistenShowSummary].self, forKey: .shows) ?? []
    }

    enum CodingKeys: String, CodingKey { case name, shows }
}

/// Namespace prefixes for `PeriodRef.id` so `RelistenCatalogSource.shows` can dispatch a song
/// or venue hit to the right endpoint instead of the ordinary year lookup. A bare uuid (no
/// prefix) is still a year id — existing routes are untouched.
private let songPrefix = "song:"
private let venuePrefix = "venue:"

public func songPeriodID(_ uuid: String) -> String { "\(songPrefix)\(uuid)" }
public func venuePeriodID(_ uuid: String) -> String { "\(venuePrefix)\(uuid)" }

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
        var tags: [Tag] = []
        if hasSoundboardSource {
            tags.append(Tag(name: "SBD", description: "Soundboard recording", priority: 10))
        }
        if hasStreamableFlacSource {
            tags.append(Tag(name: "FLAC", description: "Lossless FLAC audio", priority: 5))
        }
        return ShowSummary(
            artist: artist,
            date: displayDate,
            venue: venue?.name,
            location: venue?.location,
            tourName: tour?.name,
            recordingCount: max(sourceCount, 1),
            rating: avgRating,
            tags: tags,
            popularity: popularity
        )
    }
}

/// Relisten sends `""` rather than omitting `taper`/`lineage` on many sources — without this,
/// a blank taper renders an empty row label and a bare "Lineage:" with nothing after it.
extension Optional where Wrapped == String {
    fileprivate var nonBlank: String? {
        guard let self, !self.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return self
    }
}

extension RelistenSource {
    public func toRecordingRef() -> RecordingRef {
        let hasFlac = sets.contains { set in
            set.tracks.contains { $0.flacUrl.nonBlank != nil }
        }
        return RecordingRef(
            id: uuid,
            label: taper.nonBlank ?? (isSoundboard ? "Soundboard" : "Audience"),
            isSoundboard: isSoundboard,
            hasFlac: hasFlac,
            rating: avgRatingWeighted,
            reviewCount: numReviews,
            taper: taper.nonBlank,
            lineage: lineage.nonBlank
        )
    }
}

extension RelistenSourceTrack {
    public func toPlayableTrack(artist: ArtistRef, showDate: String, venueName: String?, setName: String, tags: [Tag] = []) -> PlayableTrack {
        PlayableTrack(
            id: uuid,
            title: title,
            // Suppressed for an artist without real sets (verified live: Dead sources carry
            // one wrapper set literally named "Set") rather than every screen re-checking hasSets.
            setName: artist.hasSets ? setName : "",
            durationMs: duration * 1000,
            url: mp3Url ?? "",
            showDate: showDate,
            venueName: venueName,
            flacUrl: flacUrl,
            tags: tags
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

        let chosenRecording = chosen?.toRecordingRef()

        var showTags: [Tag] = []
        let hasSbd = hasSoundboardSource || sources.contains { $0.isSoundboard }
        let hasFlac = hasStreamableFlacSource || sources.contains { src in
            src.sets.contains { set in set.tracks.contains { $0.flacUrl != nil && !$0.flacUrl!.isEmpty } }
        }
        let hasMatrix = sources.contains { $0.toRecordingRef().looksLikeMatrix }

        if hasSbd {
            showTags.append(Tag(name: "SBD", description: "Soundboard recording", priority: 10))
        }
        if hasMatrix {
            showTags.append(Tag(name: "Matrix", description: "Matrix recording (SBD + AUD)", priority: 8))
        }
        if hasFlac {
            showTags.append(Tag(name: "FLAC", description: "Lossless FLAC audio", priority: 5))
        }

        let tracks: [PlayableTrack] = (chosen?.sets ?? [])
            .sorted { $0.index < $1.index }
            .flatMap { set in
                set.tracks
                    .filter { !($0.mp3Url ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
                    .map { track in
                        var trackTags: [Tag] = []
                        if chosenRecording?.isSoundboard == true {
                            trackTags.append(Tag(name: "SBD", description: "Soundboard recording", priority: 10))
                        }
                        if track.flacUrl != nil && !track.flacUrl!.isEmpty {
                            trackTags.append(Tag(name: "FLAC", description: "Lossless FLAC audio", priority: 5))
                        }
                        return track.toPlayableTrack(
                            artist: artist,
                            showDate: displayDate,
                            venueName: venue?.name,
                            setName: set.name,
                            tags: trackTags
                        )
                    }
            }

        let summary = ShowSummary(
            artist: artist,
            date: displayDate,
            venue: venue?.name,
            location: venue?.location,
            tourName: tour?.name,
            recordingCount: max(sources.count, 1),
            rating: avgRating,
            tags: showTags,
            popularity: popularity
        )

        return ShowDetail(
            summary: summary,
            recording: chosenRecording,
            alternates: sources.filter { $0.uuid != chosen?.uuid }.map { $0.toRecordingRef() },
            tracks: tracks,
            tags: showTags,
            popularity: popularity
        )
    }
}

extension RelistenSearchResults {
    /// Maps every bucket to `SearchHits`, dropping the `phish` slug: phish.in is the Phish
    /// backend (D-verified, MULTI-ARTIST-PLAN.md decision 2), so a Relisten hit for it would
    /// be a near-duplicate row missing likes, waveforms, and cover art. Shows and slices
    /// carry artist-projection `ArtistRef`s built from `RelistenSlimArtist` rather than a
    /// full lookup — every screen they lead to re-resolves the real one anyway.
    public func toSearchHits() -> SearchHits {
        func ref(_ slim: RelistenSlimArtist) -> ArtistRef {
            ArtistRef(backend: .relisten, id: slim.slug, name: slim.name)
        }

        return SearchHits(
            artists: artists.filter { $0.slug != PHISH.id }.map { $0.toArtistRef() },
            shows: shows.filter { $0.slimArtist.slug != PHISH.id }.map {
                ShowSummary(artist: ref($0.slimArtist), date: $0.displayDate, recordingCount: max($0.sourceCount, 1))
            },
            slices: songs.filter { $0.slimArtist.slug != PHISH.id }.map {
                SliceHit(
                    kind: .song, artist: ref($0.slimArtist),
                    period: PeriodRef(id: songPeriodID($0.uuid), label: $0.name, showCount: $0.showsPlayedAt)
                )
            } + venues.filter { $0.slimArtist.slug != PHISH.id }.map {
                SliceHit(kind: .venue, artist: ref($0.slimArtist), period: PeriodRef(id: venuePeriodID($0.uuid), label: $0.name))
            }
        )
    }
}

extension RelistenSliceWithShows {
    public func toShowSummaries(artist: ArtistRef) -> [ShowSummary] {
        shows.map { $0.toShowSummary(artist: artist) }
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

    public static func song(artistIdOrSlug: String, songUuid: String) async throws -> RelistenSliceWithShows {
        try decoder.decode(RelistenSliceWithShows.self, from: try await get(path("v3", "artists", artistIdOrSlug, "songs", songUuid)))
    }

    public static func venue(artistIdOrSlug: String, venueUuid: String) async throws -> RelistenSliceWithShows {
        try decoder.decode(RelistenSliceWithShows.self, from: try await get(path("v3", "artists", artistIdOrSlug, "venues", venueUuid)))
    }

    /// `term` goes in `q`, a query parameter — unlike phish.in's `/search/{term}` path
    /// segment. `path(_:)` returns a bare `URL` here (unlike PhishInAPI's `URLComponents`),
    /// so this needs its own `URLComponents` to attach a query item.
    public static func search(_ term: String) async throws -> RelistenSearchResults {
        var components = URLComponents(url: path("v3", "search"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "q", value: term)]
        return try decoder.decode(RelistenSearchResults.self, from: try await get(components.url!))
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

    /// A `period` id namespaced `song:`/`venue:` (from a search hit) routes to the matching
    /// entity endpoint instead of the ordinary year lookup — see the prefix constants above.
    public func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary] {
        if period.id.hasPrefix(songPrefix) {
            let uuid = String(period.id.dropFirst(songPrefix.count))
            return try await RelistenAPI.song(artistIdOrSlug: artist.id, songUuid: uuid).toShowSummaries(artist: artist)
        }
        if period.id.hasPrefix(venuePrefix) {
            let uuid = String(period.id.dropFirst(venuePrefix.count))
            return try await RelistenAPI.venue(artistIdOrSlug: artist.id, venueUuid: uuid).toShowSummaries(artist: artist)
        }
        return try await RelistenAPI.year(artistUuid: artist.id, yearUuid: period.id).shows.map { $0.toShowSummary(artist: artist) }
    }

    public func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail {
        try await RelistenAPI.show(artistIdOrSlug: artist.id, date: date).toShowDetail(artist: artist, recordingId: recordingId)
    }

    public func search(term: String) async throws -> SearchHits {
        try await RelistenAPI.search(term).toSearchHits()
    }
}
