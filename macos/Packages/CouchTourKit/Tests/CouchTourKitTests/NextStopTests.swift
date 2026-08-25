import XCTest
@testable import CouchTourKit

final class NextStopTests: XCTestCase {

    func testResolveNextConsecutiveShowPicksNextShowInSameTour() {
        let candidateShows = [
            ShowSummary(artist: PHISH, date: "1997-11-16", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-11-17", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-11-19", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-11-21", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-12-30", tourName: "1997 NYE Run"),
        ]

        let next = resolveNextConsecutiveShow(
            currentDate: "1997-11-17",
            tourName: "1997 Fall Tour",
            candidateShows: candidateShows
        )

        XCTAssertEqual("1997-11-19", next?.date)
        XCTAssertEqual("1997 Fall Tour", next?.tourName)
    }

    func testResolveNextConsecutiveShowFallsBackToNextChronologicalShowIfTourEnded() {
        let candidateShows = [
            ShowSummary(artist: PHISH, date: "1997-11-17", tourName: "1997 Fall Tour"),
            ShowSummary(artist: PHISH, date: "1997-12-30", tourName: "1997 NYE Run"),
            ShowSummary(artist: PHISH, date: "1997-12-31", tourName: "1997 NYE Run"),
        ]

        let next = resolveNextConsecutiveShow(
            currentDate: "1997-11-17",
            tourName: "1997 Fall Tour",
            candidateShows: candidateShows
        )

        XCTAssertEqual("1997-12-30", next?.date)
    }

    func testResolveNextConsecutiveShowReturnsNilWhenNoFutureShows() {
        let candidateShows = [
            ShowSummary(artist: PHISH, date: "1997-11-17", tourName: "1997 Fall Tour"),
        ]

        let next = resolveNextConsecutiveShow(
            currentDate: "1997-11-17",
            tourName: "1997 Fall Tour",
            candidateShows: candidateShows
        )

        XCTAssertNil(next)
    }
}
