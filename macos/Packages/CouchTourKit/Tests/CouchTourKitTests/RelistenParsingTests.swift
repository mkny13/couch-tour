import XCTest
@testable import CouchTourKit

/// Decodes real Relisten responses, trimmed, plus the pure mapping into the backend-neutral
/// model — port of RelistenParsingTest.kt, plus search (D169). See the Android repo's
/// MULTI-ARTIST-PLAN.md "Verified against the live API" for where the facts pinned here
/// came from.
final class RelistenParsingTests: XCTestCase {

    private let decoder = JSONDecoder()

    private func fixture(_ name: String) throws -> Data { try fixtureData(name) }

    private let deadArtist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead", hasSets: false, hasMultipleSources: true)

    // ---------------------------------------------------------------- artists

    func testParsesArtistsAndTheirFeatureFlags() throws {
        let artists = try decoder.decode([RelistenArtist].self, from: try fixture("relisten_artists.json"))
        XCTAssertEqual(["phish", "grateful-dead", "wsp"], artists.map { $0.slug })
    }

    func testPhishHasSetsButNoTapeToChoose() throws {
        let artists = try decoder.decode([RelistenArtist].self, from: try fixture("relisten_artists.json"))
        let phish = artists.first { $0.slug == "phish" }!.toArtistRef()
        let dead = artists.first { $0.slug == "grateful-dead" }!.toArtistRef()

        XCTAssertTrue(phish.hasSets)
        XCTAssertFalse(phish.hasMultipleSources)
        XCTAssertFalse(dead.hasSets)
        XCTAssertTrue(dead.hasMultipleSources)
    }

    // ------------------------------------------------------------------ years

    func testParsesYearsIntoPeriodRefsKeyedByUuid() throws {
        let years = try decoder.decode([RelistenYear].self, from: try fixture("relisten_years.json"))
        let first = years[0].toPeriodRef()
        XCTAssertEqual("1965", first.label)
        XCTAssertEqual(1, first.showCount)
        // The uuid, not the year string, is what a Relisten period is fetched by.
        XCTAssertEqual(years[0].uuid, first.id)
    }

    func testParsesAYearsShowsIncludingVenueAndTour() throws {
        let detail = try decoder.decode(RelistenYearWithShows.self, from: try fixture("relisten_year.json"))
        XCTAssertEqual("1977", detail.year)

        let cornell = detail.shows.first { $0.displayDate == "1977-05-08" }!.toShowSummary(artist: deadArtist)
        XCTAssertEqual(deadArtist, cornell.artist)
        XCTAssertEqual("Barton Hall, Cornell University", cornell.venue)
        XCTAssertEqual("Ithaca, NY, USA", cornell.location)
        XCTAssertEqual("Spring 1977", cornell.tourName)
        XCTAssertEqual(10, cornell.recordingCount)
    }

    // ------------------------------------------------------------- show + tapes

    func testDefaultsToTheFirstSourceBecauseRelistenAlreadySortsByRating() throws {
        // Do NOT tie-break on is_soundboard: the soundboard here ranks 4th (8.212 against
        // 8.260), so picking it would override Relisten's own ranking with a worse tape.
        let show = try decoder.decode(RelistenShowWithSources.self, from: try fixture("relisten_show.json"))
        let detail = show.toShowDetail(artist: deadArtist)

        XCTAssertEqual("848d7cec-2b6d-faee-7661-ce4abd18cb01", detail.recording?.id)
        XCTAssertFalse(detail.recording!.isSoundboard)
        XCTAssertEqual(3, detail.alternates.count)
    }

    func testAnExplicitRecordingIdIsHonouredOverTheDefault() throws {
        let show = try decoder.decode(RelistenShowWithSources.self, from: try fixture("relisten_show.json"))
        let soundboardId = "0a1e8672-06ef-5fe6-5717-02061dcaf53e"

        let detail = show.toShowDetail(artist: deadArtist, recordingId: soundboardId)

        XCTAssertEqual(soundboardId, detail.recording?.id)
        XCTAssertTrue(detail.recording!.isSoundboard)
    }

