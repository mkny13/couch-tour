import Foundation

/// Past this many shows in one `year_range=` request, phish.in's `per_page=1000` would truncate the page.
public let phishInRangeCap = 900

/// Total Relisten year-fetches allowed across every favorited artist, split evenly between them.
public let relistenYearBudget = 12

/// Relisten artists beyond this many don't participate at all.
public let maxRelistenArtists = 3

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

/// Years per artist, once `relistenYearBudget` is split between them.
public func relistenYearBudget(artistCount: Int, budget: Int = relistenYearBudget) -> Int {
    if artistCount <= 0 { return 0 }
    return max(1, budget / artistCount)
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
) async -> [ShowSummary] {
    let relisten = Array(favorites.filter { $0.backend == .relisten }.prefix(maxRelistenArtists))
    let yearsEach = relistenYearBudget(artistCount: relisten.count)
    let participating = favorites.filter { $0.backend != .relisten } + relisten

    if participating.isEmpty { return [] }

    let perArtist = await withTaskGroup(of: [ShowSummary].self) { group in
        for artist in participating {
            group.addTask {
                do {
                    let src = source(artist.backend)
                    let allPeriods = try await src.periods(artist: artist)
                    let periods: [PeriodRef]
                    switch artist.backend {
                    case .phishin:
                        periods = phishInRanges(periods: allPeriods)
                    case .relisten:
                        periods = Array(allPeriods.sorted { $0.label > $1.label }.prefix(yearsEach))
                    }

                    var artistShows: [ShowSummary] = []
                    for period in periods {
                        if let shows = try? await src.shows(artist: artist, period: period) {
                            artistShows.append(contentsOf: shows)
                        }
                    }
                    return showsOnAnniversary(shows: artistShows, today: today)
                } catch {
                    return []
                }
            }
        }

        var allMatches: [ShowSummary] = []
        for await shows in group {
            allMatches.append(contentsOf: shows)
        }
        return allMatches
    }

    return pickAnniversaryShows(matches: perArtist)
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
    ) async -> [ShowSummary] {
        if favorites.isEmpty { return [] }
        let key = cacheKey(favorites: favorites, today: today)
        if let cached = cached, cached.key == key {
            return cached.shows
        }
        let shows = await showsOnDate(favorites: favorites, today: today, source: source)
        cached = (key, shows)
        return shows
    }

    public static func resetCache() {
        cached = nil
    }
}
