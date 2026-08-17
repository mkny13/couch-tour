import XCTest
@testable import CouchTourKit

/// Round-trips `SyncTokenStore` against an in-memory keychain fake, never the real system
/// keychain — see `InMemoryKeychain`. `defaults` is a fresh, uniquely-named suite per test
/// method (cleaned up in tearDown), not `.standard` — this suite is otherwise a real on-disk
/// UserDefaults domain, and every test method in this file would collide on the same one
/// under a fixed name like `#file`.
final class SyncTokenStoreTests: XCTestCase {

    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "SyncTokenStoreTests.\(UUID())"
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func store() -> SyncTokenStore {
        SyncTokenStore(keychain: InMemoryKeychain(), defaults: UserDefaults(suiteName: suiteName)!)
    }

    func testRoundTripsADeviceTokenAndId() {
        let store = store()
        store.deviceToken = "ct_abc"
        store.deviceId = "device-1"
        XCTAssertEqual("ct_abc", store.deviceToken)
        XCTAssertEqual("device-1", store.deviceId)
    }

    func testDefaultsTheCursorsToZero() {
        XCTAssertEqual(0, store().lastSeq)
        XCTAssertEqual(0, store().lastPushWatermark)
    }

    func testRoundTripsTheCursors() {
        let store = store()
        store.lastSeq = 42
        store.lastPushWatermark = 7
        XCTAssertEqual(42, store.lastSeq)
        XCTAssertEqual(7, store.lastPushWatermark)
    }

    func testClearWipesEverything() {
        let store = store()
        store.deviceToken = "ct_abc"
        store.deviceId = "device-1"
        store.lastSeq = 42
        store.lastPushWatermark = 7

        store.clear()

        XCTAssertNil(store.deviceToken)
        XCTAssertNil(store.deviceId)
        XCTAssertEqual(0, store.lastSeq)
        XCTAssertEqual(0, store.lastPushWatermark)
    }
}

/// Exercises SyncAPI's outgoing requests against a local `MockServer`, mirroring
/// RequestTests.swift's style.
final class SyncAPIRequestTests: XCTestCase {

    private var server: MockServer!

    override func setUp() {
        super.setUp()
        server = MockServer()
        server.start()
        SyncAPI.baseURL = URL(string: "https://mock.test")!
    }

    override func tearDown() {
        server.shutdown()
        SyncAPI.baseURL = URL(string: "https://couch-tour-sync.mkastellec.workers.dev")!
        super.tearDown()
    }

