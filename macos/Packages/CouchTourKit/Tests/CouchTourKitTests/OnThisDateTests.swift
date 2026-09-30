import XCTest
@testable import CouchTourKit

final class OnThisDateTests: XCTestCase {

    private let dead = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
    private let wsp = ArtistRef(backend: .relisten, id: "wsp", name: "Widespread Panic")

    func testMonthDayAndYearOfExtractCorrectly() {
        XCTAssertEqual("11-17", monthDay("1997-11-17"))
        XCTAssertEqual("1997", yearOf("1997-11-17"))

        XCTAssertEqual("05-08", monthDay("1977-05-08"))
        XCTAssertEqual("1977", yearOf("1977-05-08"))

        XCTAssertNil(monthDay("1997"))
        XCTAssertNil(yearOf("1997"))

        XCTAssertNil(monthDay("invalid-date"))
        XCTAssertNil(yearOf("invalid-date"))
    }

    func testShowsOnAnniversaryFiltersSameMonthDayInDifferentYear() {
        let shows = [
            ShowSummary(artist: PHISH, date: "1997-11-17"),
            ShowSummary(artist: PHISH, date: "1998-11-17"),
            ShowSummary(artist: PHISH, date: "2024-11-17"), // same year as today
            ShowSummary(artist: PHISH, date: "1997-11-18"), // different day
        ]

        let matches = showsOnAnniversary(shows: shows, today: "2024-11-17")
        XCTAssertEqual(2, matches.count)
        XCTAssertEqual(["1997-11-17", "1998-11-17"], matches.map { $0.date })
    }

    func testPhishInRangesBatchesConsecutivePeriodsUnderCap() {
        let periods = [
            PeriodRef(id: "1983-1987", label: "1983-1987", showCount: 50),
            PeriodRef(id: "1988", label: "1988", showCount: 100),
            PeriodRef(id: "1989", label: "1989", showCount: 150),
            PeriodRef(id: "1990", label: "1990", showCount: 200),
            PeriodRef(id: "1991", label: "1991", showCount: 500),
        ]

        // Cap at 400
        let batched = phishInRanges(periods: periods, cap: 400)
        // 50+100+150 = 300 (1983-1989)
        // 200 (1990-1990)
        // 500 (1991-1991)
        XCTAssertEqual(3, batched.count)
        XCTAssertEqual("1983-1989", batched[0].id)
        XCTAssertEqual(300, batched[0].showCount)
        XCTAssertEqual("1990-1990", batched[1].id)
        XCTAssertEqual(200, batched[1].showCount)
        XCTAssertEqual("1991-1991", batched[2].id)
        XCTAssertEqual(500, batched[2].showCount)
    }

    func testPickAnniversaryShowsSortsNewestFirstAndCapsCount() {
        let matches = (1980...2000).map { year in
            ShowSummary(artist: PHISH, date: "\(year)-05-08")
        }

        let picked = pickAnniversaryShows(matches: matches, limit: 5)
        XCTAssertEqual(5, picked.count)
        for i in 0..<(picked.count - 1) {
            XCTAssertGreaterThan(picked[i].date, picked[i + 1].date)
        }
    }

    private final class MockMusicSource: MusicSource {
        let backend: Backend
        var periodsHandler: ((ArtistRef) throws -> [PeriodRef])?
        var showsHandler: ((ArtistRef, PeriodRef) throws -> [ShowSummary])?
        var showsOnDateHandler: ((ArtistRef, Int, Int) throws -> [ShowSummary])?
        var periodsCalled = false
        var showsCalled = false

        init(backend: Backend) {
            self.backend = backend
        }