    func testAnUnknownRecordingIdFallsBackToTheDefault() throws {
        let show = try decoder.decode(RelistenShowWithSources.self, from: try fixture("relisten_show.json"))
        let detail = show.toShowDetail(artist: deadArtist, recordingId: "not-a-real-uuid")
        XCTAssertEqual("848d7cec-2b6d-faee-7661-ce4abd18cb01", detail.recording?.id)
    }

    func testConvertsTrackDurationFromSecondsToMilliseconds() throws {
        // 325 seconds = 5:25. Everything else in this package is milliseconds; a track this
        // far off is the kind of bug that looks fine until someone opens the scrubber.
        let show = try decoder.decode(RelistenShowWithSources.self, from: try fixture("relisten_show.json"))
        let detail = show.toShowDetail(artist: deadArtist)
        XCTAssertEqual("Minglewood Blues", detail.tracks[0].title)
        XCTAssertEqual(325_000, detail.tracks[0].durationMs)
    }

    func testKeepsEveryTrackOfTheChosenSourceNotTheDefaultsNeighbours() throws {
        // The empirical case for keying progress on the source: Cornell's tapes really do
        // carry different track counts (20 on the default tape, 25 on the soundboard).
        let show = try decoder.decode(RelistenShowWithSources.self, from: try fixture("relisten_show.json"))
        XCTAssertEqual(20, show.toShowDetail(artist: deadArtist).tracks.count)
        XCTAssertEqual(25, show.toShowDetail(artist: deadArtist, recordingId: "0a1e8672-06ef-5fe6-5717-02061dcaf53e").tracks.count)
    }

    func testAShowWithNoSourcesHasNoRecordingAndNoTracks() {
        let empty = RelistenShowWithSources(displayDate: "2001-04-22")
        let detail = empty.toShowDetail(artist: ArtistRef(backend: .relisten, id: "wsp", name: "Widespread Panic"))
        XCTAssertNil(detail.recording)
        XCTAssertTrue(detail.tracks.isEmpty)
        XCTAssertTrue(detail.alternates.isEmpty)
    }

    // --------------------------------------------------------- set flattening

    func testFlattensSetsInIndexOrderAndDropsTracksWithNoMp3Url() throws {
        let raw = """
            {"display_date":"2001-04-22","sources":[{"uuid":"src-1","sets":[
                {"index":1,"name":"Set 2","tracks":[
                    {"uuid":"t3","title":"Third","duration":10,"mp3_url":"https://a/3.mp3"}
                ]},
                {"index":0,"name":"Set 1","tracks":[
                    {"uuid":"t1","title":"First","duration":10,"mp3_url":"https://a/1.mp3"},
                    {"uuid":"t2","title":"Missing","duration":10,"mp3_url":null}
                ]}
            ]}]}
            """
        let artist = ArtistRef(backend: .relisten, id: "wsp", name: "Widespread Panic", hasSets: true)
        let tracks = try decoder.decode(RelistenShowWithSources.self, from: Data(raw.utf8)).toShowDetail(artist: artist).tracks

        XCTAssertEqual(["First", "Third"], tracks.map { $0.title })
        XCTAssertEqual(["Set 1", "Set 2"], tracks.map { $0.setName })
    }

    func testSetNamesAreSuppressedForAnArtistWithoutRealSets() throws {
        // Dead sources carry a single wrapper set literally named "Set" — showing it would
        // render one meaningless divider on every show.
        let raw = """
            {"display_date":"1977-05-08","sources":[{"uuid":"src-1","sets":[
                {"index":0,"name":"Set","tracks":[
                    {"uuid":"t1","title":"Minglewood Blues","duration":10,"mp3_url":"https://a/1.mp3"}
                ]}
            ]}]}
            """
        let tracks = try decoder.decode(RelistenShowWithSources.self, from: Data(raw.utf8)).toShowDetail(artist: deadArtist).tracks
        XCTAssertEqual("", tracks[0].setName)
    }

    func testABlankMp3UrlIsDroppedTheSameAsANilOne() throws {
        let raw = """
            {"display_date":"2001-04-22","sources":[{"uuid":"src-1","sets":[
                {"index":0,"name":"Set 1","tracks":[
                    {"uuid":"t1","title":"Kept","duration":1,"mp3_url":"https://a/1.mp3"},
                    {"uuid":"t2","title":"Blank","duration":1,"mp3_url":""}
                ]}
            ]}]}
            """
        let artist = ArtistRef(backend: .relisten, id: "wsp", name: "Widespread Panic", hasSets: true)
        let tracks = try decoder.decode(RelistenShowWithSources.self, from: Data(raw.utf8)).toShowDetail(artist: artist).tracks
        XCTAssertEqual(["Kept"], tracks.map { $0.title })
    }

