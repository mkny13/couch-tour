import Foundation

public let notPartOfATour = "Not Part of a Tour"
public let maxTourArtists = 3
public let tourPeriodsCount = 2

/// The latest `count` year-shaped periods, most recent first. Sorted on `label` (which is a plain
/// year string on both backends) rather than `id` (opaque UUID on Relisten).
public func recentPeriods(_ periods: [PeriodRef], count: Int = tourPeriodsCount) -> [PeriodRef] {
    periods.filter { Int($0.label) != nil }
        .sorted { (Int($0.label) ?? 0) > (Int($1.label) ?? 0) }
        .prefix(count)
        .map { $0 }
}

/// The backend-neutral identity of the show a catalog entry represents — what "have I played this?" compares.
public func showId(for show: ShowSummary) -> String {
    switch show.artist.backend {
    case .phishin:
        return showQueueKey(show.date)
    case .relisten:
        return recordingShowKey(show.artist.id, show.date)
    }
}

/// The identity set for stored queue keys (e.g. `history()` / finished keys).
public func playedShowIds(from keys: [String]) -> Set<String> {
    var result = Set<String>()
    for raw in keys {
        guard let ref = parseQueueKey(raw) else { continue }
        switch ref.kind {
        case .show:
            result.insert(showQueueKey(ref.id))
        case .recording:
            if let rec = parseRecordingId(ref.id) {
                result.insert(recordingShowKey(rec.artistSlug, rec.date))
            }
        case .playlist, .localPlaylist, .youtube:
            break
        }
    }
    return result
}

public func playedShowIds(from keys: Set<String>) -> Set<String> {
    playedShowIds(from: Array(keys))
}

/// The shows in `shows` that share the tour of the most recent one — empty if that show carries
/// no tour name or carries `Not Part of a Tour`.
public func currentTourShows(_ shows: [ShowSummary]) -> [ShowSummary] {
    guard let latest = shows.max(by: { $0.date < $1.date }) else { return [] }
    guard let tour = latest.tourName?.trimmingCharacters(in: .whitespaces),
          !tour.isEmpty,
          tour != notPartOfATour else {
        return []
    }
    return shows.filter { $0.tourName == tour }
}

/// The single oldest show in `candidates` with no matching entry in `played`. Ties break on `ArtistRef.key`.
public func oldestUnplayed(candidates: [ShowSummary], played: Set<String>) -> ShowSummary? {
    let unplayed = candidates.filter { !played.contains(showId(for: $0)) }
    return unplayed.min { a, b in
        if a.date != b.date { return a.date < b.date }
        return a.artist.key < b.artist.key
    }
}

/// Fetches tour shows for an artist, supporting defunct artist resolution via `ArtistTourPreference` (#68, D190).
public func tourFor(
    artist: ArtistRef,
    preference: ArtistTourPreference? = nil,
    source: (Backend) -> MusicSource = sourceFor
) async throws -> [ShowSummary] {
    try await tourForChecked(artist: artist, preference: preference, source: source).shows
}