        func artists() async throws -> [ArtistRef] { [] }
        func periods(artist: ArtistRef) async throws -> [PeriodRef] {
            periodsCalled = true
            if let handler = periodsHandler { return try handler(artist) }
            return []
        }
        func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary] {
            showsCalled = true
            if let handler = showsHandler { return try handler(artist, period) }
            return []
        }
        func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail {
            fatalError("Not used")
        }
        func search(term: String) async throws -> SearchHits {
            SearchHits()
        }
        func showsOnDate(artist: ArtistRef, month: Int, day: Int) async throws -> [ShowSummary] {
            if let handler = showsOnDateHandler { return try handler(artist, month, day) }
            return []
        }
    }

    func testShowsOnDateQueriesFavoritedArtists() async throws {
        let phishMock = MockMusicSource(backend: .phishin)
        phishMock.periodsHandler = { _ in
            [PeriodRef(id: "1997", label: "1997", showCount: 50)]
        }
        phishMock.showsHandler = { artist, _ in
            [
                ShowSummary(artist: artist, date: "1997-11-17"),
                ShowSummary(artist: artist, date: "1997-11-18"),
            ]
        }

        let deadMock = MockMusicSource(backend: .relisten)
        deadMock.showsOnDateHandler = { artist, month, day in
            XCTAssertEqual(11, month)
            XCTAssertEqual(17, day)
            return [
                ShowSummary(artist: artist, date: "1977-11-17"),
            ]
        }

        let results = try await showsOnDate(
            favorites: [PHISH, dead],
            today: "2026-11-17",
            source: { backend in
                switch backend {
                case .phishin: return phishMock
                case .relisten: return deadMock
                }
            }
        )

        XCTAssertEqual(2, results.count)
        let dates = Set(results.map { $0.date })
        XCTAssertTrue(dates.contains("1997-11-17"))
        XCTAssertTrue(dates.contains("1977-11-17"))
    }

    func testShowsOnDateReturnsRelistenOlderAnniversariesExcludesTodayYearAndNeverCallsPeriodsOrShows() async throws {
        let moe = ArtistRef(backend: .relisten, id: "moe", name: "moe.")
        let mock = MockMusicSource(backend: .relisten)
        mock.periodsHandler = { _ in
            XCTFail("periods should never be called for Relisten On This Date")
            return []
        }
        mock.showsHandler = { _, _ in
            XCTFail("shows should never be called for Relisten On This Date")
            return []
        }
        mock.showsOnDateHandler = { artist, month, day in
            XCTAssertEqual(9, month)
            XCTAssertEqual(29, day)
            return [
                ShowSummary(artist: artist, date: "1995-09-29"),
                ShowSummary(artist: artist, date: "1998-09-29"),
                ShowSummary(artist: artist, date: "2001-09-29"),
                ShowSummary(artist: artist, date: "2007-09-29"),
                ShowSummary(artist: artist, date: "2013-09-29"),
                ShowSummary(artist: artist, date: "2026-09-29"), // Today's year, must be excluded
            ]
        }

        let results = try await showsOnDate(
            favorites: [moe],
            today: "2026-09-29",
            source: { _ in mock }
        )

        XCTAssertFalse(mock.periodsCalled)
        XCTAssertFalse(mock.showsCalled)
        let dates = results.map { $0.date }
        XCTAssertEqual(5, dates.count)
        XCTAssertEqual(["2013-09-29", "2007-09-29", "2001-09-29", "1998-09-29", "1995-09-29"], dates)
        XCTAssertFalse(dates.contains("2026-09-29"))
    }

    func testShowsOnDateCapsAtTenRelistenArtists() async throws {
        let artists = (1...12).map { ArtistRef(backend: .relisten, id: "artist-\($0)", name: "Artist \($0)") }
        let lock = NSLock()
        var queriedArtistIds: [String] = []
        let mock = MockMusicSource(backend: .relisten)
        mock.showsOnDateHandler = { artist, _, _ in
            lock.lock()
            queriedArtistIds.append(artist.id)
            lock.unlock()
            return [ShowSummary(artist: artist, date: "2000-09-29")]
        }

        let results = try await showsOnDate(
            favorites: artists,
            today: "2026-09-29",
            source: { _ in mock }
        )

        XCTAssertEqual(10, queriedArtistIds.count)
        XCTAssertEqual(Set((1...10).map { "artist-\($0)" }), Set(queriedArtistIds))
        XCTAssertEqual(8, results.count) // Capped at maxAnniversaryShows (8)
    }

    func testShowsOnDateOneFailingArtistDoesNotDropOthers() async throws {
        let ok = ArtistRef(backend: .relisten, id: "ok", name: "Ok")
        let bad = ArtistRef(backend: .relisten, id: "bad", name: "Bad")
        let mock = MockMusicSource(backend: .relisten)
        mock.showsOnDateHandler = { artist, _, _ in
            if artist.id == "bad" {
                throw APIException("500", code: 500)
            }
            return [ShowSummary(artist: artist, date: "1999-09-29")]
        }

        let results = try await showsOnDate(
            favorites: [ok, bad],
            today: "2026-09-29",
            source: { _ in mock }
        )

        XCTAssertEqual(1, results.count)
        XCTAssertEqual("1999-09-29", results[0].date)
        XCTAssertEqual("ok", results[0].artist.id)
    }

    func testOnThisDateCacheInvalidation() async throws {
        OnThisDate.resetCache()

        var phishFetchCount = 0
        let mock = MockMusicSource(backend: .phishin)
        mock.periodsHandler = { _ in
            phishFetchCount += 1
            return [PeriodRef(id: "1997", label: "1997", showCount: 50)]
        }
        mock.showsHandler = { artist, _ in
            [ShowSummary(artist: artist, date: "1997-11-17")]
        }

        let favs = [PHISH]
        let res1 = try await OnThisDate.load(favorites: favs, today: "2026-11-17", source: { _ in mock })
        XCTAssertEqual(1, res1.count)
        XCTAssertEqual(1, phishFetchCount)

        // Cached call
        let res2 = try await OnThisDate.load(favorites: favs, today: "2026-11-17", source: { _ in mock })
        XCTAssertEqual(1, res2.count)
        XCTAssertEqual(1, phishFetchCount)

        // Reset cache
        OnThisDate.resetCache()
        let res3 = try await OnThisDate.load(favorites: favs, today: "2026-11-17", source: { _ in mock })
        XCTAssertEqual(1, res3.count)
        XCTAssertEqual(2, phishFetchCount)
    }

    func testOnThisDateEmptyNotCached() async throws {
        OnThisDate.resetCache()
        
        var phishFetchCount = 0
        let mock = MockMusicSource(backend: .phishin)
        mock.periodsHandler = { _ in
            phishFetchCount += 1
            return [PeriodRef(id: "1997", label: "1997", showCount: 50)]
        }
        mock.showsHandler = { _, _ in [] } // Empty result
        
        let favs = [PHISH]
        let res1 = try await OnThisDate.load(favorites: favs, today: "2026-11-17", source: { _ in mock })
        XCTAssertEqual(0, res1.count)
        XCTAssertEqual(1, phishFetchCount)
        
        // Not cached, so should fetch again
        let res2 = try await OnThisDate.load(favorites: favs, today: "2026-11-17", source: { _ in mock })
        XCTAssertEqual(0, res2.count)
        XCTAssertEqual(2, phishFetchCount)
    }
}
