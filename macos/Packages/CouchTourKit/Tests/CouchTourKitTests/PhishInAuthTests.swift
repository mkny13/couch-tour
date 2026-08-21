import XCTest
@testable import CouchTourKit

/// Request-shape and session-plumbing tests for #57 (phish.in login), mirroring Android's
/// ApiTest.kt/AuthTest.kt split: `PhishInAPIAuthRequestTests` pins the wire format against
/// `MockServer`, `PhishInTokenStoreTests` pins the Keychain round trip, and
/// `PhishInSessionTests` pins the login/logout/onUnauthorized state machine.
final class PhishInAPIAuthRequestTests: XCTestCase {
    private var server: MockServer!

    override func setUp() {
        super.setUp()
        server = MockServer()
        server.start()
        PhishInAPI.baseURL = URL(string: "https://mock.test/api/v2")!
        PhishInAPI.authToken = nil
        PhishInAPI.onUnauthorized = nil
    }

    override func tearDown() {
        server.shutdown()
        PhishInAPI.baseURL = URL(string: "https://phish.in/api/v2")!
        PhishInAPI.authToken = nil
        PhishInAPI.onUnauthorized = nil
        super.tearDown()
    }

    func testLoginPostsEmailAndPasswordToAuthLogin() async throws {
        server.enqueue(#"{"jwt":"t","username":"mike","email":"mike@example.com"}"#)

        _ = try await PhishInAPI.login(email: "mike@example.com", password: "hunter2")

        let request = server.takeRequest()!
        XCTAssertEqual(["api", "v2", "auth", "login"], request.pathSegments)
        XCTAssertEqual("POST", request.httpMethod)
        let body = try JSONSerialization.jsonObject(with: Data(request.bodyString!.utf8)) as! [String: String]
        XCTAssertEqual(["email": "mike@example.com", "password": "hunter2"], body)
    }

    func testLoginDoesNotAttachAnAuthTokenEvenIfOneIsAlreadyStored() async throws {
        // A stale token from a previous session shouldn't ride along on the request that's
        // establishing a brand new one.
        PhishInAPI.authToken = "stale-token"
        server.enqueue(#"{"jwt":"t","username":"mike","email":"mike@example.com"}"#)

        _ = try await PhishInAPI.login(email: "mike@example.com", password: "hunter2")

        XCTAssertEqual("stale-token", server.takeRequest()!.value(forHTTPHeaderField: "X-Auth-Token"))
    }

    func testAuthenticatedRequestsCarryTheTokenAsXAuthTokenNotBearer() async throws {
        PhishInAPI.authToken = "abc123"
        server.enqueue(#"{"shows":[]}"#)

        _ = try await PhishInAPI.showsForPeriod("1997")

        let request = server.takeRequest()!
        XCTAssertEqual("abc123", request.value(forHTTPHeaderField: "X-Auth-Token"))
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
    }

    func testA401OnARequestThatCarriedATokenFiresOnUnauthorized() async throws {
        PhishInAPI.authToken = "abc123"
        var fired = false
        PhishInAPI.onUnauthorized = { fired = true }
        server.enqueue("", code: 401)

        _ = try? await PhishInAPI.showsForPeriod("1997")

        XCTAssertTrue(fired)
    }

    func testABadPasswordFromLoginItselfDoesNotFireOnUnauthorized() async throws {
        // login() never attaches a token, so its own 401 must not look like a session going
        // bad — it's just a wrong password, and the caller surfaces that as a login error.
        var fired = false
        PhishInAPI.onUnauthorized = { fired = true }
        server.enqueue("", code: 401)

        do {
            _ = try await PhishInAPI.login(email: "mike@example.com", password: "wrong")
            XCTFail("expected a 401")
        } catch let error as APIException {
            XCTAssertTrue(error.unauthorized)
        }

        XCTAssertFalse(fired)
    }
}

@MainActor
final class PhishInTokenStoreTests: XCTestCase {
    func testRoundTripsJwtAndUsername() {
        let store = PhishInTokenStore(keychain: InMemoryKeychain())
        store.jwt = "t"
        store.username = "mike"
        XCTAssertEqual("t", store.jwt)
        XCTAssertEqual("mike", store.username)
    }

    func testClearWipesBoth() {
        let store = PhishInTokenStore(keychain: InMemoryKeychain())
        store.jwt = "t"
        store.username = "mike"
        store.clear()
        XCTAssertNil(store.jwt)
        XCTAssertNil(store.username)
    }

    func testDefaultsToNil() {
        let store = PhishInTokenStore(keychain: InMemoryKeychain())
        XCTAssertNil(store.jwt)
        XCTAssertNil(store.username)
    }
}

@MainActor
final class PhishInSessionTests: XCTestCase {
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
        PhishInAPI.onUnauthorized = nil
        super.tearDown()
    }

    func testInitRestoresAStoredSessionIntoPhishInAPI() {
        let store = PhishInTokenStore(keychain: InMemoryKeychain())
        store.jwt = "stored-token"
        store.username = "mike"

        let session = PhishInSession(store: store)

        XCTAssertEqual("mike", session.username)
        XCTAssertEqual("stored-token", PhishInAPI.authToken)
    }

    func testLoginStoresTheTokenAndPublishesTheUsername() async throws {
        let session = PhishInSession(store: PhishInTokenStore(keychain: InMemoryKeychain()))
        server.enqueue(#"{"jwt":"new-token","username":"mike","email":"mike@example.com"}"#)

        try await session.login(email: "mike@example.com", password: "hunter2")

        XCTAssertEqual("mike", session.username)
        XCTAssertEqual("new-token", PhishInAPI.authToken)
    }

    func testLogoutClearsTheStoreAndTheApiToken() async throws {
        let store = PhishInTokenStore(keychain: InMemoryKeychain())
        let session = PhishInSession(store: store)
        server.enqueue(#"{"jwt":"t","username":"mike","email":"mike@example.com"}"#)
        try await session.login(email: "mike@example.com", password: "hunter2")

        session.logout()

        XCTAssertNil(session.username)
        XCTAssertNil(PhishInAPI.authToken)
        XCTAssertNil(store.jwt)
        XCTAssertNil(store.username)
    }

    func testAnUnauthorizedResponseElsewhereLogsTheSessionOut() async throws {
        let store = PhishInTokenStore(keychain: InMemoryKeychain())
        let session = PhishInSession(store: store)
        server.enqueue(#"{"jwt":"t","username":"mike","email":"mike@example.com"}"#)
        try await session.login(email: "mike@example.com", password: "hunter2")

        server.enqueue("", code: 401)
        _ = try? await PhishInAPI.showsForPeriod("1997")

        XCTAssertNil(session.username)
        XCTAssertNil(store.jwt)
    }
}
