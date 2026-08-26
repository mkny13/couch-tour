import XCTest
@testable import CouchTourKit

final class Milestone1StressTests: XCTestCase {

    private let decoder = JSONDecoder()

    private let deadArtist = ArtistRef(
        backend: .relisten,
        id: "grateful-dead",
        name: "Grateful Dead",
        hasSets: false,
        hasMultipleSources: true
    )

    private let phishArtist = PHISH

    // =========================================================================
    // 1. SYNTHETIC TAG DERIVATION UNDER MISSING, PARTIAL, OR CONFLICTING DATA
    // =========================================================================

    func testTagDerivationHandlesWhitespaceBlankAndNullTaperAndLineage() {
        let blanks = ["", "   ", "\t\n", "   \r\n  "]
        for blank in blanks {
            let rec = RecordingRef(
                id: "test-blank",
                label: "Tape",
                isSoundboard: false,
                hasFlac: false,
                taper: blank,
                lineage: blank
            )
            XCTAssertFalse(rec.looksLikeMatrix, "Blank string '\(blank)' should not trigger matrix")
            let tags = rec.tags
            XCTAssertTrue(tags.isEmpty, "No tags should be derived for purely blank non-sbd non-flac recording")
        }
    }

    func testTagDerivationHandlesMatrixVariationsAndCaseVariations() {
        let matrixVariations = [
            "Matrix",
            "matrix",
            "MATRIX",
            "SBD/AUD Matrix",
            "Matrix by Charlie Miller",
            "Matrix 4-Source",
            "Rematrixed by Dusborne",
            "A fine matrix blend",
            "Lineage: Matrix > DAT",
        ]

        for v in matrixVariations {
            let recTaper = RecordingRef(id: "rec-taper", label: "Tape", taper: v)
            XCTAssertTrue(recTaper.looksLikeMatrix, "Taper '\(v)' should match looksLikeMatrix")

            let recLineage = RecordingRef(id: "rec-lineage", label: "Tape", lineage: v)
            XCTAssertTrue(recLineage.looksLikeMatrix, "Lineage '\(v)' should match looksLikeMatrix")

            let tags = recTaper.tags
            XCTAssertEqual(1, tags.filter { $0.name == "Matrix" }.count, "Should have exactly 1 Matrix tag")
        }
    }

    func testTagDerivationDoesNotDuplicateMatrixTagWhenBothTaperAndLineageMatch() {
        let rec = RecordingRef(
            id: "both",
            label: "Tape",
            isSoundboard: true,
            hasFlac: true,
            taper: "Seamons Matrix",
            lineage: "SBD + AUD Matrix 5.1"
        )
        let tags = rec.tags
        XCTAssertEqual(3, tags.count)
        XCTAssertEqual(1, tags.filter { $0.name == "SBD" }.count)
        XCTAssertEqual(1, tags.filter { $0.name == "Matrix" }.count)
        XCTAssertEqual(1, tags.filter { $0.name == "FLAC" }.count)
    }

    func testRelistenSourceMappingCleansBlankTaperAndFallsBackToSBDOrAudience() {
        let sbdBlankTaper = RelistenSource(uuid: "s1", isSoundboard: true, taper: "   ", lineage: "")
        let sbdRec = sbdBlankTaper.toRecordingRef()
        XCTAssertEqual("Soundboard", sbdRec.label)
        XCTAssertNil(sbdRec.taper)
        XCTAssertNil(sbdRec.lineage)
        XCTAssertTrue(sbdRec.tags.contains { $0.name == "SBD" })
        XCTAssertFalse(sbdRec.tags.contains { $0.name == "Matrix" })

        let audBlankTaper = RelistenSource(uuid: "s2", isSoundboard: false, taper: "", lineage: "   ")
        let audRec = audBlankTaper.toRecordingRef()
        XCTAssertEqual("Audience", audRec.label)
        XCTAssertNil(audRec.taper)
        XCTAssertNil(audRec.lineage)
        XCTAssertTrue(audRec.tags.isEmpty)
    }

    func testTrackFlacTagDerivedWhenFlacUrlPresent() {
        let track = RelistenSourceTrack(
            uuid: "t1",
            title: "Dark Star",
            duration: 1200,
            mp3Url: "https://relisten.net/mp3/darkstar.mp3",
            flacUrl: "https://relisten.net/flac/darkstar.flac"
        )
        let playable = track.toPlayableTrack(
            artist: deadArtist,
            showDate: "1972-04-08",
            venueName: "Wembley",
            setName: "Set 2",
            tags: [Tag(name: "FLAC", description: "Lossless", priority: 5)]
        )
        XCTAssertEqual(1, playable.tags.count)
        XCTAssertEqual("FLAC", playable.tags.first?.name)
    }

