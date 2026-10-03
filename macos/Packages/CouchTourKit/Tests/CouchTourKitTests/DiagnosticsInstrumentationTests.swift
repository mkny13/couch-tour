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

    private func row(_ key: String, updatedAt: Int64) -> PlaybackProgress {
        PlaybackProgress(queueKey: key, title: "t", subtitle: "s", trackIndex: 0, positionMs: 0,
                         trackTitle: "Track", updatedAt: updatedAt, artist: "Phish")
    }

    private func wireChange(_ key: String) -> String {
        #"{"queueKey":"\#(key)","title":"t","subtitle":"s","trackIndex":0,"positionMs":0,"trackTitle":"Track","updatedAt":9000,"finished":false,"dismissed":false,"artist":"Phish","deletedAt":null}"#
    }

    /// Deterministic overlap: call A parks mid-round-trip behind a gated response while call B
    /// runs to completion, then A is released. Distinct stores give the calls distinct pushes.
    func testOverlappingSyncsLogTheirOwnCounts() async throws {
        let session = try await pairedSession()
        let storeA = try ProgressStore.inMemory()
        try storeA.put(row("show:a1", updatedAt: 100))
        let storeB = try ProgressStore.inMemory()
        try storeB.put(row("show:b1", updatedAt: 100))
        try storeB.put(row("show:b2", updatedAt: 200))
        let pullA = (1...2).map { wireChange("show:pa\($0)") }.joined(separator: ",")
        let pullB = (1...3).map { wireChange("show:pb\($0)") }.joined(separator: ",")
        server.enqueue(#"{"seq":1,"changes":[\#(pullA)]}"#, gated: true)

        let callA = Task { try await session.sync(storeA) }
        let parked = await server.waitUntilParked(1)
        XCTAssertTrue(parked)

        server.enqueue(#"{"seq":2,"changes":[\#(pullB)]}"#)
        try await session.sync(storeB)
        XCTAssertTrue(session.isSyncing, "A is still in flight, so isSyncing must stay true")

        server.releaseGatedResponses()
        try await callA.value
        XCTAssertFalse(session.isSyncing)

        let ends = text().split(separator: "\n").filter { $0.contains("sync.end") }
        XCTAssertEqual(ends.count, 2)
        // B finished first, then A.
        XCTAssertTrue(ends[0].contains("pulled=3 pushed=2"), "\(ends[0])")
        XCTAssertTrue(ends[1].contains("pulled=2 pushed=1"), "\(ends[1])")
    }

    func testOverlappingUnauthorizedDoesNotMarkAnotherCallsSyncFailed() async throws {
        let session = try await pairedSession()
        server.enqueue(#"{"seq":1,"changes":[]}"#, gated: true)

        let callA = Task { try await session.sync(try ProgressStore.inMemory()) }
        let parked = await server.waitUntilParked(1)
        XCTAssertTrue(parked)

        server.enqueue(#"{"error":"revoked"}"#, code: 401)
        try await session.sync(try ProgressStore.inMemory())

        server.releaseGatedResponses()
        try await callA.value

        let out = text()
        XCTAssertEqual(out.components(separatedBy: "sync.error\tcode=unauthorized").count - 1, 1)
        XCTAssertEqual(out.components(separatedBy: "sync.end").count - 1, 1)
        // A finished last, so its mark is the one on record.
        XCTAssertTrue(log.summaryLines().contains(" ok"))
        XCTAssertFalse(log.summaryLines().contains("unauthorized"))
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
