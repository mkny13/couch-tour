import XCTest
@testable import CouchTourKit

final class FormatTests: XCTestCase {

    func testFormatsSubMinuteDurations() {
        XCTAssertEqual("0:00", fmt(0))
        XCTAssertEqual("0:01", fmt(1_000))
        XCTAssertEqual("0:09", fmt(9_999))
        XCTAssertEqual("0:59", fmt(59_999))
    }

    func testPadsSecondsToTwoDigits() {
        XCTAssertEqual("1:00", fmt(60_000))
        XCTAssertEqual("1:05", fmt(65_000))
        XCTAssertEqual("12:46", fmt(766_000))
    }

    func testSwitchesToHoursOnlyAtTheHourBoundary() {
        XCTAssertEqual("59:59", fmt(3_599_999))
        XCTAssertEqual("1:00:00", fmt(3_600_000))
        // A real show length, from 1997-02-13.
        XCTAssertEqual("3:04:16", fmt(11_056_537))
    }

    func testTreatsNegativeDurationsAsZero() {
        XCTAssertEqual("0:00", fmt(-1))
        XCTAssertEqual("0:00", fmt(-500_000))
    }

    func testPluralisesOnlyWhenCountIsNotOne() {
        XCTAssertEqual("show", plural(1, "show"))
        XCTAssertEqual("shows", plural(0, "show"))
        XCTAssertEqual("shows", plural(2, "show"))
        XCTAssertEqual("tracks", plural(99, "track"))
    }

    func testMapsAScrubberTouchToATrackPosition() {
        XCTAssertEqual(0, positionAt(x: 0, widthPx: 1000, durationMs: 60_000))
        XCTAssertEqual(30_000, positionAt(x: 500, widthPx: 1000, durationMs: 60_000))
        XCTAssertEqual(60_000, positionAt(x: 1000, widthPx: 1000, durationMs: 60_000))
    }

    func testClampsScrubberTouchesOutsideTheWidget() {
        XCTAssertEqual(0, positionAt(x: -250, widthPx: 1000, durationMs: 60_000))
        XCTAssertEqual(60_000, positionAt(x: 1500, widthPx: 1000, durationMs: 60_000))
    }

    func testReturnsZeroRatherThanDividingByZeroBeforeLayout() {
        // Both happen for real: width is 0 until measured, duration is 0 until prepared.
        XCTAssertEqual(0, positionAt(x: 120, widthPx: 0, durationMs: 60_000))
        XCTAssertEqual(0, positionAt(x: 120, widthPx: 1000, durationMs: 0))
    }
}
