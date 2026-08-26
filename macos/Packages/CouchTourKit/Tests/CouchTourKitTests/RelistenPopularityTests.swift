import XCTest
@testable import CouchTourKit

final class RelistenPopularityTests: XCTestCase {

    private let decoder = JSONDecoder()

    private func fixture(_ name: String) throws -> Data { try fixtureData(name) }

    private let deadArtist = ArtistRef(
        backend: .relisten,
        id: "grateful-dead",
        name: "Grateful Dead",
        hasSets: false,
        hasMultipleSources: true
    )

    // ----------------------------------------------------------- JSON Decoding

    func testDecodesRelistenPopularityWithAllWindows() throws {
        let json = """
        {
            "momentum_score": 0.7806,
            "trend_ratio": 0.8673,
            "windows": {
                "48h": {
                    "plays": 612,
                    "hours": 95.3917,
                    "hot_score": 24.7386
                },
                "7d": {
                    "plays": 1640,
                    "hours": 251.5275,
                    "hot_score": 40.4969
                },
                "30d": {
                    "plays": 8457,
                    "hours": 1311.8942,
                    "hot_score": 91.9619
                }
            }
        }
        """.data(using: .utf8)!

        let popularity = try decoder.decode(RelistenPopularity.self, from: json)
        XCTAssertEqual(0.7806, popularity.momentumScore, accuracy: 0.0001)
        XCTAssertEqual(0.8673, popularity.trendRatio, accuracy: 0.0001)
        XCTAssertEqual(3, popularity.windows.count)

        let w48h = try XCTUnwrap(popularity.windows["48h"])
        XCTAssertEqual(612, w48h.plays)
        XCTAssertEqual(95.3917, w48h.hours, accuracy: 0.0001)
        XCTAssertEqual(24.7386, w48h.hotScore, accuracy: 0.0001)

        let w7d = try XCTUnwrap(popularity.windows["7d"])
        XCTAssertEqual(1640, w7d.plays)
        XCTAssertEqual(251.5275, w7d.hours, accuracy: 0.0001)
        XCTAssertEqual(40.4969, w7d.hotScore, accuracy: 0.0001)

        let w30d = try XCTUnwrap(popularity.windows["30d"])
        XCTAssertEqual(8457, w30d.plays)
        XCTAssertEqual(1311.8942, w30d.hours, accuracy: 0.0001)
        XCTAssertEqual(91.9619, w30d.hotScore, accuracy: 0.0001)

        // Helper getters
        XCTAssertEqual(24.7386, popularity.hotScore48h, accuracy: 0.0001)
        XCTAssertEqual(40.4969, popularity.hotScore7d, accuracy: 0.0001)
        XCTAssertEqual(91.9619, popularity.hotScore30d, accuracy: 0.0001)
        XCTAssertEqual(612, popularity.plays48h)
        XCTAssertEqual(1640, popularity.plays7d)
        XCTAssertEqual(8457, popularity.plays30d)
    }

    func testDecodesEmptyAndPartialPopularityGracefully() throws {
        let emptyJson = "{}".data(using: .utf8)!
        let popularity = try decoder.decode(RelistenPopularity.self, from: emptyJson)
        XCTAssertEqual(0.0, popularity.momentumScore)
        XCTAssertEqual(0.0, popularity.trendRatio)
        XCTAssertTrue(popularity.windows.isEmpty)
        XCTAssertEqual(0.0, popularity.hotScore48h)
        XCTAssertEqual(0.0, popularity.hotScore7d)
        XCTAssertEqual(0.0, popularity.hotScore30d)
        XCTAssertEqual(0, popularity.plays48h)
        XCTAssertEqual(0, popularity.plays7d)
        XCTAssertEqual(0, popularity.plays30d)
    }

