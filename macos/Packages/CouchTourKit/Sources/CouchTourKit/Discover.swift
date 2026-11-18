import Foundation

/// Home's Discover shelf (#834, replacing On This Date): a random handful of the favorited
/// artists' top-rated shows that haven't been started. Mirrors Android's `Discover.kt`.
///
/// Each backend ranks its own catalog, so "top rated" is one request per artist: phish.in's
/// top-by-likes list, Relisten's `/v2/artists/{slug}/shows/top` (sorted by `avg_rating`).
/// The network half is cached once a day; the "unstarted" filter is applied live by the caller
/// through `pickDiscoverShows`, so a show drops out as soon as it's started.

/// Relisten artists beyond this many don't participate at all. Each favorite costs one request.
public let maxRelistenArtists = 10

/// How many shows the shelf shows.
public let maxDiscoverShows = 8

/// Same all day, different tomorrow: "2026-11-18" -> 20261118. Not `hashValue`, which Swift
/// randomises per process.
public func daySeed(_ today: String) -> UInt64 {
    UInt64(today.filter(\.isNumber)) ?? 0
}

/// SplitMix64 — a tiny deterministic generator, since the standard library has no seedable one.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// `limit` of `candidates` not in `started`, in an order fixed for `today`. Canonical order
/// before the seeded shuffle keeps the answer independent of fan-out order; shuffling before
/// the started filter means starting one show swaps in the next pick without reshuffling the rest.
public func pickDiscoverShows(
    candidates: [ShowSummary],
    started: Set<String>,
    today: String,
    limit: Int = maxDiscoverShows
) -> [ShowSummary] {
    var seen = Set<String>()
    let unique = candidates.filter { seen.insert(showId(for: $0)).inserted }
        .sorted { ($0.artist.key, $0.date) < ($1.artist.key, $1.date) }
    var rng = SeededGenerator(seed: daySeed(today))
    return Array(
        unique.shuffled(using: &rng)
            .filter { !started.contains(showId(for: $0)) }
            .prefix(limit)
    )
}

/// Every favorited artist's top-rated shows, fanned out concurrently. One artist failing costs
/// only its own shows but marks the result incomplete; all failing throws.
public func topRatedShows(
    favorites: [ArtistRef],
    source: @escaping (Backend) -> MusicSource = sourceFor
) async throws -> (shows: [ShowSummary], complete: Bool) {
    let relisten = Array(favorites.filter { $0.backend == .relisten }.prefix(maxRelistenArtists))
    let participating = favorites.filter { $0.backend == .phishin } + relisten
    if participating.isEmpty { return ([], true) }

    return try await withThrowingTaskGroup(of: Result<[ShowSummary], Error>.self) { group in
        for artist in participating {
            group.addTask {
                do { return .success(try await source(artist.backend).topShows(artist: artist)) }
                catch { return .failure(error) }
            }
        }
        var shows: [ShowSummary] = []
        var successes = 0
        var firstError: Error?
        for try await result in group {
            switch result {
            case .success(let s): shows += s; successes += 1
            case .failure(let e): if firstError == nil { firstError = e }
            }
        }
        if successes == 0, let error = firstError { throw error }
        return (shows, firstError == nil)
    }
}

/// One-entry cache behind `Discover.load`. Injectable so tests don't share process-wide state.
public final class DiscoverCache: @unchecked Sendable {
    private let lock = NSLock()
    private var entry: (key: String, shows: [ShowSummary])?

    public init() {}

    func get(_ key: String) -> [ShowSummary]? {
        lock.lock(); defer { lock.unlock() }
        if let entry = entry, entry.key == key { return entry.shows }
        return nil
    }

    func set(_ key: String, _ shows: [ShowSummary]) {
        lock.lock(); defer { lock.unlock() }
        entry = (key, shows)
    }

    public func reset() {
        lock.lock(); defer { lock.unlock() }
        entry = nil
    }
}

public enum Discover {
    public static let sharedCache = DiscoverCache()

    public static func cacheKey(favorites: [ArtistRef], today: String) -> String {
        "\(today)|" + favorites.map { $0.key }.sorted().joined(separator: ",")
    }

    /// The day's candidates, unfiltered; callers apply `pickDiscoverShows` live.
    public static func load(
        favorites: [ArtistRef],
        today: String,
        cache: DiscoverCache = sharedCache,
        source: @escaping (Backend) -> MusicSource = sourceFor
    ) async throws -> [ShowSummary] {
        if favorites.isEmpty { return [] }
        let key = cacheKey(favorites: favorites, today: today)
        if let hit = cache.get(key) { return hit }
        let (shows, complete) = try await topRatedShows(favorites: favorites, source: source)
        // A partial list cached here would hide the failed artists until tomorrow.
        if complete && !shows.isEmpty { cache.set(key, shows) }
        return shows
    }
}