    func testTagFilteringHandlesSpecialCharactersAndCaseVariations() {
        let shows = [
            ShowSummary(artist: phishArtist, date: "1997-11-22", tags: [Tag(name: "Type-II (Extended)"), Tag(name: "Jamcharts")]),
            ShowSummary(artist: phishArtist, date: "1997-12-30", tags: [Tag(name: "Jamcharts")]),
            ShowSummary(artist: phishArtist, date: "1998-04-03", tags: [Tag(name: "Bustout*")]),
        ]

        func filterShows(_ list: [ShowSummary], by tag: String) -> [ShowSummary] {
            if tag.isEmpty || tag.caseInsensitiveCompare("all") == .orderedSame { return list }
            return list.filter { show in show.tags.contains { $0.name.caseInsensitiveCompare(tag) == .orderedSame } }
        }

        XCTAssertEqual(1, filterShows(shows, by: "Type-II (Extended)").count)
        XCTAssertEqual(1, filterShows(shows, by: "type-ii (extended)").count)
        XCTAssertEqual(2, filterShows(shows, by: "JAMCHARTS").count)
        XCTAssertEqual(1, filterShows(shows, by: "Bustout*").count)
        XCTAssertEqual(3, filterShows(shows, by: "").count)
        XCTAssertEqual(3, filterShows(shows, by: "All").count)
        XCTAssertEqual(3, filterShows(shows, by: "all").count)
        XCTAssertEqual(0, filterShows(shows, by: "NonExistent").count)
    }

    // =========================================================================
    // 2. POPULARITY WINDOW PARSING & NUMERICAL SAFETY
    // =========================================================================

    func testParsesEmptyAndSparsePopularityJsonWithoutCrashing() throws {
        let emptyPopJson = """
        {
            "display_date": "1977-05-08",
            "popularity": {}
        }
        """.data(using: .utf8)!

        let parsed = try decoder.decode(RelistenShowSummary.self, from: emptyPopJson)
        let domain = parsed.toShowSummary(artist: deadArtist)
        XCTAssertNotNil(domain.popularity)
        XCTAssertEqual(0.0, domain.momentumScore)
        XCTAssertEqual(0.0, domain.hotScore48h)
        XCTAssertEqual(0.0, domain.hotScore7d)
        XCTAssertEqual(0.0, domain.hotScore30d)
    }

    func testParsesPartialWindowsWhereSomeWindowsAreMissingOrNull() throws {
        let partialPopJson = """
        {
            "display_date": "1977-05-08",
            "popularity": {
                "momentum_score": 0.42,
                "windows": {
                    "7d": {
                        "plays": 50,
                        "hours": 12.5,
                        "hot_score": 8.5
                    }
                }
            }
        }
        """.data(using: .utf8)!

        let parsed = try decoder.decode(RelistenShowSummary.self, from: partialPopJson)
        let domain = parsed.toShowSummary(artist: deadArtist)
        XCTAssertNotNil(domain.popularity)
        XCTAssertEqual(0.42, domain.momentumScore, accuracy: 0.0001)
        XCTAssertEqual(0.0, domain.hotScore48h)
        XCTAssertEqual(8.5, domain.hotScore7d, accuracy: 0.0001)
        XCTAssertEqual(0.0, domain.hotScore30d)
    }

    func testHandlesNegativeAndZeroPopularityScoresSafely() throws {
        let negPopJson = """
        {
            "display_date": "1977-05-08",
            "popularity": {
                "momentum_score": -0.85,
                "trend_ratio": -1.2,
                "windows": {
                    "48h": { "plays": 0, "hours": 0.0, "hot_score": -10.5 },
                    "7d": { "plays": 0, "hours": 0.0, "hot_score": 0.0 },
                    "30d": { "plays": 1000000, "hours": 50000.0, "hot_score": 99999.9 }
                }
            }
        }
        """.data(using: .utf8)!

        let parsed = try decoder.decode(RelistenShowSummary.self, from: negPopJson)
        let domain = parsed.toShowSummary(artist: deadArtist)
        XCTAssertEqual(-0.85, domain.momentumScore, accuracy: 0.0001)
        XCTAssertEqual(-10.5, domain.hotScore48h, accuracy: 0.0001)
        XCTAssertEqual(0.0, domain.hotScore7d, accuracy: 0.0001)
        XCTAssertEqual(99999.9, domain.hotScore30d, accuracy: 0.0001)
    }

    // =========================================================================
    // 3. MULTI-TIER SORTING STABILITY & DETERMINISM
    // =========================================================================

    func testSortsEmptyListAndSingleElementListSafelyAcrossAllModes() {
        for option in ShowSortOption.allCases {
            let emptySorted = sortShows([], by: option)
            XCTAssertTrue(emptySorted.isEmpty)

            let single = [ShowSummary(artist: phishArtist, date: "1997-11-22")]
            let singleSorted = sortShows(single, by: option)
            XCTAssertEqual(1, singleSorted.count)
            XCTAssertEqual("1997-11-22", singleSorted.first?.date)
        }
    }

