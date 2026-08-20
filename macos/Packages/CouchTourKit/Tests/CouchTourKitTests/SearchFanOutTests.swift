import XCTest
@testable import CouchTourKit

/// `searchAll` fans out across both backends independently — one going down must not cost the
/// other its results. Port of `SearchFanOutTest.kt`. Each backend points at a distinct mock
/// host so `MockServer`'s two concurrent requests resolve deterministically (its host-scoped
/// `enqueue`, added for this test) rather than racing over one shared FIFO queue, mirroring
/// how the Kotlin test gives each backend its own `MockWebServer`.
final class SearchFanOutTests: XCTestCase {

    private var server: MockServer!
    private let phishInHost = "phishin.mock.test"
    private let relistenHost = "relisten.mock.test"

    override func setUp() {
        super.setUp()
        server = MockServer()
        server.start()
        PhishInAPI.baseURL = URL(string: "https://\(phishInHost)/api/v2")!
        RelistenAPI.baseURL = URL(string: "https://\(relistenHost)/api")!
    }

    override func tearDown() {
        server.shutdown()
        PhishInAPI.baseURL = URL(string: "https://phish.in/api/v2")!
        RelistenAPI.baseURL = URL(string: "https://api.relisten.net/api")!
        super.tearDown()
    }

    func testMergesHitsFromBothBackends() async {
        server.enqueue(#"{"other_shows":[],"tracks":[]}"#, forHost: phishInHost)
        server.enqueue(
            #"{"Artists":[{"uuid":"u","slug":"goose","name":"Goose","show_count":100}],"Shows":[],"Songs":[],"Venues":[]}"#,
            forHost: relistenHost
        )

        let hits = await searchAll("goose")

        XCTAssertEqual(["Goose"], hits.artists.map { $0.name })
        XCTAssertTrue(hits.failed.isEmpty)
    }

    func testOneBackendFailingDoesNotCostTheOthersResults() async {
        server.enqueue("nope", code: 500, forHost: phishInHost)
        server.enqueue(
            #"{"Artists":[{"uuid":"u","slug":"goose","name":"Goose","show_count":100}],"Shows":[],"Songs":[],"Venues":[]}"#,
            forHost: relistenHost
        )

        let hits = await searchAll("goose")

        XCTAssertEqual(["Goose"], hits.artists.map { $0.name })
        XCTAssertEqual(Set([Backend.phishin]), hits.failed)
    }

    func testBothBackendsFailingReturnsEmptyHitsNamingBoth() async {
        server.enqueue("nope", code: 500, forHost: phishInHost)
        server.enqueue("nope", code: 500, forHost: relistenHost)

        let hits = await searchAll("goose")

        XCTAssertTrue(hits.isEmpty)
        XCTAssertEqual(Set([Backend.phishin, Backend.relisten]), hits.failed)
    }
}