    // -------------------------------------------------------------- source picker

    func testABlankTaperFallsBackToTheSoundboardAudienceLabelRatherThanRenderingEmpty() {
        let sbd = RelistenSource(uuid: "1", isSoundboard: true, taper: "")
        XCTAssertEqual("Soundboard", sbd.toRecordingRef().label)
        XCTAssertNil(sbd.toRecordingRef().taper)

        let aud = RelistenSource(uuid: "2", isSoundboard: false, taper: "")
        XCTAssertEqual("Audience", aud.toRecordingRef().label)
    }

    func testABlankLineageBecomesNilRatherThanABareLabel() {
        let source = RelistenSource(uuid: "1", lineage: "")
        XCTAssertNil(source.toRecordingRef().lineage)
    }

    func testARealTaperAndLineageAreKeptAsIs() {
        let source = RelistenSource(uuid: "1", taper: "Jim Wise", lineage: "DAT > FLAC")
        let ref = source.toRecordingRef()
        XCTAssertEqual("Jim Wise", ref.label)
        XCTAssertEqual("Jim Wise", ref.taper)
        XCTAssertEqual("DAT > FLAC", ref.lineage)
    }

    // ----------------------------------------------------------------- search

    func testParsesEverySearchBucketAndDropsThePhishSlug() throws {
        let results = try decoder.decode(RelistenSearchResults.self, from: try fixture("relisten_search.json"))
        let hits = results.toSearchHits()

        XCTAssertEqual(["Goose"], hits.artists.map { $0.name })
        XCTAssertEqual(["1977-05-08"], hits.shows.map { $0.date })
        XCTAssertEqual("Grateful Dead", hits.shows.first?.artist.name)
        // 2 songs (grateful-dead, dark-star-orchestra) + 1 venue — the fixture's third song
        // hit is Phish's, dropped.
        XCTAssertEqual(3, hits.slices.count)
        XCTAssertTrue(hits.slices.contains { $0.kind == .venue && $0.artist.name == "Grateful Dead" })
    }

    func testDropsThePhishSlugFromSongHitsSpecifically() throws {
        let results = try decoder.decode(RelistenSearchResults.self, from: try fixture("relisten_search.json"))
        let songHits = results.toSearchHits().slices.filter { $0.kind == .song }

        XCTAssertEqual(2, songHits.count)
        XCTAssertFalse(songHits.contains { $0.artist.id == "phish" })
    }

    func testSongAndVenueHitsCarryNamespacedPeriodIds() throws {
        let hits = try decoder.decode(RelistenSearchResults.self, from: try fixture("relisten_search.json")).toSearchHits()

        let songHit = hits.slices.first { $0.kind == .song }!
        XCTAssertTrue(songHit.period.id.hasPrefix("song:"))

        let venueHit = hits.slices.first { $0.kind == .venue }!
        XCTAssertTrue(venueHit.period.id.hasPrefix("venue:"))
    }

    func testASongsShowsParseIntoShowSummaries() throws {
        let detail = try decoder.decode(RelistenSliceWithShows.self, from: try fixture("relisten_song_shows.json"))
        let summaries = detail.toShowSummaries(artist: deadArtist)

        XCTAssertEqual(["1974-03-23", "1974-05-14", "1974-05-19"], summaries.map { $0.date })
        XCTAssertEqual("Cow Palace", summaries.first?.venue)
    }

    func testAVenuesShowsParseTheSameWayWithTheVenuePopulatedUnlikeASearchHit() throws {
        let detail = try decoder.decode(RelistenSliceWithShows.self, from: try fixture("relisten_venue_shows.json"))
        let summaries = detail.toShowSummaries(artist: deadArtist)

        XCTAssertEqual(3, summaries.count)
        XCTAssertEqual("Barton Hall, Cornell University", summaries.first?.venue)
        XCTAssertEqual("Ithaca, NY, USA", summaries.first?.location)
    }
}