    func testMultiTierTieBreakingInTrending48h() {
        let s1 = ShowSummary(artist: deadArtist, date: "1977-05-01", popularity: RelistenPopularity(momentumScore: 0.5, windows: ["48h": WindowPopularity(hotScore: 10.0)]))
        let s2 = ShowSummary(artist: deadArtist, date: "1977-05-02", popularity: RelistenPopularity(momentumScore: 0.5, windows: ["48h": WindowPopularity(hotScore: 20.0)]))
        let s3 = ShowSummary(artist: deadArtist, date: "1977-05-03", popularity: RelistenPopularity(momentumScore: 0.9, windows: ["48h": WindowPopularity(hotScore: 20.0)]))
        let s4 = ShowSummary(artist: deadArtist, date: "1977-05-05", popularity: RelistenPopularity(momentumScore: 0.9, windows: ["48h": WindowPopularity(hotScore: 20.0)]))

        let list = [s1, s3, s4, s2]
        let sorted = sortShows(list, by: .trending48h)
        XCTAssertEqual(["1977-05-05", "1977-05-03", "1977-05-02", "1977-05-01"], sorted.map { $0.date })
    }

    func testMultiTierTieBreakingInHot7dAndPopular30d() {
        let s1 = ShowSummary(artist: deadArtist, date: "1977-05-01", popularity: RelistenPopularity(momentumScore: 0.2, windows: ["7d": WindowPopularity(hotScore: 50.0), "30d": WindowPopularity(hotScore: 100.0)]))
        let s2 = ShowSummary(artist: deadArtist, date: "1977-05-02", popularity: RelistenPopularity(momentumScore: 0.8, windows: ["7d": WindowPopularity(hotScore: 50.0), "30d": WindowPopularity(hotScore: 100.0)]))
        let s3 = ShowSummary(artist: deadArtist, date: "1977-05-04", popularity: RelistenPopularity(momentumScore: 0.8, windows: ["7d": WindowPopularity(hotScore: 50.0), "30d": WindowPopularity(hotScore: 100.0)]))

        let list = [s1, s3, s2]
        let sorted7d = sortShows(list, by: .hot7d)
        XCTAssertEqual(["1977-05-04", "1977-05-02", "1977-05-01"], sorted7d.map { $0.date })

        let sorted30d = sortShows(list, by: .popular30d)
        XCTAssertEqual(["1977-05-04", "1977-05-02", "1977-05-01"], sorted30d.map { $0.date })
    }

    func testMomentumSortTieBreaksByTrendRatioThenDate() {
        let s1 = ShowSummary(artist: deadArtist, date: "1977-05-01", popularity: RelistenPopularity(momentumScore: 0.8, trendRatio: 0.5))
        let s2 = ShowSummary(artist: deadArtist, date: "1977-05-02", popularity: RelistenPopularity(momentumScore: 0.8, trendRatio: 1.2))
        let s3 = ShowSummary(artist: deadArtist, date: "1977-05-04", popularity: RelistenPopularity(momentumScore: 0.8, trendRatio: 1.2))

        let list = [s2, s1, s3]
        let sorted = sortShows(list, by: .momentum)
        XCTAssertEqual(["1977-05-04", "1977-05-02", "1977-05-01"], sorted.map { $0.date })
    }

    func testSorting1000RandomizedShowsIsDeterministic() {
        var rng = SystemRandomNumberGenerator()
        let dates = (1970...2024).flatMap { year in
            (1...12).map { month in
                String(format: "%04d-%02d-15", year, month)
            }
        }

        let generated: [ShowSummary] = (1...1000).map { i in
            let date = dates[Int.random(in: 0..<dates.count, using: &rng)]
            let rating = Double.random(in: 0.0...10.0, using: &rng)
            let hasPop = Bool.random(using: &rng)
            let pop: RelistenPopularity? = hasPop ? RelistenPopularity(
                momentumScore: Double.random(in: 0.0...1.0, using: &rng),
                trendRatio: Double.random(in: 0.0...2.0, using: &rng),
                windows: [
                    "48h": WindowPopularity(hotScore: Double.random(in: 0.0...100.0, using: &rng)),
                    "7d": WindowPopularity(hotScore: Double.random(in: 0.0...500.0, using: &rng)),
                    "30d": WindowPopularity(hotScore: Double.random(in: 0.0...2000.0, using: &rng)),
                ]
            ) : nil
            return ShowSummary(
                artist: Bool.random(using: &rng) ? deadArtist : phishArtist,
                date: date,
                rating: rating,
                popularity: pop
            )
        }

        for option in ShowSortOption.allCases {
            let pass1 = sortShows(generated, by: option)
            let pass2 = sortShows(generated, by: option)
            XCTAssertEqual(pass1.map { $0.date }, pass2.map { $0.date }, "Sort option \(option) must be deterministic")
        }
    }
}