/// Like `tourFor`, but also reports whether every period request it needed succeeded.
/// A failed period is skipped so the rest still shows, but the result must not be cached (#466).
public func tourForChecked(
    artist: ArtistRef,
    preference: ArtistTourPreference? = nil,
    source: (Backend) -> MusicSource = sourceFor
) async throws -> (shows: [ShowSummary], complete: Bool) {
    let src = source(artist.backend)
    let periods = try await src.periods(artist: artist)
    var complete = true

    func fetch(_ period: PeriodRef) async -> [ShowSummary]? {
        do {
            return try await src.shows(artist: artist, period: period)
        } catch {
            complete = false
            return nil
        }
    }

    if let pref = preference {
        if let year = pref.year, !year.isEmpty {
            if let period = periods.first(where: { $0.label == year || $0.id == year }) {
                guard let shows = await fetch(period) else { return ([], false) }
                if let tourName = pref.tourName, !tourName.isEmpty {
                    return (shows.filter { $0.tourName == tourName }, true)
                }
                return (shows, true)
            }
        }
        if let tourName = pref.tourName, !tourName.isEmpty {
            // First check period matching a 4-digit year in the tourName if present
            if let yearMatch = periods.first(where: { tourName.contains($0.label) }) {
                if let shows = await fetch(yearMatch) {
                    let matching = shows.filter { $0.tourName == tourName }
                    if !matching.isEmpty { return (matching, complete) }
                }
            }
            for period in periods {
                if let shows = await fetch(period) {
                    let matching = shows.filter { $0.tourName == tourName }
                    if !matching.isEmpty {
                        return (matching, complete)
                    }
                }
            }
        }
    }

    let recPeriods = recentPeriods(periods)
    var allShows: [ShowSummary] = []
    for period in recPeriods {
        if let shows = await fetch(period) {
            allShows.append(contentsOf: shows)
        }
    }
    return (currentTourShows(allShows), complete)
}

/// Fetches every favorited artist's tour shows, fanned out concurrently and capped at `maxTourArtists` per backend.
public func currentTours(
    favorites: [ArtistRef],
    preferences: [ArtistTourPreference] = [],
    source: @escaping (Backend) -> MusicSource = sourceFor
) async throws -> [ShowSummary] {
    try await currentToursChecked(favorites: favorites, preferences: preferences, source: source).shows
}

public func currentTours(
    favorites: [ArtistRef],
    preferenceLookup: @escaping (ArtistRef) -> ArtistTourPreference?,
    source: @escaping (Backend) -> MusicSource = sourceFor
) async throws -> [ShowSummary] {
    try await currentToursChecked(favorites: favorites, preferenceLookup: preferenceLookup, source: source).shows
}

public func currentToursChecked(
    favorites: [ArtistRef],
    preferences: [ArtistTourPreference] = [],
    source: @escaping (Backend) -> MusicSource = sourceFor
) async throws -> (shows: [ShowSummary], complete: Bool) {
    let prefMap = Dictionary(uniqueKeysWithValues: preferences.map { ($0.artistKey, $0) })
    return try await currentToursChecked(favorites: favorites, preferenceLookup: { prefMap[$0.key] }, source: source)
}

/// Like `currentTours`, but `complete` is false if any artist or period request failed.
public func currentToursChecked(
    favorites: [ArtistRef],
    preferenceLookup: @escaping (ArtistRef) -> ArtistTourPreference?,
    source: @escaping (Backend) -> MusicSource = sourceFor
) async throws -> (shows: [ShowSummary], complete: Bool) {
    var participating: [ArtistRef] = []
    let grouped = Dictionary(grouping: favorites, by: \.backend)
    for backend in Backend.allCases {
        if let artists = grouped[backend] {
            participating.append(contentsOf: artists.prefix(maxTourArtists))
        }
    }

    if participating.isEmpty { return ([], true) }

    enum ArtistResult {
        case success([ShowSummary], complete: Bool)
        case failure(Error)
    }

    return try await withThrowingTaskGroup(of: ArtistResult.self) { group in
        for artist in participating {
            let pref = preferenceLookup(artist)
            group.addTask {
                do {
                    let (shows, complete) = try await tourForChecked(artist: artist, preference: pref, source: source)
                    return .success(shows, complete: complete)
                } catch {
                    return .failure(error)
                }
            }
        }

        var allShows: [ShowSummary] = []
        var successCount = 0
        var allComplete = true
        var firstError: Error?

        for try await result in group {
            switch result {
            case .success(let shows, let complete):
                allShows.append(contentsOf: shows)
                successCount += 1
                if !complete { allComplete = false }
            case .failure(let error):
                allComplete = false
                if firstError == nil { firstError = error }
            }
        }

        if successCount == 0, let error = firstError {
            throw error
        }

        return (allShows, allComplete)
    }
}

/// The Home screen's entry point: `currentTours` behind a one-entry in-memory cache.
public enum NextStop {
    private static var cached: (key: String, shows: [ShowSummary])?

