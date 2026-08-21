import XCTest
@testable import CouchTourKit

/// #58: Relisten's local `LikedTracks` (mirrors `FavoritesTests`) and phish.in's server-side
/// like/unlike request shape (mirrors `PhishInAPIAuthRequestTests`).
@MainActor
final class LikedTracksTests: XCTestCase {
    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "LikedTracksTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suiteName) }
        return defaults
    }

    func testTogglingAnIdAddsIt() {
        let liked = LikedTracks(defaults: isolatedDefaults())
        liked.toggle("track-uuid")
        XCTAssertEqual(["track-uuid"], liked.ids)
    }

    func testTogglingAnAlreadyLikedIdRemovesIt() {
        let liked = LikedTracks(defaults: isolatedDefaults())
        liked.toggle("track-uuid")
        liked.toggle("track-uuid")
        XCTAssertTrue(liked.ids.isEmpty)
    }

    func testPersistsAcrossInstancesSharingTheSameDefaults() {
        let defaults = isolatedDefaults()
        LikedTracks(defaults: defaults).toggle("track-uuid")
        XCTAssertEqual(["track-uuid"], LikedTracks(defaults: defaults).ids)
    }
}

final class PhishInAPILikesRequestTests: XCTestCase {
    private var server: MockServer!

    override func setUp() {
        super.setUp()
        server = MockServer()
        server.start()
        PhishInAPI.baseURL = URL(string: "https://mock.test/api/v2")!
        PhishInAPI.authToken = "abc123"
    }

    override func tearDown() {
        server.shutdown()
        PhishInAPI.baseURL = URL(string: "https://phish.in/api/v2")!
        PhishInAPI.authToken = nil
        PhishInAPI.onUnauthorized = nil
        super.tearDown()
    }

    func testLikePostsTheLikableTypeAndId() async throws {
        server.enqueue("{}")

        try await PhishInAPI.like(.track, 42)

        let request = server.takeRequest()!
        XCTAssertEqual(["api", "v2", "likes"], request.pathSegments)
        XCTAssertEqual("POST", request.httpMethod)
        let body = try JSONSerialization.jsonObject(with: Data(request.bodyString!.utf8)) as! [String: Any]
        XCTAssertEqual("Track", body["likable_type"] as? String)
        XCTAssertEqual(42, body["likable_id"] as? Int)
    }

    func testUnlikeSendsADeleteWithQueryParams() async throws {
        server.enqueue("{}")

        try await PhishInAPI.unlike(.track, 42)

        let request = server.takeRequest()!
        XCTAssertEqual("DELETE", request.httpMethod)
        XCTAssertEqual("Track", request.queryValue("likable_type"))
        XCTAssertEqual("42", request.queryValue("likable_id"))
    }

    func testLikeAndUnlikeCarryTheAuthToken() async throws {
        server.enqueue("{}")
        try await PhishInAPI.like(.track, 42)
        XCTAssertEqual("abc123", server.takeRequest()!.value(forHTTPHeaderField: "X-Auth-Token"))
    }
}
