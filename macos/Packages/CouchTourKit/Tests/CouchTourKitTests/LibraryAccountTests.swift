import XCTest
@testable import CouchTourKit

final class LibraryAccountTests: XCTestCase {
    private var server: MockServer!

    override func setUp() {
        super.setUp()
        server = MockServer()
        server.start()
        PhishInAPI.baseURL = URL(string: "https://mock.test/api/v2")!
    }

    override func tearDown() {
        server.shutdown()
        PhishInAPI.baseURL = URL(string: "https://phish.in/api/v2")!
        PhishInAPI.authToken = nil
        super.tearDown()
    }

    private func pl(_ slug: String, _ name: String = "P") -> String {
        #"{"id":1,"name":"\#(name)","slug":"\#(slug)","duration":1000,"tracks_count":3,"likes_count":1,"username":"me"}"#
    }

    private func enqueueAccount(mine: [String], liked: [String]) {
        server.enqueue(#"{"playlists":[\#(mine.joined(separator: ","))]}"#, forPathContaining: "filter=mine")
        server.enqueue(#"{"playlists":[\#(liked.joined(separator: ","))]}"#, forPathContaining: "filter=liked")
        server.enqueue(#"{"shows":[{"date":"1997-11-22","venue_name":"Hampton","audio_status":"complete","id":5}]}"#, forPathContaining: "shows")
        server.enqueue(#"{"tracks":[{"id":9,"title":"Tweezer","show_date":"1997-11-22","venue_name":"Hampton","audio_status":"complete","mp3_url":"https://x/y.mp3"}]}"#, forPathContaining: "tracks")
    }

    func testSignedOutMakesZeroRequests() async {
        PhishInAPI.authToken = nil
        let data = await LibraryAccountData.load(signedIn: false)
        XCTAssertEqual(.empty, data)
        // A stale username with no token must not leak a filter=mine request either.
        let noToken = await LibraryAccountData.load(signedIn: true)
        XCTAssertEqual(.empty, noToken)
        XCTAssertEqual(0, server.requestCount)
    }

    func testAccountEndpointsThrowWithoutToken() async {
        PhishInAPI.authToken = nil
        do { _ = try await PhishInAPI.accountPlaylists(filter: "mine"); XCTFail() } catch {}
        do { _ = try await PhishInAPI.likedShows(); XCTFail() } catch {}
        do { _ = try await PhishInAPI.likedTracks(); XCTFail() } catch {}
        XCTAssertEqual(0, server.requestCount)
    }

    func testSignedInSendsTokenAndFilters() async throws {
        PhishInAPI.authToken = "jwt"
        enqueueAccount(mine: [], liked: [])
        _ = await LibraryAccountData.load(signedIn: true)
        let reqs = (0..<4).compactMap { _ in server.takeRequest() }
        XCTAssertEqual(4, reqs.count)
        XCTAssertTrue(reqs.allSatisfy { $0.value(forHTTPHeaderField: "X-Auth-Token") == "jwt" })
        XCTAssertEqual(["liked", "mine"], reqs.compactMap { $0.queryValue("filter") }.sorted())
        XCTAssertEqual(2, reqs.filter { $0.queryValue("liked_by_user") == "true" }.count)
    }

    func testMapsAccountContentToCategoriesAndDedupesBySlug() async {
        PhishInAPI.authToken = "jwt"
        enqueueAccount(mine: [pl("a", "Mine"), pl("b")], liked: [pl("a", "Mine"), pl("c")])
        let account = await LibraryAccountData.load(signedIn: true)
        XCTAssertFalse(account.failed)
        let items = LibrarySources.items(playlists: [], playlistTracks: [], likedTracks: [], account: account)
        XCTAssertEqual(["a", "b", "c"], items.filter { $0.kind == .playlist }.compactMap { $0.accountPlaylist?.slug })
        XCTAssertEqual(["1997-11-22"], items.filter { $0.kind == .show }.map(\.name))
        XCTAssertEqual(["Tweezer"], items.filter { $0.kind == .track }.map(\.name))
        XCTAssertEqual("1997-11-22 · Hampton", items.first { $0.kind == .track }?.subtitle)
        XCTAssertTrue(items.allSatisfy { $0.addedAt == nil })
    }

    func testFailureIsFlaggedNotAnEmptySuccess() async {
        PhishInAPI.authToken = "jwt"
        for _ in 0..<4 { server.enqueue("{}", code: 500) }
        let account = await LibraryAccountData.load(signedIn: true)
        XCTAssertTrue(account.failed)
        XCTAssertNotEqual(LibraryAccountData.empty, account)
        let local = LocalPlaylist(id: "l", name: "Mine", trackCount: 0, createdAt: 0, updatedAt: 1)
        let items = LibrarySources.items(playlists: [local], playlistTracks: [], likedTracks: [], account: account)
        XCTAssertEqual(["Mine"], items.map(\.name))
    }

    func testUndatedAccountRowsFollowDatedLocalRows() {
        let local = LocalPlaylist(id: "l", name: "Local", trackCount: 0, createdAt: 0, updatedAt: 100)
        let account = LibraryAccountData(
            playlists: [PublicPlaylistSummary(name: "Acct", slug: "acct")],
            shows: [Show(date: "1999-12-31", audioStatus: "complete", id: 1)]
        )
        let items = LibrarySources.items(playlists: [local], playlistTracks: [], likedTracks: [], account: account)
        let sorted = LibrarySources.sorted(items, by: .recentlyAdded)
        XCTAssertEqual("Local", sorted.first?.name)
        XCTAssertEqual(3, sorted.count)
        XCTAssertNil(sorted[1].addedAt)
    }
}