    public static func cacheKey(
        favorites: [ArtistRef],
        today: String,
        preferences: [ArtistTourPreference] = []
    ) -> String {
        let favPart = favorites.map { $0.key }.sorted().joined(separator: ",")
        let prefPart = preferences
            .map { "\($0.artistKey):\($0.tourName ?? ""):\($0.year ?? "")" }
            .sorted()
            .joined(separator: ";")
        return "\(today)|\(favPart)|\(prefPart)"
    }

    public static func load(
        favorites: [ArtistRef],
        today: String,
        preferences: [ArtistTourPreference] = [],
        source: @escaping (Backend) -> MusicSource = sourceFor
    ) async throws -> [ShowSummary] {
        if favorites.isEmpty { return [] }
        let key = cacheKey(favorites: favorites, today: today, preferences: preferences)
        if let cached = cached, cached.key == key {
            return cached.shows
        }
        let (shows, complete) = try await currentToursChecked(favorites: favorites, preferences: preferences, source: source)
        // A partial list cached here would hide the failed artists/periods until tomorrow (#466).
        if complete && !shows.isEmpty {
            cached = (key, shows)
        }
        return shows
    }

    public static func resetCache() {
        cached = nil
    }
}

/// Resolves the next consecutive show on tour after `currentDate` (#85).
///
/// If `tourName` is present and not "Not Part of a Tour", it looks for the next chronological
/// show within that same tour. If no subsequent show exists in that tour or `tourName` is nil/empty,
/// it falls back to the next chronological show overall in `candidateShows`.
public func resolveNextConsecutiveShow(
    currentDate: String,
    tourName: String?,
    candidateShows: [ShowSummary]
) -> ShowSummary? {
    let futureShows = candidateShows.filter { $0.date > currentDate }
    if let tourName = tourName?.trimmingCharacters(in: .whitespaces),
       !tourName.isEmpty,
       tourName != notPartOfATour {
        let nextInTour = futureShows.filter { $0.tourName == tourName }.min { $0.date < $1.date }
        if let nextInTour { return nextInTour }
    }
    return futureShows.min { $0.date < $1.date }
}

/// Fetches the artist's shows surrounding `currentDate` and resolves the next consecutive show on tour (#85).
public func findNextTourStop(
    artist: ArtistRef,
    currentDate: String,
    tourName: String? = nil,
    source: (Backend) -> MusicSource = sourceFor
) async -> ShowSummary? {
    let src = source(artist.backend)
    guard let periods = try? await src.periods(artist: artist) else { return nil }
    let validPeriods = periods.filter { $0.id != "popular" }
    let yearStr = String(currentDate.prefix(4))
    guard let period = validPeriods.first(where: { $0.label == yearStr || $0.label.contains(yearStr) || $0.id == yearStr }) ?? validPeriods.first else {
        return nil
    }
    guard let shows = try? await src.shows(artist: artist, period: period) else { return nil }
    let currentShow = shows.first(where: { $0.date == currentDate })
    let effectiveTourName = tourName?.trimmingCharacters(in: .whitespaces).isEmpty == false ? tourName : currentShow?.tourName

    if let nextInPeriod = resolveNextConsecutiveShow(currentDate: currentDate, tourName: effectiveTourName, candidateShows: shows) {
        return nextInPeriod
    }

    if let yearInt = Int(yearStr) {
        let nextPeriods = validPeriods.filter { p in
            guard let pYear = Int(p.label.prefix(4)) ?? Int(p.id.prefix(4)) else { return false }
            return pYear > yearInt
        }.sorted { $0.label < $1.label }

        for np in nextPeriods.prefix(2) {
            if let nextShows = try? await src.shows(artist: artist, period: np),
               let found = resolveNextConsecutiveShow(currentDate: currentDate, tourName: effectiveTourName, candidateShows: nextShows) {
                return found
            }
        }
    }
    return nil
}
