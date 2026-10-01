import XCTest
@testable import CouchTourKit

final class DiagnosticsLogTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("diag-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private func file(_ name: String) -> URL { dir.appendingPathComponent(name) }

    private func iso(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: date)
    }

    func testLineFormatIsTabSeparated() {
        let log = DiagnosticsLog(directory: dir)
        log.log(.warn, "api.request", [("path", "/shows"), ("ms", "12")])
        log.flush()
        let parts = log.tailLines(1)[0].split(separator: "\t").map(String.init)
        XCTAssertEqual(parts.count, 4)
        XCTAssertNotNil(ISO8601DateFormatter().date(from: String(parts[0].prefix(19)) + "Z"))
        XCTAssertEqual(Array(parts[1...]), ["WARN", "api.request", "path=/shows ms=12"])
    }

    func testNewlinesAndTabsInValuesStayOnOneLine() {
        let log = DiagnosticsLog(directory: dir)
        log.log(.info, "x", [("msg", "a\nb\tc")])
        log.flush()
        XCTAssertEqual(log.tailLines(5).count, 1)
        XCTAssertTrue(log.tailLines(1)[0].hasSuffix("msg=a b c"))
    }

    func testSensitiveKeysAreRedacted() {
        let log = DiagnosticsLog(directory: dir)
        let keys = ["token", "secret", "password", "syncKey", "accessToken", "pairingCode",
                    "access_token", "Authorization", "cookie", "api_key"]
        log.log(.info, "e", keys.map { ($0, "hunter2") })
        log.log(.info, "e2", [("key", "visible"), ("code", "200"), ("status", "ok")])
        log.flush()
        let text = log.exportText()
        XCTAssertFalse(text.contains("hunter2"))
        for k in keys { XCTAssertTrue(text.contains("\(k)=***"), k) }
        XCTAssertTrue(text.contains("key=visible code=200 status=ok"))
    }

    func testRotationBoundsFootprintAtTwoMiB() {
        let log = DiagnosticsLog(directory: dir)
        let big = String(repeating: "x", count: 1000)
        for i in 0..<5000 { log.log(.info, "fill", [("i", "\(i)"), ("pad", big)]) }
        log.flush()
        XCTAssertTrue(FileManager.default.fileExists(atPath: file("diagnostics.log.1").path))
        XCTAssertLessThanOrEqual(log.onDiskBytes(), 2 * DiagnosticsLog.maxFileBytes + 2048)
        XCTAssertLessThanOrEqual(Int64((try? Data(contentsOf: file("diagnostics.log")).count) ?? 0),
                                 DiagnosticsLog.maxFileBytes + 2048)
        XCTAssertTrue(log.exportText().contains("\tlog.rotated"))
        XCTAssertTrue(log.tailLines(1)[0].contains("i=4999"))
    }

    func testRetentionDropsOldEntriesInBothGenerations() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let now = Date()
        let old = iso(now.addingTimeInterval(-8 * 86_400)), fresh = iso(now.addingTimeInterval(-86_400))
        try "\(old)\tINFO\told\n\(fresh)\tINFO\tfresh\n".write(to: file("diagnostics.log"), atomically: true, encoding: .utf8)
        try "\(old)\tINFO\told2\n".write(to: file("diagnostics.log.1"), atomically: true, encoding: .utf8)
        let log = DiagnosticsLog(directory: dir, now: { now })
        let text = log.exportText()
        XCTAssertFalse(text.contains("old"))
        XCTAssertTrue(text.contains("fresh"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file("diagnostics.log.1").path))
    }

    func testOverCapFileTruncatesToLast2000Lines() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let now = Date()
        let ts = iso(now)
        let pad = String(repeating: "p", count: 400)
        var s = ""
        for i in 0..<4000 { s += "\(ts)\tINFO\te\tn=\(i) pad=\(pad)\n" }
        XCTAssertGreaterThan(s.utf8.count, Int(DiagnosticsLog.maxFileBytes))
        try s.write(to: file("diagnostics.log"), atomically: true, encoding: .utf8)
        let log = DiagnosticsLog(directory: dir, now: { now })
        let lines = log.exportText().split(separator: "\n")
        XCTAssertEqual(lines.count, 2000)
        XCTAssertTrue(lines.first!.contains("n=2000 "))
        XCTAssertTrue(lines.last!.contains("n=3999 "))
    }

    func testTailLinesChronologicalAndBounded() {
        let log = DiagnosticsLog(directory: dir)
        for i in 0..<10 { log.log(.info, "e\(i)") }
        log.flush()
        XCTAssertEqual(log.tailLines(3).map { $0.split(separator: "\t")[2] }, ["e7", "e8", "e9"])
        XCTAssertEqual(log.tailLines(100).count, 10)
        XCTAssertTrue(log.tailLines(0).isEmpty)
    }

    func testTailLinesDoesNotReadWholeLargeFile() {
        let log = DiagnosticsLog(directory: dir)
        let pad = String(repeating: "x", count: 1000)
        for i in 0..<900 { log.log(.info, "e", [("i", "\(i)"), ("pad", pad)]) }
        log.flush()
        let lines = log.tailLines(100_000)
        XCTAssertLessThan(lines.count, 900)
        XCTAssertLessThanOrEqual(lines.reduce(0) { $0 + $1.utf8.count + 1 }, DiagnosticsLog.maxTailBytes)
        XCTAssertTrue(lines.last!.contains("i=899 "))
        XCTAssertTrue(lines.allSatisfy { $0.contains("\tINFO\te\t") }, "partial leading line dropped")
    }

    func testTailSpansIntoPreviousGeneration() {
        let log = DiagnosticsLog(directory: dir)
        let pad = String(repeating: "x", count: 1000)
        for i in 0..<1100 { log.log(.info, "e", [("i", "\(i)"), ("pad", pad)]) }
        log.flush()
        XCTAssertTrue(FileManager.default.fileExists(atPath: file("diagnostics.log.1").path))
        let lines = log.tailLines(150)
        XCTAssertEqual(lines.count, 150)
        XCTAssertTrue(lines.last!.contains("i=1099 "))
    }

    func testSummaryReportsMarksEntriesAndSize() {
        let log = DiagnosticsLog(directory: dir)
        log.mark(key: "Last sync", value: "ok 12:00")
        log.log(.info, "a"); log.log(.info, "b")
        log.flush()
        let summary = log.summaryLines()
        XCTAssertTrue(summary.contains("Last sync: ok 12:00"))
        XCTAssertTrue(summary.contains("Entries: 2"))
        XCTAssertTrue(summary.contains("Size: \(log.onDiskBytes()) bytes"))
        XCTAssertGreaterThan(log.onDiskBytes(), 0)
    }

    func testClearRemovesFilesAndMarks() {
        let log = DiagnosticsLog(directory: dir)
        log.mark(key: "Last sync", value: "ok")
        log.log(.info, "a")
        log.clear()
        XCTAssertEqual(log.onDiskBytes(), 0)
        XCTAssertEqual(log.exportText(), "")
        XCTAssertFalse(log.summaryLines().contains("Last sync"))
        log.log(.info, "after"); log.flush()
        XCTAssertEqual(log.tailLines(5).count, 1)
    }

    func testIOFailureLatchesOffWithoutThrowing() throws {
        // A regular file where the directory should be makes every write fail.
        try Data().write(to: dir)
        let log = DiagnosticsLog(directory: dir)
        log.log(.error, "boom")
        log.mark(key: "k", value: "v")
        log.flush()
        XCTAssertEqual(log.tailLines(5), [])
        XCTAssertEqual(log.exportText(), "")
        _ = log.summaryLines()
    }

    func testConcurrentLoggingKeepsEveryLine() {
        let log = DiagnosticsLog(directory: dir)
        DispatchQueue.concurrentPerform(iterations: 200) { log.log(.info, "c", [("i", "\($0)")]) }
        log.flush()
        XCTAssertEqual(log.tailLines(1000).count, 200)
    }
}
