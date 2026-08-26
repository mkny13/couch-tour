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

    func testRelistenYearBudgetSplitsEvenly() {
        XCTAssertEqual(0, relistenYearBudget(artistCount: 0, budget: 12))
        XCTAssertEqual(12, relistenYearBudget(artistCount: 1, budget: 12))
        XCTAssertEqual(6, relistenYearBudget(artistCount: 2, budget: 12))
        XCTAssertEqual(4, relistenYearBudget(artistCount: 3, budget: 12))
        XCTAssertEqual(3, relistenYearBudget(artistCount: 4, budget: 12))
        XCTAssertEqual(1, relistenYearBudget(artistCount: 20, budget: 12))
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
        var periodsHandler: ((ArtistRef) -> [PeriodRef])?
        var showsHandler: ((ArtistRef, PeriodRef) -> [ShowSummary])?

        init(backend: Backend) {
            self.backend = backend
        }

        func artists() async throws -> [ArtistRef] { [] }
        func periods(artist: ArtistRef) async throws -> [PeriodRef] {
            periodsHandler?(artist) ?? []
        }
        func shows(artist: ArtistRef, period: PeriodRef) async throws -> [ShowSummary] {
            showsHandler?(artist, period) ?? []
        }
        func show(artist: ArtistRef, date: String, recordingId: String?) async throws -> ShowDetail {
            fatalError("Not used")
        }
        func search(term: String) async throws -> SearchHits {
            SearchHits()
        }
    }

    func testShowsOnDateQueriesFavoritedArtists() async {
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
        deadMock.periodsHandler = { _ in
            [PeriodRef(id: "1977", label: "1977", showCount: 60)]
        }
        deadMock.showsHandler = { artist, _ in
            [
                ShowSummary(artist: artist, date: "1977-11-17"),
            ]
        }

        let results = await showsOnDate(
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

    func testOnThisDateCacheInvalidation() async {
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
        let res1 = await OnThisDate.load(favorites: favs, today: "2026-11-17", source: { _ in mock })
        XCTAssertEqual(1, res1.count)
        XCTAssertEqual(1, phishFetchCount)

        // Cached call
        let res2 = await OnThisDate.load(favorites: favs, today: "2026-11-17", source: { _ in mock })
        XCTAssertEqual(1, res2.count)
        XCTAssertEqual(1, phishFetchCount)

        // Reset cache
        OnThisDate.resetCache()
        let res3 = await OnThisDate.load(favorites: favs, today: "2026-11-17", source: { _ in mock })
        XCTAssertEqual(1, res3.count)
        XCTAssertEqual(2, phishFetchCount)
    }
}
