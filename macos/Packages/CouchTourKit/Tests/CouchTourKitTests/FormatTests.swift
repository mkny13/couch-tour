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

    func testProgressFractionClampsBetweenZeroAndOne() {
        XCTAssertEqual(0.0, progressFraction(positionMs: 0, durationMs: 100_000), accuracy: 0.001)
        XCTAssertEqual(0.5, progressFraction(positionMs: 50_000, durationMs: 100_000), accuracy: 0.001)
        XCTAssertEqual(1.0, progressFraction(positionMs: 100_000, durationMs: 100_000), accuracy: 0.001)
        XCTAssertEqual(1.0, progressFraction(positionMs: 150_000, durationMs: 100_000), accuracy: 0.001)
        XCTAssertEqual(0.0, progressFraction(positionMs: -50_000, durationMs: 100_000), accuracy: 0.001)
        XCTAssertEqual(0.0, progressFraction(positionMs: 50_000, durationMs: 0), accuracy: 0.001)
    }

    func testFormatCompactDuration() {
        // Sub-hour: m:ss
        XCTAssertEqual("1:06", formatCompactDuration(ms: 66_000))
        XCTAssertEqual("12:44", formatCompactDuration(ms: 764_000))
        // Multi-hour: h:mm
        XCTAssertEqual("2:41", formatCompactDuration(ms: 9_660_000))
        XCTAssertEqual("0:00", formatCompactDuration(ms: 0))
    }

    func testFormatRemainingTime() {
        XCTAssertEqual("7:32 left", formatRemainingTime(positionMs: 312_000, durationMs: 764_000))
        XCTAssertEqual("0:00 left", formatRemainingTime(positionMs: 800_000, durationMs: 764_000))
        XCTAssertEqual("0:00 left", formatRemainingTime(positionMs: 0, durationMs: 0))
    }

    func testFormatShowDate() {
        XCTAssertEqual("1997-11-17", formatShowDate("1997-11-17"))
        XCTAssertEqual("1997-11-17", formatShowDate("1997/11/17"))
        XCTAssertEqual("1977-05-08", formatShowDate("May 8, 1977"))
    }

    func testFormatSetRoman() {
        XCTAssertEqual("SET I", formatSetRoman("Set 1"))
        XCTAssertEqual("SET II", formatSetRoman("Set 2"))
        XCTAssertEqual("SET III", formatSetRoman("Set 3"))
        XCTAssertEqual("SET IV", formatSetRoman("Set 4"))
        XCTAssertEqual("ENCORE", formatSetRoman("Encore"))
        XCTAssertEqual("SOUNDCHECK", formatSetRoman("Soundcheck"))
        XCTAssertEqual("SET 5", formatSetRoman("Set 5"))
    }

    func testFormatSetAndTrackEyebrow() {
        XCTAssertEqual("SET II · TRACK 4", formatSetAndTrackEyebrow(setName: "Set 2", trackPosition: 4, fallbackIndex: 1))
        XCTAssertEqual("SET I · TRACK 1", formatSetAndTrackEyebrow(setName: "Set 1", trackPosition: nil, fallbackIndex: 1))
        XCTAssertEqual("ENCORE · TRACK 2", formatSetAndTrackEyebrow(setName: "Encore", trackPosition: 2, fallbackIndex: 2))
        XCTAssertEqual("TRACK 7", formatSetAndTrackEyebrow(setName: "", trackPosition: nil, fallbackIndex: 7))
        XCTAssertEqual("TRACK 3", formatSetAndTrackEyebrow(setName: nil, trackPosition: 3, fallbackIndex: 1))
    }

    func testFormatSetColumn() {
        XCTAssertEqual("II · Track 4", formatSetColumn(setName: "Set 2", trackPosition: 4, fallbackIndex: 1))
        XCTAssertEqual("I · Track 1", formatSetColumn(setName: "Set 1", trackPosition: nil, fallbackIndex: 1))
        XCTAssertEqual("E · Track 2", formatSetColumn(setName: "Encore", trackPosition: 2, fallbackIndex: 2))
        XCTAssertEqual("Track 5", formatSetColumn(setName: "", trackPosition: nil, fallbackIndex: 5))
    }
}
