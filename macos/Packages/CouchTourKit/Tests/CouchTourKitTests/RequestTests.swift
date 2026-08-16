import XCTest
@testable import CouchTourKit

/// Exercises the outgoing requests against a local `MockServer`, mirroring the browse-relevant
/// cases from ApiRequestTest.kt and RelistenRequestTest.kt — the parts of the request shape
/// that are invisible in the DTOs and fail silently when wrong.
final class RequestTests: XCTestCase {

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

    func testUsesYearForASingleYearPeriod() async throws {
        server.enqueue(#"{"shows":[]}"#)

        _ = try await PhishInAPI.showsForPeriod("1997")

        let request = server.takeRequest()!
        XCTAssertEqual("1997", request.queryValue("year"))
        XCTAssertNil(request.queryValue("year_range"))
    }

    func testUsesYearRangeForAMultiYearPeriod() async throws {
        // Sending a range to year= returns an empty list with no error, so this
        // distinction is the difference between a populated screen and a blank one.
        server.enqueue(#"{"shows":[]}"#)

        _ = try await PhishInAPI.showsForPeriod("1983-1987")

        let request = server.takeRequest()!
        XCTAssertEqual("1983-1987", request.queryValue("year_range"))
        XCTAssertNil(request.queryValue("year"))
    }

    func testFiltersShowListsToThoseWithAudio() async throws {
        server.enqueue(#"{"shows":[]}"#)

        _ = try await PhishInAPI.showsForPeriod("1997")

        XCTAssertEqual("complete_or_partial", server.takeRequest()!.queryValue("audio_status"))
    }

    func testRequestsShowsInDateOrder() async throws {
        server.enqueue(#"{"shows":[]}"#)

        _ = try await PhishInAPI.showsForPeriod("1997")

        XCTAssertEqual("date:asc", server.takeRequest()!.queryValue("sort"))
    }

    func testPutsTheShowDateInThePath() async throws {
        server.enqueue(#"{"date":"1997-02-13"}"#)

        _ = try await PhishInAPI.show("1997-02-13")

        XCTAssertEqual(["api", "v2", "shows", "1997-02-13"], server.takeRequest()!.pathSegments)
    }

    func testDropsPeriodsThatHaveNoAudioAtAll() async throws {
        server.enqueue(
            """
            [{"period":"1997","shows_with_audio_count":81},
             {"period":"2020","shows_with_audio_count":0}]
            """
        )

        let periods = try await PhishInAPI.years()

        XCTAssertEqual(["1997"], periods.map { $0.period })
    }

    func testRaisesTheStatusCodeOnAServerError() async {
        server.enqueue("nope", code: 500)

        do {
            _ = try await PhishInAPI.years()
            XCTFail("expected APIException")
        } catch let error as APIException {
            XCTAssertEqual(500, error.code)
            XCTAssertFalse(error.unauthorized)
        } catch {
            XCTFail("wrong error type: \(error)")
        }
    }

    func testRequestsJson() async throws {
        server.enqueue("[]")

        _ = try await PhishInAPI.years()

        XCTAssertEqual("application/json", server.takeRequest()!.value(forHTTPHeaderField: "Accept"))
    }

    // ----------------------------------------------------------------- Relisten

    func testRequestsTheArtistListUnderV3() async throws {
        server.enqueue("[]")

        _ = try await RelistenAPI.artists()

        XCTAssertEqual(["api", "v3", "artists"], server.takeRequest()!.pathSegments)
    }

    func testRequestsYearsUnderTheArtistsV3Path() async throws {
        server.enqueue("[]")

        _ = try await RelistenAPI.years(artistUuid: "artist-uuid")

        XCTAssertEqual(["api", "v3", "artists", "artist-uuid", "years"], server.takeRequest()!.pathSegments)
    }

    func testRequestsASingleYearByArtistAndYearUuid() async throws {
        server.enqueue(#"{"year":"1977","shows":[]}"#)

        _ = try await RelistenAPI.year(artistUuid: "artist-uuid", yearUuid: "year-uuid")

        XCTAssertEqual(
            ["api", "v3", "artists", "artist-uuid", "years", "year-uuid"],
            server.takeRequest()!.pathSegments
        )
    }

    func testRequestsAShowByArtistSlugAndDateUnderV2NotV3() async throws {
        // The per-show endpoint with every tape hasn't moved to v3 — getting this wrong
        // 404s every show page.
        server.enqueue(#"{"display_date":"1977-05-08","sources":[]}"#)

        _ = try await RelistenAPI.show(artistIdOrSlug: "grateful-dead", date: "1977-05-08")

        XCTAssertEqual(
            ["api", "v2", "artists", "grateful-dead", "shows", "1977-05-08"],
            server.takeRequest()!.pathSegments
        )
    }

    func testSendsNoAuthHeaderBecauseRelistenNeedsNone() async throws {
        server.enqueue("[]")

        _ = try await RelistenAPI.artists()

        let request = server.takeRequest()!
        XCTAssertNil(request.value(forHTTPHeaderField: "X-Auth-Token"))
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
    }

    // ----------------------------------------------------------- RelistenCatalogSource

    func testRelistenCatalogSourceCachesTheArtistListAfterTheFirstCall() async throws {
        let source = RelistenCatalogSource()
        server.enqueue(#"[{"uuid":"u","slug":"phish","name":"Phish"}]"#)

        let first = try await source.artists()
        let second = try await source.artists()

        XCTAssertEqual(first, second)
        XCTAssertEqual(1, server.requests.count)
    }

    func testRelistenCatalogSourcePeriodsDelegateThroughTheSlugNotAUuidLookup() async throws {
        // /years and /years/{yearUuid} both accept the slug directly (confirmed live on
        // Android), so this must not try to resolve one through an extra artists() round trip.
        let source = RelistenCatalogSource()
        let artist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
        server.enqueue(#"[{"uuid":"y","year":"1977","show_count":57}]"#)

        let periods = try await source.periods(artist: artist)

        XCTAssertEqual("grateful-dead", server.takeRequest()!.pathSegments[3])
        XCTAssertEqual("1977", periods.first?.label)
    }

    func testRelistenCatalogSourceShowReturnsTheDefaultTapeAsAQueueKeyableDetail() async throws {
        let source = RelistenCatalogSource()
        let artist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead", hasSets: false)
        server.enqueue(
            """
            {"display_date":"1977-05-08","sources":[
                {"uuid":"src-1","avg_rating_weighted":9.0,"sets":[
                    {"index":0,"name":"Set","tracks":[
                        {"uuid":"t1","title":"Minglewood Blues","duration":10,"mp3_url":"https://a/1.mp3"}
                    ]}
                ]}
            ]}
            """
        )

        let detail = try await source.show(artist: artist, date: "1977-05-08", recordingId: nil)

        XCTAssertEqual("relisten:grateful-dead/1977-05-08/src-1", detail.queueKey)
        XCTAssertEqual("Minglewood Blues", detail.tracks.first?.title)
    }
}
