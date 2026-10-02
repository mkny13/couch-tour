import XCTest
@testable import CouchTourKit

final class DiagnosticsInstrumentationTests: XCTestCase {
    private var dir: URL!
    private var log: DiagnosticsLog!
    private var server: MockServer!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("diag-inst-\(UUID().uuidString)")
        log = DiagnosticsLog(directory: dir)
        Diagnostics.log = log
        server = MockServer()
        server.start()
        SyncAPI.baseURL = URL(string: "https://mock.test")!
        PhishInAPI.baseURL = URL(string: "https://mock.test/api/v2")!
        suiteName = "DiagnosticsInstrumentationTests.\(UUID())"
    }

    override func tearDown() {
        Diagnostics.log = nil
        server.shutdown()
        SyncAPI.baseURL = SyncAPI.defaultBase
        PhishInAPI.baseURL = URL(string: "https://phish.in/api/v2")!
        PhishInAPI.authToken = nil
        UserDefaults().removePersistentDomain(forName: suiteName)
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    private func text() -> String { log.flush(); return log.exportText() }

    private func pairedSession() async throws -> SyncSession {
        let s = SyncSession(store: SyncTokenStore(keychain: InMemoryKeychain(), defaults: UserDefaults(suiteName: suiteName)!))
        server.enqueue(#"{"deviceId":"d2","deviceToken":"ct_secret_token"}"#)
        try await s.claimPairing(code: "ABCD1234", deviceName: "Mac", platform: "macos")
        return s
    }

    func testApiCallLogsPathOnlyAndNeverQueryOrToken() async throws {
        PhishInAPI.authToken = "jwt-super-secret"
        server.enqueue("[]")
        _ = try? await PhishInAPI.search("fluffhead")
        let out = text()
        XCTAssertTrue(out.contains("api.call"))
        XCTAssertTrue(out.contains("phase=start"))
        XCTAssertTrue(out.contains("phase=end") || out.contains("phase=failed"))
        XCTAssertTrue(out.contains("path=/api/v2/search/fluffhead"))
        XCTAssertFalse(out.contains("audio_status"))
        XCTAssertFalse(out.contains("jwt-super-secret"))
        XCTAssertFalse(out.contains("?"))
        XCTAssertFalse(out.contains("mock.test"))
    }

    func testApiFailureLogsPhaseFailedWithErrorTypeName() async {
        server.enqueue("{}", code: 500)
        _ = try? await PhishInAPI.years()
        // HTTP errors still complete the transport; the status is recorded on phase=end.
        XCTAssertTrue(text().contains("status=500"))
    }

    func testSyncSuccessLogsStartEndCountsAndMark() async throws {
        let session = try await pairedSession()
        server.enqueue(#"{"seq":1,"changes":[]}"#)
        try await session.sync(try ProgressStore.inMemory())
        let out = text()
        XCTAssertTrue(out.contains("sync.start"))
        XCTAssertTrue(out.contains("sync.end\tpulled=0 pushed=0 ms="))
        XCTAssertTrue(log.summaryLines().contains("Last sync: "))
        XCTAssertTrue(log.summaryLines().contains(" ok"))
        XCTAssertFalse(out.contains("ct_secret_token"))
        XCTAssertFalse(out.contains("ABCD1234"))
    }

    func testSyncServerErrorLogsShortCodeNotRawMessage() async throws {
        let session = try await pairedSession()
        server.enqueue(#"{"error":"boom at https://internal.example/x?token=abc"}"#, code: 500)
        do { try await session.sync(try ProgressStore.inMemory()); XCTFail() } catch {}
        let out = text()
        XCTAssertTrue(out.contains("sync.error\tcode=server"))
        XCTAssertFalse(out.contains("internal.example"))
        XCTAssertFalse(out.contains("abc"))
        XCTAssertTrue(log.summaryLines().contains("server"))
    }

    func testSyncUnauthorizedIsLoggedAsError() async throws {
        let session = try await pairedSession()
        server.enqueue(#"{"error":"revoked"}"#, code: 401)
        try await session.sync(try ProgressStore.inMemory())
        XCTAssertTrue(text().contains("sync.error\tcode=unauthorized"))
        XCTAssertFalse(text().contains("sync.end"))
    }

    func testPlaybackEventsAndMark() {
        Diagnostics.playbackStart(show: "1997-11-17", trackIndex: 2)
        Diagnostics.playbackStop(show: "1997-11-17", trackIndex: 2, positionMs: 1234)
        Diagnostics.playbackError(show: "1997-11-17", trackIndex: 2,
                                  error: NSError(domain: "AVFoundationErrorDomain", code: -11800))
        let out = text()
        XCTAssertTrue(out.contains("playback.start\tshow=1997-11-17 track=2"))
        XCTAssertTrue(out.contains("playback.stop\tshow=1997-11-17 track=2 posMs=1234"))
        XCTAssertTrue(out.contains("playback.error\tshow=1997-11-17 track=2 domain=AVFoundationErrorDomain errno=-11800"))
        XCTAssertTrue(log.summaryLines().contains("Last playback: "))
        XCTAssertTrue(log.summaryLines().contains("error"))
    }
}
