import XCTest
@testable import CouchTourKit

/// Home's Discover shelf (#834): pure pick logic plus a fake `MusicSource` for the fetch.
final class DiscoverTests: XCTestCase {

    private let dead = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")

    private lazy var candidates: [ShowSummary] =
        (1...30).map { ShowSummary(artist: PHISH, date: String(format: "1997-11-%02d", $0)) } +
        (1...30).map { ShowSummary(artist: dead, date: String(format: "1977-05-%02d", $0)) }

    private struct Boom: Error {}

    private final class Source: MusicSource, @unchecked Sendable {
        let backend: Backend = .phishin
        let shows: [ShowSummary]
        let failing: Set<String>
        private let lock = NSLock()
        private var _calls = 0
        var calls: Int { lock.lock(); defer { lock.unlock() }; return _calls }
        private func bump() { lock.lock(); _calls += 1; lock.unlock() }

        init(shows: [ShowSummary], failing: Set<String> = []) {
            self.shows = shows
            self.failing = failing
        }

        func artists() async throws -> [ArtistRef] { [] }
        func periods(artist: ArtistRef) async throws -> [PeriodRef] { [] }
        func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary] { [] }
        func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail { throw Boom() }
        func search(term: String) async throws -> SearchHits { SearchHits() }
        func topShows(artist: ArtistRef) async throws -> [ShowSummary] {
            bump()
            if failing.contains(artist.key) { throw Boom() }
            return shows.filter { $0.artist == artist }
        }
    }

    // MARK: - pickDiscoverShows

    func testPickIsCappedAtTheLimit() {
        XCTAssertEqual(maxDiscoverShows, pickDiscoverShows(candidates: candidates, started: [], today: "2026-11-18").count)
    }

    func testPickIsStableWithinADayRegardlessOfOrder() {
        XCTAssertEqual(
            pickDiscoverShows(candidates: candidates, started: [], today: "2026-11-18"),
            pickDiscoverShows(candidates: candidates.reversed(), started: [], today: "2026-11-18")
        )
    }

    func testPickChangesFromOneDayToTheNext() {
        XCTAssertNotEqual(
            pickDiscoverShows(candidates: candidates, started: [], today: "2026-11-18"),
            pickDiscoverShows(candidates: candidates, started: [], today: "2026-11-19")
        )
    }

    func testStartedShowsAreExcludedAndTheRestStayPut() {
        let before = pickDiscoverShows(candidates: candidates, started: [], today: "2026-11-18")
        let startedShow = before[2]
        let after = pickDiscoverShows(candidates: candidates, started: [showId(for: startedShow)], today: "2026-11-18")
        XCTAssertFalse(after.contains(startedShow))
        XCTAssertEqual(before.filter { $0 != startedShow }, Array(after.prefix(maxDiscoverShows - 1)))
        XCTAssertEqual(maxDiscoverShows, after.count)
    }

    func testStartedSetFromProgressKeysCoversBothBackends() {
        let started = playedShowIds(from: [showQueueKey("1997-11-01"), recordingQueueKey("grateful-dead", "1977-05-01", "123")])
        let pick = pickDiscoverShows(candidates: candidates, started: started, today: "2026-11-18", limit: candidates.count)
        XCTAssertFalse(pick.contains(ShowSummary(artist: PHISH, date: "1997-11-01")))
        XCTAssertFalse(pick.contains(ShowSummary(artist: dead, date: "1977-05-01")))
        XCTAssertEqual(candidates.count - 2, pick.count)
    }

    func testEverythingStartedLeavesAnEmptyShelf() {
        let started = Set(candidates.map { showId(for: $0) })
        XCTAssertTrue(pickDiscoverShows(candidates: candidates, started: started, today: "2026-11-18").isEmpty)
    }

    // MARK: - topRatedShows / Discover.load

    func testOneArtistFailingKeepsTheOthersButIsIncomplete() async throws {
        let src = Source(shows: candidates, failing: [dead.key])
        let (shows, complete) = try await topRatedShows(favorites: [PHISH, dead], source: { _ in src })
        XCTAssertFalse(complete)
        XCTAssertEqual(candidates.filter { $0.artist == PHISH }, shows)
    }

    func testEveryArtistFailingThrows() async {
        let src = Source(shows: candidates, failing: [PHISH.key])
        do {
            _ = try await topRatedShows(favorites: [PHISH], source: { _ in src })
            XCTFail("expected a throw")
        } catch {}
    }

    func testRelistenFavoritesPastTheCapDontParticipate() async throws {
        let src = Source(shows: [])
        let many = (1...(maxRelistenArtists + 3)).map { ArtistRef(backend: .relisten, id: "a\($0)", name: "A\($0)") }
        _ = try await topRatedShows(favorites: many, source: { _ in src })
        XCTAssertEqual(maxRelistenArtists, src.calls)
    }

    func testLoadCachesACompleteDayAndRefetchesForANewDay() async throws {
        let cache = DiscoverCache()
        let src = Source(shows: candidates)
        _ = try await Discover.load(favorites: [PHISH], today: "2026-11-18", cache: cache, source: { _ in src })
        _ = try await Discover.load(favorites: [PHISH], today: "2026-11-18", cache: cache, source: { _ in src })
        XCTAssertEqual(1, src.calls)
        _ = try await Discover.load(favorites: [PHISH], today: "2026-11-19", cache: cache, source: { _ in src })
        XCTAssertEqual(2, src.calls)
    }

    func testLoadDoesNotCacheAnIncompleteResult() async throws {
        let cache = DiscoverCache()
        let src = Source(shows: candidates, failing: [dead.key])
        _ = try await Discover.load(favorites: [PHISH, dead], today: "2026-11-18", cache: cache, source: { _ in src })
        _ = try await Discover.load(favorites: [PHISH, dead], today: "2026-11-18", cache: cache, source: { _ in src })
        XCTAssertEqual(4, src.calls)
    }

    func testNoFavoritesMeansNoFetch() async throws {
        let src = Source(shows: candidates)
        let shows = try await Discover.load(favorites: [], today: "2026-11-18", cache: DiscoverCache(), source: { _ in src })
        XCTAssertTrue(shows.isEmpty)
        XCTAssertEqual(0, src.calls)
    }
}