    func testDecodesPopularityFromRelistenYearFixture() throws {
        let yearDetail = try decoder.decode(RelistenYearWithShows.self, from: try fixture("relisten_year.json"))
        let may4 = try XCTUnwrap(yearDetail.shows.first { $0.displayDate == "1977-05-04" })
        let pop = try XCTUnwrap(may4.popularity)

        XCTAssertEqual(0.5524, pop.momentumScore, accuracy: 0.0001)
        XCTAssertEqual(0.7276, pop.trendRatio, accuracy: 0.0001)
        XCTAssertEqual(10.8628, pop.hotScore48h, accuracy: 0.0001)
        XCTAssertEqual(22.0907, pop.hotScore7d, accuracy: 0.0001)
        XCTAssertEqual(44.7549, pop.hotScore30d, accuracy: 0.0001)
        XCTAssertEqual(118, pop.plays48h)
        XCTAssertEqual(488, pop.plays7d)
        XCTAssertEqual(2003, pop.plays30d)

        let summary = may4.toShowSummary(artist: deadArtist)
        XCTAssertEqual(0.5524, summary.momentumScore, accuracy: 0.0001)
        XCTAssertEqual(0.7276, summary.trendRatio, accuracy: 0.0001)
        XCTAssertEqual(10.8628, summary.hotScore48h, accuracy: 0.0001)
        XCTAssertEqual(22.0907, summary.hotScore7d, accuracy: 0.0001)
        XCTAssertEqual(44.7549, summary.hotScore30d, accuracy: 0.0001)
    }

    func testDecodesPopularityFromRelistenShowFixture() throws {
        let show = try decoder.decode(RelistenShowWithSources.self, from: try fixture("relisten_show.json"))
        let pop = try XCTUnwrap(show.popularity)

        XCTAssertEqual(0.7806, pop.momentumScore, accuracy: 0.0001)
        XCTAssertEqual(0.8673, pop.trendRatio, accuracy: 0.0001)
        XCTAssertEqual(24.7386, pop.hotScore48h, accuracy: 0.0001)
        XCTAssertEqual(40.4969, pop.hotScore7d, accuracy: 0.0001)
        XCTAssertEqual(91.9619, pop.hotScore30d, accuracy: 0.0001)

        let detail = show.toShowDetail(artist: deadArtist)
        XCTAssertEqual(0.7806, detail.summary.momentumScore, accuracy: 0.0001)
        XCTAssertEqual(0.8673, detail.summary.trendRatio, accuracy: 0.0001)
        XCTAssertEqual(24.7386, detail.summary.hotScore48h, accuracy: 0.0001)
    }

    func testDecodesPopularityFromRelistenYearsFixture() throws {
        let years = try decoder.decode([RelistenYear].self, from: try fixture("relisten_years.json"))
        XCTAssertFalse(years.isEmpty)
        let firstWithPop = years.first { $0.popularity != nil }
        XCTAssertNotNil(firstWithPop)
        XCTAssertGreaterThan(firstWithPop?.popularity?.momentumScore ?? 0, 0)
    }

    // ------------------------------------------------------------- Show Sorting

    private func makeShow(
        date: String,
        rating: Double = 0.0,
        momentum: Double = 0.0,
        trendRatio: Double = 0.0,
        hot48h: Double = 0.0,
        hot7d: Double = 0.0,
        hot30d: Double = 0.0
    ) -> ShowSummary {
        let windows = [
            "48h": WindowPopularity(plays: Int(hot48h * 10), hours: hot48h, hotScore: hot48h),
            "7d": WindowPopularity(plays: Int(hot7d * 10), hours: hot7d, hotScore: hot7d),
            "30d": WindowPopularity(plays: Int(hot30d * 10), hours: hot30d, hotScore: hot30d),
        ]
        let popularity = RelistenPopularity(
            momentumScore: momentum,
            trendRatio: trendRatio,
            windows: windows
        )
        return ShowSummary(
            artist: deadArtist,
            date: date,
            rating: rating,
            popularity: popularity
        )
    }

    func testSortShowsByDateDesc() {
        let s1 = makeShow(date: "1977-05-08")
        let s2 = makeShow(date: "1977-05-09")
        let s3 = makeShow(date: "1972-04-08")

        let sorted = sortShows([s1, s2, s3], by: .dateDesc)
        XCTAssertEqual(["1977-05-09", "1977-05-08", "1972-04-08"], sorted.map { $0.date })
    }

