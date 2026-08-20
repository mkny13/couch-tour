import XCTest
@testable import CouchTourKit

/// Decodes real phish.in responses, trimmed — port of the browse-relevant cases in
/// ApiParsingTest.kt, plus search (D169). Login/playlist parsing is out of scope for the
/// desktop MVP (D5).
final class PhishInParsingTests: XCTestCase {

    private let decoder = JSONDecoder()

    private func fixture(_ name: String) throws -> Data { try fixtureData(name) }

    // ------------------------------------------------------------------ shows

    func testParsesAShowWithItsVenueAndTracks() throws {
        let show = try decoder.decode(Show.self, from: try fixture("show.json"))
        XCTAssertEqual("1997-11-17", show.date)
        XCTAssertEqual("McNichols Arena", show.venueName)
        XCTAssertEqual("Denver, CO", show.location)
        XCTAssertEqual("complete", show.audioStatus)
        XCTAssertEqual(3, show.tracks.count)
        XCTAssertEqual("Tweezer", show.tracks[0].title)
    }

    func testTreatsATrackWithNoAudioAsUnplayable() throws {
        let show = try decoder.decode(Show.self, from: try fixture("show.json"))
        let playable = show.tracks.filter { $0.playable }

        // Partial shows have gaps; queueing a nil mp3_url would stall playback.
        XCTAssertEqual(2, playable.count)
        XCTAssertTrue(show.tracks[0].playable)
        XCTAssertFalse(show.tracks[2].playable)
        XCTAssertNil(show.tracks[2].mp3Url)
    }

    func testReadsDurationsInMilliseconds() throws {
        let show = try decoder.decode(Show.self, from: try fixture("show.json"))
        XCTAssertEqual(1_072_274, show.tracks[0].duration)
        XCTAssertEqual("17:52", fmt(show.tracks[0].duration))
    }

    func testExposesWaveformAndSetNamePerTrack() throws {
        let track = try decoder.decode(Show.self, from: try fixture("show.json")).tracks[0]
        XCTAssertEqual("Set 1", track.setName)
        XCTAssertTrue(track.waveformImageUrl!.hasSuffix(".png"))
    }

    func testReadsLikeCountsAndWhetherTheUserLikedIt() throws {
        let show = try decoder.decode(Show.self, from: try fixture("show.json"))
        XCTAssertEqual(412, show.id)
        XCTAssertEqual(172, show.likesCount)
        XCTAssertFalse(show.likedByUser)

        let track = show.tracks[0]
        XCTAssertEqual(8435, track.id)
        XCTAssertEqual(89, track.likesCount)
        XCTAssertFalse(track.likedByUser)
    }

    // ------------------------------------------------------------------ years

    func testParsesPeriodsIncludingMultiYearRanges() throws {
        let periods = try decoder.decode([Period].self, from: try fixture("years.json"))
        XCTAssertFalse(periods.isEmpty)

        // The early entry is a range, not a year. This is the trap that makes
        // showsForPeriod pick between year= and year_range=.
        let first = periods[0]
        XCTAssertEqual("1983-1987", first.period)
        XCTAssertTrue(first.period.contains("-"))

        let single = periods.first { $0.period == "1997" }!
        XCTAssertFalse(single.period.contains("-"))
    }

    func testPeriodsReportHowManyShowsActuallyHaveAudio() throws {
        let periods = try decoder.decode([Period].self, from: try fixture("years.json"))
        let early = periods.first { $0.period == "1983-1987" }!

        // Far fewer shows have audio than exist; the browse list must show the audio count.
        XCTAssertLessThan(early.showsWithAudioCount, early.showsCount)
        XCTAssertGreaterThan(early.showsWithAudioCount, 0)
    }

    // --------------------------------------------------------------- defaults

    func testToleratesMissingOptionalFields() throws {
        let bare = try decoder.decode(Show.self, from: Data(#"{"date":"1997-02-13"}"#.utf8))
        XCTAssertNil(bare.venueName)
        XCTAssertNil(bare.location)
        XCTAssertEqual("missing", bare.audioStatus)
        XCTAssertTrue(bare.tracks.isEmpty)
    }

    func testTreatsABlankMp3UrlAsUnplayable() throws {
        let blank = try decoder.decode(Track.self, from: Data(#"{"id":1,"title":"x","mp3_url":"","audio_status":"complete"}"#.utf8))
        XCTAssertFalse(blank.playable)
    }

    func testTreatsAPresentUrlWithMissingStatusAsUnplayable() throws {
        let mismatch = try decoder.decode(Track.self, from: Data(#"{"id":1,"title":"x","mp3_url":"https://phish.in/blob/a.mp3","audio_status":"missing"}"#.utf8))
        XCTAssertFalse(mismatch.playable)
    }

    // ----------------------------------------------------------------- search

    func testParsesSearchResultsAcrossShowsAndTracks() throws {
        let results = try decoder.decode(SearchResults.self, from: try fixture("search.json"))
        XCTAssertNil(results.exactShow)
        XCTAssertEqual(["2025-07-27", "1998-08-15"], results.shows.map { $0.date })
        XCTAssertEqual(2, results.tracks.count)
    }

    func testSearchTracksCarryTheShowTheyCameFrom() throws {
        let track = try decoder.decode(SearchResults.self, from: try fixture("search.json")).tracks.first!
        // Without show_date a search hit can't be opened inside its show.
        XCTAssertEqual("1993-07-22", track.showDate)
        XCTAssertEqual("Stowe Performing Arts Center", track.venueName)
    }

    func testExactShowIsIncludedFirstWhenPresent() throws {
        let withExact = try decoder.decode(
            SearchResults.self,
            from: Data(#"{"exact_show":{"date":"1997-11-17"},"other_shows":[{"date":"1997-11-22"}]}"#.utf8)
        )
        XCTAssertEqual(["1997-11-17", "1997-11-22"], withExact.shows.map { $0.date })
    }

    func testTreatsAnEmptySearchBodyAsNoHits() throws {
        let empty = try decoder.decode(SearchResults.self, from: Data("{}".utf8))
        XCTAssertTrue(empty.shows.isEmpty)
        XCTAssertTrue(empty.tracks.isEmpty)
        XCTAssertTrue(empty.toSearchHits().isEmpty)
    }
}