    func testPairStartPostsDeviceNameAndPlatformNoAuthWhenBootstrapping() async throws {
        server.enqueue(#"{"code":"ABCD1234","expiresAt":1000,"deviceId":"d1","deviceToken":"ct_x"}"#)

        let response = try await SyncAPI.pairStart(deviceName: "Mac", platform: "macos", existingToken: nil)

        let request = server.takeRequest()!
        XCTAssertEqual(["pair", "start"], request.pathSegments)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual("ABCD1234", response.code)
        XCTAssertEqual("d1", response.deviceId)
        XCTAssertEqual("ct_x", response.deviceToken)
    }

    func testPairStartSendsTheBearerTokenWhenAlreadyPaired() async throws {
        server.enqueue(#"{"code":"ABCD1234","expiresAt":1000}"#)

        _ = try await SyncAPI.pairStart(deviceName: "Mac", platform: "macos", existingToken: "ct_existing")

        XCTAssertEqual("Bearer ct_existing", server.takeRequest()!.value(forHTTPHeaderField: "Authorization"))
    }

    func testPairClaimPostsJustTheCodeNameAndPlatform() async throws {
        server.enqueue(#"{"deviceId":"d2","deviceToken":"ct_y"}"#)

        let response = try await SyncAPI.pairClaim(code: "ABCD1234", deviceName: "Mac", platform: "macos")

        let request = server.takeRequest()!
        XCTAssertEqual(["pair", "claim"], request.pathSegments)
        XCTAssertEqual("d2", response.deviceId)
        XCTAssertEqual("ct_y", response.deviceToken)
    }

    func testSyncSendsTheBearerTokenSinceAndChanges() async throws {
        server.enqueue(#"{"seq":5,"changes":[]}"#)
        let change = SyncProgressWire(
            queueKey: "show:1997-11-17", title: "t", subtitle: "s", trackIndex: 0,
            positionMs: 100, trackTitle: "Track", updatedAt: 1000, finished: false,
            dismissed: false, artist: "Phish"
        )

        let (response, rotated) = try await SyncAPI.sync(token: "ct_token", since: 2, changes: [change])

        let request = server.takeRequest()!
        XCTAssertEqual(["sync"], request.pathSegments)
        XCTAssertEqual("Bearer ct_token", request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(5, response.seq)
        XCTAssertNil(rotated)
    }

    func testSyncSurfacesARotatedTokenFromTheResponseHeader() async throws {
        server.enqueue(#"{"seq":1,"changes":[]}"#, headers: ["X-Sync-Token-Rotated": "ct_new"])

        let (_, rotated) = try await SyncAPI.sync(token: "ct_old", since: 0, changes: [])

        XCTAssertEqual("ct_new", rotated)
    }

    func testDevicesParsesTheList() async throws {
        server.enqueue(
            #"{"devices":[{"deviceId":"d1","name":"Pixel","platform":"android","createdAt":100,"lastSeenAt":200,"isSelf":true}]}"#
        )

        let devices = try await SyncAPI.devices(token: "ct_token")

        XCTAssertEqual(1, devices.count)
        XCTAssertEqual("Pixel", devices[0].name)
        XCTAssertTrue(devices[0].isSelf)
    }

    func testRevokeDeviceSendsADeleteToTheDevicePath() async throws {
        server.enqueue(#"{"revoked":true}"#)

        try await SyncAPI.revokeDevice(token: "ct_token", deviceId: "d1")

        let request = server.takeRequest()!
        XCTAssertEqual("DELETE", request.httpMethod)
        XCTAssertEqual(["devices", "d1"], request.pathSegments)
    }

    func testA401ThrowsSyncExceptionMarkedUnauthorized() async {
        server.enqueue(#"{"error":"unauthorized"}"#, code: 401)

        do {
            _ = try await SyncAPI.devices(token: "ct_bad")
            XCTFail("expected SyncException")
        } catch let error as SyncException {
            XCTAssertTrue(error.unauthorized)
        } catch {
            XCTFail("wrong error type: \(error)")
        }
    }

    func testA410ThrowsSyncExceptionMarkedGone() async {
        server.enqueue(#"{"error":"cursor too old; full resync required"}"#, code: 410)

        do {
            _ = try await SyncAPI.sync(token: "ct_token", since: 1, changes: [])
            XCTFail("expected SyncException")
        } catch let error as SyncException {
            XCTAssertTrue(error.gone)
        } catch {
            XCTFail("wrong error type: \(error)")
        }
    }

    // Caught live: without LocalizedError conformance, `error.localizedDescription` on a
    // SyncException falls back to Swift's generic bridged-NSError text regardless of what
    // actually went wrong ("The operation couldn't be completed... error 1"), which is exactly
    // what a wrong pairing code showed instead of "incorrect code".
    func testLocalizedDescriptionSurfacesTheActualMessage() async {
        server.enqueue(#"{"error":"incorrect code"}"#, code: 401)

        do {
            _ = try await SyncAPI.pairClaim(code: "WRONGCOD", deviceName: "Mac", platform: "macos")
            XCTFail("expected SyncException")
        } catch let error as SyncException {
            XCTAssertEqual("incorrect code", error.localizedDescription)
        } catch {
            XCTFail("wrong error type: \(error)")
        }
    }
}

/// End-to-end `SyncSession` behaviour against a mock server, an in-memory `ProgressStore`,
/// and an in-memory keychain — the same combination the Kotlin `SyncSessionTest` uses.
final class SyncSessionTests: XCTestCase {

    private var server: MockServer!
    private var store: ProgressStore!
    private var session: SyncSession!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        server = MockServer()
        server.start()
        SyncAPI.baseURL = URL(string: "https://mock.test")!
        store = try! ProgressStore.inMemory()
        suiteName = "SyncSessionTests.\(UUID())"
        session = SyncSession(store: SyncTokenStore(keychain: InMemoryKeychain(), defaults: UserDefaults(suiteName: suiteName)!))
    }

    override func tearDown() {
        server.shutdown()
        SyncAPI.baseURL = URL(string: "https://couch-tour-sync.mkastellec.workers.dev")!
        UserDefaults().removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func claim() async throws {
        server.enqueue(#"{"deviceId":"d2","deviceToken":"ct_y"}"#)
        try await session.claimPairing(code: "ABCD1234", deviceName: "Mac", platform: "macos")
        _ = server.takeRequest() // the pair/claim request itself, out of the way for callers
    }

    func testSyncIsANoOpWhenUnpaired() async throws {
        try await session.sync(store)

        XCTAssertEqual(0, server.requestCount)
    }

    func testClaimingPairingStoresTheToken() async throws {
        try await claim()

        XCTAssertTrue(session.paired)
    }

    func testSyncPushesRowsChangedSinceTheWatermark() async throws {
        try await claim()
        try store.put(PlaybackProgress(
            queueKey: "show:1997-11-17", title: "t", subtitle: "s", trackIndex: 0,
            positionMs: 100, trackTitle: "Track", updatedAt: 5_000, finished: false, dismissed: false, artist: "Phish"
        ))
        server.enqueue(#"{"seq":1,"changes":[]}"#)

        try await session.sync(store)

        let pushed = server.takeRequest()!.bodyString!
        XCTAssertTrue(pushed.contains(#""queueKey":"show:1997-11-17""#))
        XCTAssertTrue(pushed.contains(#""since":0"#))
    }

    func testSyncDoesNotRepushARowAlreadyAtTheWatermark() async throws {
        try await claim()
        try store.put(PlaybackProgress(
            queueKey: "show:1997-11-17", title: "t", subtitle: "s", trackIndex: 0,
            positionMs: 100, trackTitle: "Track", updatedAt: 5_000, finished: false, dismissed: false, artist: "Phish"
        ))
        server.enqueue(#"{"seq":1,"changes":[]}"#)
        try await session.sync(store)
        _ = server.takeRequest() // the first sync's push

        server.enqueue(#"{"seq":1,"changes":[]}"#)
        try await session.sync(store)

        // JSONEncoder's key order isn't guaranteed stable across process launches, so this
        // checks content rather than the exact byte layout — same reasoning as the sibling
        // "pushes rows" test above.
        let body = server.takeRequest()!.bodyString ?? ""
        XCTAssertTrue(body.contains(#""since":1"#))
        XCTAssertTrue(body.contains(#""changes":[]"#))
    }

    func testSyncAppliesPulledRowsIntoTheLocalDatabase() async throws {
        try await claim()
        server.enqueue(
            """
            {"seq":3,"changes":[{"queueKey":"show:1997-11-17","title":"t","subtitle":"s",
            "artUrl":null,"trackIndex":2,"positionMs":9000,"trackTitle":"Tweezer",
            "updatedAt":5000,"finished":false,"dismissed":false,"artist":"Phish","deletedAt":null}]}
            """
        )

        try await session.sync(store)

        let row = try store.get(key: "show:1997-11-17")!
        XCTAssertEqual(2, row.trackIndex)
        XCTAssertEqual(9_000, row.positionMs)
    }

    func testARotatedTokenIsUsedOnTheNextCall() async throws {
        try await claim()
        server.enqueue(#"{"seq":1,"changes":[]}"#, headers: ["X-Sync-Token-Rotated": "ct_rotated"])
        try await session.sync(store)
        _ = server.takeRequest() // the rotating sync call

        server.enqueue(#"{"seq":1,"changes":[]}"#)
        try await session.sync(store)

        XCTAssertEqual("Bearer ct_rotated", server.takeRequest()!.value(forHTTPHeaderField: "Authorization"))
    }

    func testAnUnauthorizedResponseUnlinksTheDevice() async throws {
        try await claim()
        server.enqueue(#"{"error":"unauthorized"}"#, code: 401)

        try await session.sync(store)

        XCTAssertFalse(session.paired)
    }

    func testAGoneResponseResetsTheCursorAndRetriesOnce() async throws {
        try await claim()
        let requestsBeforeSync = server.requestCount
        server.enqueue(#"{"error":"cursor too old"}"#, code: 410)
        server.enqueue(#"{"seq":9,"changes":[]}"#)

        try await session.sync(store)

        // The 410 plus its retry: two requests beyond whatever claim() already made.
        XCTAssertEqual(2, server.requestCount - requestsBeforeSync)
        XCTAssertTrue(session.paired)
    }
}
