import XCTest
@testable import CouchTourKit

/// Decodes the `contract_*.json` fixtures, which `scripts/contracts/record.sh` records from
/// the live phish.in and Relisten APIs (D319). Unlike the hand-shaped fixtures elsewhere,
/// upstream drift shows up here when they're re-recorded — on the Kotlin side too, so a drift
/// that only breaks CouchTourKit still fails CI rather than a user's Mac. A decode failure on
/// a recorded file is a real DTO bug, not a test problem.
///
/// The endpoint list mirrors Android's `ContractFixturesTest`, so both clients are held to the
/// same recordings.
final class ContractFixturesTests: XCTestCase {

    private let decoder = JSONDecoder()

    private func fixture(_ name: String) throws -> Data { try fixtureData(name) }

    private var server: MockServer!

    override func setUp() {
        super.setUp()
        server = MockServer()
        server.start()
        PhishInAPI.baseURL = URL(string: "https://mock.test/api/v2")!
        RelistenAPI.baseURL = URL(string: "https://mock.test/api")!
    }

    override func tearDown() {
        server.shutdown()
        PhishInAPI.baseURL = URL(string: "https://phish.in/api/v2")!
        RelistenAPI.baseURL = URL(string: "https://api.relisten.net/api")!
        super.tearDown()
    }

    // ----------------------------------------------------------------- phish.in

    func testPhishInYearsDecode() throws {
        let periods = try decoder.decode([Period].self, from: try fixture("contract_phishin_years.json"))

        XCTAssertFalse(periods.isEmpty)
        XCTAssertTrue(periods.allSatisfy { !$0.period.isEmpty })
        // `PhishInAPI.years()` drops every period with no audio at all, so a fixture that
        // decoded to nothing but empty periods would be untestable in production.
        XCTAssertTrue(periods.contains { $0.showsWithAudioCount > 0 })
    }

    func testPhishInShowsForAYearDecode() throws {
        let page = try decoder.decode(ShowsPage.self, from: try fixture("contract_phishin_shows_year.json"))

        XCTAssertFalse(page.shows.isEmpty)
        // One recording request per year, so every show in the page belongs to it.
        XCTAssertTrue(page.shows.allSatisfy { $0.date.hasPrefix("1997") })
    }

    func testPhishInShowDecodesWithTracks() throws {
        let show = try decoder.decode(Show.self, from: try fixture("contract_phishin_show.json"))

        XCTAssertEqual("1997-11-22", show.date)
        XCTAssertFalse(show.tracks.isEmpty)
        // `playable` is what the queue builder reads — a show that decoded to nil mp3_urls
        // would decode fine and still be unplayable.
        XCTAssertTrue(show.tracks.contains { $0.playable })
    }

    func testPhishInSearchDecodes() throws {
        let results = try decoder.decode(SearchResults.self, from: try fixture("contract_phishin_search.json"))

        XCTAssertTrue(!results.shows.isEmpty || !results.tracks.isEmpty)
    }

    func testPhishInPlaylistsStayLoadable() throws {
        // The one recorded endpoint this client has no DTO for: the desktop MVP has no
        // playlists screen (D5), so there is no Swift model to drift. Parsed generically so
        // the fixture is at least proven to be the shape `record.sh` promised; the weekly
        // shape check (D320) is what actually guards drift here.
        let payload = try JSONSerialization.jsonObject(
            with: try fixture("contract_phishin_playlists.json")
        ) as? [String: Any]
        let playlists = payload?["playlists"] as? [Any]

        XCTAssertNotNil(payload?["playlists"])
        XCTAssertFalse(playlists?.isEmpty ?? true)
    }

    func testPhishInShowDecodesThroughTheRealClient() async throws {
        server.enqueue(try fixtureString("contract_phishin_show.json"))

        let show = try await PhishInAPI.show("1997-11-22")

        XCTAssertEqual("/api/v2/shows/1997-11-22", server.takeRequest()?.url?.path)
        XCTAssertEqual("1997-11-22", show.date)
        XCTAssertFalse(show.tracks.isEmpty)
    }

    // ------------------------------------------------------------------ Relisten

    func testRelistenArtistsDecode() throws {
        let artists = try decoder.decode([RelistenArtist].self, from: try fixture("contract_relisten_artists.json"))

        XCTAssertFalse(artists.isEmpty)
        // The uuid, not the slug, is what every other Relisten endpoint is fetched by.
        XCTAssertTrue(artists.allSatisfy { !$0.uuid.isEmpty && !$0.slug.isEmpty })
    }

    func testRelistenYearsDecode() throws {
        let years = try decoder.decode([RelistenYear].self, from: try fixture("contract_relisten_years.json"))

        XCTAssertFalse(years.isEmpty)
        XCTAssertTrue(years.allSatisfy { !$0.uuid.isEmpty && !$0.year.isEmpty })
    }

    func testRelistenYearDecodesWithShows() throws {
        let year = try decoder.decode(RelistenYearWithShows.self, from: try fixture("contract_relisten_year.json"))

        XCTAssertEqual("1997", year.year)
        XCTAssertFalse(year.shows.isEmpty)
    }

    func testRelistenShowDecodesWithSources() throws {
        let show = try decoder.decode(RelistenShowWithSources.self, from: try fixture("contract_relisten_show.json"))

        XCTAssertEqual("1997-11-22", show.displayDate)
        XCTAssertFalse(show.sources.isEmpty)
        // Sets are what the track list is flattened out of, so an empty one means a show that
        // decodes but plays nothing.
        XCTAssertFalse(show.sources[0].sets.isEmpty)
        XCTAssertTrue(show.sources[0].sets.flatMap(\.tracks).contains { $0.mp3Url?.isEmpty == false })
    }

    func testRelistenOnDateDecodes() throws {
        let shows = try decoder.decode([RelistenShowSummary].self, from: try fixture("contract_relisten_on_date.json"))

        XCTAssertFalse(shows.isEmpty)
        XCTAssertTrue(shows.allSatisfy { $0.displayDate.hasSuffix("-11-22") })
    }

    func testRelistenSearchDecodes() throws {
        let results = try decoder.decode(RelistenSearchResults.self, from: try fixture("contract_relisten_search.json"))

        // The recorded term only hits the Songs bucket, so asserting on all four would pin
        // the recording rather than the DTO.
        XCTAssertFalse(results.songs.isEmpty)
        XCTAssertTrue(results.toSearchHits().slices.contains { $0.kind == .song })
    }

    func testRelistenShowDecodesThroughTheRealClient() async throws {
        server.enqueue(try fixtureString("contract_relisten_show.json"))

        let show = try await RelistenAPI.show(artistIdOrSlug: "phish", date: "1997-11-22")

        XCTAssertEqual("/api/v2/artists/phish/shows/1997-11-22", server.takeRequest()?.url?.path)
        XCTAssertEqual("1997-11-22", show.displayDate)
        XCTAssertFalse(show.sources.isEmpty)
    }

    // ------------------------------------------------------------ bundled assets

    func testCuratedMatchesLoadFromTheShippedBundle() {
        // Read through `CuratedMatches`, not the file path the other asset tests use, so this
        // covers the bundle actually shipping the resource (D319).
        let match = CuratedMatches.shared.match(backend: .phishin, artistId: "phish", date: "1995-11-14")

        XCTAssertNotNil(match)
    }

    func testHeuristicMatchesLoadFromTheShippedBundle() {
        let match = HeuristicMatches.shared.match(backend: .phishin, artistId: "phish", date: "1994-06-22")

        XCTAssertNotNil(match)
    }
}