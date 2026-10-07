import Foundation

/// Caps shows per `year_range=` request. 900 shows is ~2.7 MB, slow enough to time out, and a timed-out
/// range looks like years with no shows. 300 (~0.9 MB) mirrors Android's `PHISHIN_RANGE_CAP` and stays
/// well under phish.in's `per_page=1000` page limit.
public let phishInRangeCap = 300

/// Relisten artists beyond this many don't participate at all. Each favorite costs one on-date request.
public let maxRelistenArtists = 10

/// How many matches the anniversary shelf shows.
public let maxAnniversaryShows = 8

/// "1997-11-17" -> "11-17"; nil for anything that isn't a `YYYY-MM-DD` date.
public func monthDay(_ date: String) -> String? {
    guard date.count == 10 else { return nil }
    let chars = Array(date)
    guard chars[4] == "-" && chars[7] == "-" else { return nil }
    return String(chars[5..<10])
}

/// "1997-11-17" -> "1997"; nil on the same terms as `monthDay`.
public func yearOf(_ date: String) -> String? {
    guard monthDay(date) != nil else { return nil }
    return String(date.prefix(4))
}

/// The shows in `shows` played on `today`'s month/day in some *other* year.
public func showsOnAnniversary(shows: [ShowSummary], today: String) -> [ShowSummary] {
    guard let md = monthDay(today) else { return [] }
    let thisYear = yearOf(today)
    return shows.filter { monthDay($0.date) == md && yearOf($0.date) != thisYear }
}

/// A phish.in period id is either "1997" or "1983-1987"; this is its span.
public func periodSpan(_ id: String) -> ClosedRange<Int>? {
    let parts = id.split(separator: "-").compactMap { Int($0) }
    if parts.count == 1 {
        return parts[0]...parts[0]
    } else if parts.count == 2 {
        guard parts[0] <= parts[1] else { return nil }
        return parts[0]...parts[1]
    }
    return nil
}

/// Collapses phish.in's ~35 single-year periods into a handful of `year_range=` ones, so
/// covering the whole archive costs about four requests instead of thirty-five.
public func phishInRanges(periods: [PeriodRef], cap: Int = phishInRangeCap) -> [PeriodRef] {
    let spans: [(ClosedRange<Int>, Int)] = periods.compactMap { p in
        guard let span = periodSpan(p.id) else { return nil }
        return (span, p.showCount)
    }
    if spans.isEmpty { return [] }

    var batches: [(ClosedRange<Int>, Int)] = []
    for (span, count) in spans {
        guard let last = batches.last else {
            batches.append((span, count))
            continue
        }
        if last.1 + count > cap {
            batches.append((span, count))
        } else {
            let combinedRange = min(last.0.lowerBound, span.lowerBound)...max(last.0.upperBound, span.upperBound)
            batches[batches.count - 1] = (combinedRange, last.1 + count)
        }
    }

    return batches.map { (span, count) in
        PeriodRef(
            id: "\(span.lowerBound)-\(span.upperBound)",
            label: "\(span.lowerBound)-\(span.upperBound)",
            showCount: count
        )
    }
}

/// Trims the matches down to a bounded random handful, then orders them newest-first.
public func pickAnniversaryShows(
    matches: [ShowSummary],
    limit: Int = maxAnniversaryShows
) -> [ShowSummary] {
    matches.shuffled().prefix(limit).sorted { $0.date > $1.date }
}

/// Fetches every favorited artist's shows for `today`'s month/day, within the bounds above.
public func showsOnDate(
    favorites: [ArtistRef],
    today: String,
    source: @escaping (Backend) -> MusicSource = sourceFor
) async throws -> [ShowSummary] {
    try await showsOnDateChecked(favorites: favorites, today: today, source: source).shows
}

/// Like `showsOnDate`, but also reports whether every artist and period request succeeded.
/// A partial list is still worth showing, but not worth caching for the day.
public func showsOnDateChecked(
    favorites: [ArtistRef],
    today: String,
    source: @escaping (Backend) -> MusicSource = sourceFor
) async throws -> (shows: [ShowSummary], complete: Bool) {
    let relisten = Array(favorites.filter { $0.backend == .relisten }.prefix(maxRelistenArtists))
    let participating = favorites.filter { $0.backend != .relisten } + relisten

    if participating.isEmpty { return ([], true) }

    enum ArtistResult {
        case success([ShowSummary], complete: Bool)
        case failure(Error)
    }

    let perArtist = try await withThrowingTaskGroup(of: ArtistResult.self) { group in
        for artist in participating {
            group.addTask {
                do {
                    let src = source(artist.backend)
                    var complete = true
                    let artistShows: [ShowSummary]
                    switch artist.backend {
                    case .phishin:
                        let allPeriods = try await src.periods(artist: artist)
                        let periods = phishInRanges(periods: allPeriods)
                        var shows: [ShowSummary] = []
                        for period in periods {
                            do {
                                shows.append(contentsOf: try await src.shows(artist: artist, period: period))
                            } catch {
                                complete = false
                            }
                        }
                        artistShows = shows
                    case .relisten:
                        guard let md = monthDay(today) else { return .success([], complete: true) }
                        let parts = md.split(separator: "-").compactMap { Int($0) }
                        guard parts.count == 2 else { return .success([], complete: true) }
                        artistShows = try await src.showsOnDate(artist: artist, month: parts[0], day: parts[1])
                    }
                    return .success(showsOnAnniversary(shows: artistShows, today: today), complete: complete)
                } catch {
                    return .failure(error)
                }
            }
        }

        var allMatches: [ShowSummary] = []
        var successCount = 0
        var allComplete = true
        var firstError: Error?

        for try await result in group {
            switch result {
            case .success(let shows, let complete):
                allMatches.append(contentsOf: shows)
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
        
        return (allMatches, allComplete)
    }

    return (pickAnniversaryShows(matches: perArtist.0), perArtist.1)
}

/// The Home screen's entry point: `showsOnDate` behind a one-entry in-memory cache.
public enum OnThisDate {
    private static var cached: (key: String, shows: [ShowSummary])?

    public static func cacheKey(favorites: [ArtistRef], today: String) -> String {
        let favPart = favorites.map { $0.key }.sorted().joined(separator: ",")
        return "\(today)|\(favPart)"
    }

    public static func load(
        favorites: [ArtistRef],
        today: String,
        source: @escaping (Backend) -> MusicSource = sourceFor
    ) async throws -> [ShowSummary] {
        if favorites.isEmpty { return [] }
        let key = cacheKey(favorites: favorites, today: today)
        if let cached = cached, cached.key == key {
            return cached.shows
        }
        let (shows, complete) = try await showsOnDateChecked(favorites: favorites, today: today, source: source)
        // A partial list cached here would hide the failed artists/periods until tomorrow.
        if complete && !shows.isEmpty {
            cached = (key, shows)
        }
        return shows
    }

    public static func resetCache() {
        cached = nil
    }
}
