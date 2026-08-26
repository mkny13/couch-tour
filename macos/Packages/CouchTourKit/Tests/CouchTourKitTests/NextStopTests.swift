import XCTest
@testable import CouchTourKit

final class NextStopTests: XCTestCase {

    func testResolveNextConsecutiveShowPicksNextShowInSameTour() {
        let candidateShows = [
            ShowSummary(artist: PHISH, date: "1997-11-16", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-11-17", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-11-19", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-11-21", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-12-30", tourName: "1997 NYE Run"),
        ]

        let next = resolveNextConsecutiveShow(
            currentDate: "1997-11-17",
            tourName: "1997 Fall Tour",
            candidateShows: candidateShows
        )

        XCTAssertEqual("1997-11-19", next?.date)
        XCTAssertEqual("1997 Fall Tour", next?.tourName)
    }

    func testResolveNextConsecutiveShowFallsBackToNextChronologicalShowIfTourEnded() {
        let candidateShows = [
            ShowSummary(artist: PHISH, date: "1997-11-17", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-12-30", tourName: "1997 NYE Run"),
            ShowSummary(artist: PHISH, date: "1997-12-31", tourName: "1997 NYE Run"),
        ]

        let next = resolveNextConsecutiveShow(
            currentDate: "1997-11-17",
            tourName: "1997 Fall Tour",
            candidateShows: candidateShows
        )

        XCTAssertEqual("1997-12-30", next?.date)
    }

    func testResolveNextConsecutiveShowReturnsNilWhenNoFutureShows() {
        let candidateShows = [
            ShowSummary(artist: PHISH, date: "1997-11-17", tourName: "1997 Fall Tour"),
        ]

        let next = resolveNextConsecutiveShow(
            currentDate: "1997-11-17",
            tourName: "1997 Fall Tour",
            candidateShows: candidateShows
        )

        XCTAssertNil(next)
    }

    // MARK: - Current Tour Shows & Oldest Unplayed Tests

    private let GRATEFUL_DEAD = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")

    func testCurrentTourShowsExcludesNotPartOfATourAndBlank() {
        let shows = [
            ShowSummary(artist: GRATEFUL_DEAD, date: "1995-07-09", tourName: notPartOfATour),
            ShowSummary(artist: GRATEFUL_DEAD, date: "1995-07-08", tourName: ""),
            ShowSummary(artist: GRATEFUL_DEAD, date: "1995-07-07", tourName: nil),
        ]

        let current = currentTourShows(shows)
        XCTAssertTrue(current.isEmpty)
    }

    func testCurrentTourShowsMatchesLatestTour() {
        let shows = [
            ShowSummary(artist: PHISH, date: "1997-11-17", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-11-16", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-08-17", tourName: "1997 Summer Tour"),
        ]

        let current = currentTourShows(shows)
        XCTAssertEqual(2, current.count)
        XCTAssertEqual(["1997-11-17", "1997-11-16"], current.map { $0.date })
    }

    func testOldestUnplayedReturnsFirstChronologicalUnplayedShow() {
        let dead = GRATEFUL_DEAD
        let candidates = [
            ShowSummary(artist: dead, date: "1977-05-07", tourName: "Spring 1977"),
            ShowSummary(artist: dead, date: "1977-05-08", tourName: "Spring 1977"),
            ShowSummary(artist: dead, date: "1977-05-09", tourName: "Spring 1977"),
        ]

        // 1977-05-07 was played
        let played = playedShowIds(from: ["relisten:grateful-dead/1977-05-07/tape-1"])
        let next = oldestUnplayed(candidates: candidates, played: played)

        XCTAssertNotNil(next)
        XCTAssertEqual("1977-05-08", next?.date)
    }

    func testOldestUnplayedDeterministicTieBreakOnArtistKey() {
        let artistA = ArtistRef(backend: .relisten, id: "aaa", name: "Artist A")
        let artistB = ArtistRef(backend: .relisten, id: "bbb", name: "Artist B")

        let candidates = [
            ShowSummary(artist: artistB, date: "1980-01-01"),
            ShowSummary(artist: artistA, date: "1980-01-01"),
        ]

        let next = oldestUnplayed(candidates: candidates, played: [])
        XCTAssertEqual("aaa", next?.artist.id)
    }

    // MARK: - Defunct Artist Tour Resolution & Mock Source Tests

    private struct TestError: Error {}

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
            throw TestError()
        }
        func search(term: String) async throws -> SearchHits {
            SearchHits()
        }
    }

    func testTourForDefunctArtistWithoutPreferenceReturnsEmptyWhenUntoured() async {
        let mock = MockMusicSource(backend: .relisten)
        mock.periodsHandler = { _ in
            [PeriodRef(id: "1995", label: "1995"), PeriodRef(id: "1994", label: "1994")]
        }
        mock.showsHandler = { artist, period in
            [
                ShowSummary(artist: artist, date: "\(period.label)-07-01", tourName: notPartOfATour),
                ShowSummary(artist: artist, date: "\(period.label)-07-02", tourName: notPartOfATour),
            ]
        }

        let shows = await tourFor(artist: GRATEFUL_DEAD, preference: nil, source: { _ in mock })
        XCTAssertTrue(shows.isEmpty)
    }

    func testTourForDefunctArtistWithTourPreferenceResolvesNamedTour() async {
        let mock = MockMusicSource(backend: .relisten)
        mock.periodsHandler = { _ in
            [
                PeriodRef(id: "1995", label: "1995"),
                PeriodRef(id: "1977", label: "1977"),
            ]
        }
        mock.showsHandler = { artist, period in
            if period.label == "1977" {
                return [
                    ShowSummary(artist: artist, date: "1977-05-08", tourName: "Spring 1977"),
                    ShowSummary(artist: artist, date: "1977-05-09", tourName: "Spring 1977"),
                    ShowSummary(artist: artist, date: "1977-10-15", tourName: "Fall 1977"),
                ]
            }
            return [ShowSummary(artist: artist, date: "1995-07-09", tourName: notPartOfATour)]
        }

        let pref = ArtistTourPreference(artistKey: GRATEFUL_DEAD.key, tourName: "Spring 1977", year: "1977")
        let shows = await tourFor(artist: GRATEFUL_DEAD, preference: pref, source: { _ in mock })

        XCTAssertEqual(2, shows.count)
        XCTAssertEqual(["1977-05-08", "1977-05-09"], shows.map { $0.date })
    }

    func testTourForDefunctArtistWithYearPreferenceResolvesAllShowsInYear() async {
        let mock = MockMusicSource(backend: .relisten)
        mock.periodsHandler = { _ in
            [
                PeriodRef(id: "1995", label: "1995"),
                PeriodRef(id: "1972", label: "1972"),
            ]
        }
        mock.showsHandler = { artist, period in
            if period.label == "1972" {
                return [
                    ShowSummary(artist: artist, date: "1972-04-08", tourName: notPartOfATour),
                    ShowSummary(artist: artist, date: "1972-08-27", tourName: notPartOfATour),
                ]
            }
            return []
        }

        let pref = ArtistTourPreference(artistKey: GRATEFUL_DEAD.key, tourName: nil, year: "1972")
        let shows = await tourFor(artist: GRATEFUL_DEAD, preference: pref, source: { _ in mock })

        XCTAssertEqual(2, shows.count)
        XCTAssertEqual(["1972-04-08", "1972-08-27"], shows.map { $0.date })
    }

    func testCurrentToursFansOutAndAppliesPreferencesPerArtist() async {
        let phishMock = MockMusicSource(backend: .phishin)
        phishMock.periodsHandler = { _ in [PeriodRef(id: "1997", label: "1997")] }
        phishMock.showsHandler = { artist, _ in
            [ShowSummary(artist: artist, date: "1997-11-17", tourName: "1997 Fall Tour")]
        }

        let deadMock = MockMusicSource(backend: .relisten)
        deadMock.periodsHandler = { _ in [PeriodRef(id: "1977", label: "1977")] }
        deadMock.showsHandler = { artist, _ in
            [ShowSummary(artist: artist, date: "1977-05-08", tourName: "Spring 1977")]
        }

        let favorites = [PHISH, GRATEFUL_DEAD]
        let preferences = [
            ArtistTourPreference(artistKey: GRATEFUL_DEAD.key, tourName: "Spring 1977", year: "1977")
        ]

        let shows = await currentTours(favorites: favorites, preferences: preferences, source: { backend in
            switch backend {
            case .phishin: return phishMock
            case .relisten: return deadMock
            }
        })

        let dates = Set(shows.map { $0.date })
        XCTAssertTrue(dates.contains("1997-11-17"))
        XCTAssertTrue(dates.contains("1977-05-08"))
    }

    func testNextStopCacheInvalidation() async {
        NextStop.resetCache()

        var phishFetchCount = 0
        let mock = MockMusicSource(backend: .phishin)
        mock.periodsHandler = { _ in
            phishFetchCount += 1
            return [PeriodRef(id: "1997", label: "1997")]
        }
        mock.showsHandler = { artist, _ in
            [ShowSummary(artist: artist, date: "1997-11-17", tourName: "1997 Fall Tour")]
        }

        let favs = [PHISH]
        let res1 = await NextStop.load(favorites: favs, today: "2026-08-26", source: { _ in mock })
        XCTAssertEqual(1, res1.count)
        XCTAssertEqual(1, phishFetchCount)

        // Cached call
        let res2 = await NextStop.load(favorites: favs, today: "2026-08-26", source: { _ in mock })
        XCTAssertEqual(1, res2.count)
        XCTAssertEqual(1, phishFetchCount)

        // Reset cache
        NextStop.resetCache()
        let res3 = await NextStop.load(favorites: favs, today: "2026-08-26", source: { _ in mock })
        XCTAssertEqual(1, res3.count)
        XCTAssertEqual(2, phishFetchCount)
    }
}