    func testSortShowsByDateAsc() {
        let s1 = makeShow(date: "1977-05-08")
        let s2 = makeShow(date: "1977-05-09")
        let s3 = makeShow(date: "1972-04-08")

        let sorted = sortShows([s1, s2, s3], by: .dateAsc)
        XCTAssertEqual(["1972-04-08", "1977-05-08", "1977-05-09"], sorted.map { $0.date })
    }

    func testSortShowsByRatingDesc() {
        let s1 = makeShow(date: "1977-05-08", rating: 9.8)
        let s2 = makeShow(date: "1977-05-09", rating: 8.5)
        let s3 = makeShow(date: "1972-04-08", rating: 9.8) // tie breaks by date desc

        let sorted = sortShows([s2, s3, s1], by: .ratingDesc)
        XCTAssertEqual(["1977-05-08", "1972-04-08", "1977-05-09"], sorted.map { $0.date })
    }

    func testSortShowsByTrending48h() {
        let s1 = makeShow(date: "1977-05-08", momentum: 0.8, hot48h: 25.0)
        let s2 = makeShow(date: "1977-05-09", momentum: 0.9, hot48h: 50.0)
        let s3 = makeShow(date: "1972-04-08", momentum: 0.95, hot48h: 25.0) // tie breaks by momentum

        let sorted = sortShows([s1, s2, s3], by: .trending48h)
        XCTAssertEqual(["1977-05-09", "1972-04-08", "1977-05-08"], sorted.map { $0.date })
    }

    func testSortShowsByHot7d() {
        let s1 = makeShow(date: "1977-05-08", momentum: 0.5, hot7d: 80.0)
        let s2 = makeShow(date: "1977-05-09", momentum: 0.9, hot7d: 40.0)
        let s3 = makeShow(date: "1972-04-08", momentum: 0.7, hot7d: 80.0) // tie breaks by momentum

        let sorted = sortShows([s2, s1, s3], by: .hot7d)
        XCTAssertEqual(["1972-04-08", "1977-05-08", "1977-05-09"], sorted.map { $0.date })
    }

    func testSortShowsByPopular30d() {
        let s1 = makeShow(date: "1977-05-08", momentum: 0.4, hot30d: 100.0)
        let s2 = makeShow(date: "1977-05-09", momentum: 0.8, hot30d: 250.0)
        let s3 = makeShow(date: "1972-04-08", momentum: 0.6, hot30d: 100.0) // tie breaks by momentum

        let sorted = sortShows([s1, s2, s3], by: .popular30d)
        XCTAssertEqual(["1977-05-09", "1972-04-08", "1977-05-08"], sorted.map { $0.date })
    }

    func testSortShowsByMomentum() {
        let s1 = makeShow(date: "1977-05-08", momentum: 0.92, trendRatio: 0.7)
        let s2 = makeShow(date: "1977-05-09", momentum: 0.45, trendRatio: 0.9)
        let s3 = makeShow(date: "1972-04-08", momentum: 0.92, trendRatio: 0.85) // tie breaks by trendRatio

        let sorted = sortShows([s2, s1, s3], by: .momentum)
        XCTAssertEqual(["1972-04-08", "1977-05-08", "1977-05-09"], sorted.map { $0.date })
    }

    func testSortShowsOptionsProperties() {
        XCTAssertEqual(7, ShowSortOption.allCases.count)
        XCTAssertEqual("dateDesc", ShowSortOption.dateDesc.id)
        XCTAssertEqual("Date (Newest First)", ShowSortOption.dateDesc.displayName)
        XCTAssertEqual("Date (Oldest First)", ShowSortOption.dateAsc.displayName)
        XCTAssertEqual("Top Rated", ShowSortOption.ratingDesc.displayName)
        XCTAssertEqual("Trending (48h)", ShowSortOption.trending48h.displayName)
        XCTAssertEqual("Hot (7d)", ShowSortOption.hot7d.displayName)
        XCTAssertEqual("Popular (30d)", ShowSortOption.popular30d.displayName)
        XCTAssertEqual("Momentum", ShowSortOption.momentum.displayName)
    }
}
