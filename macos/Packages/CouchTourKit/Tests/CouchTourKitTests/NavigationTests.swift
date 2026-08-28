import XCTest
@testable import CouchTourKit

/// The window's single navigation path (D203). `Route` is what the stack pushes, what the
/// toolbar breadcrumb reads, and what names the screen a feedback issue is filed against, so
/// its identity and its crumb text both matter.
final class NavigationTests: XCTestCase {

    private let dead = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
    private let wsp = ArtistRef(backend: .relisten, id: "wsp", name: "Widespread Panic")

    // ---------------------------------------------------------------- crumb titles

    func testCrumbTitlesUseTheContentsOwnName() {
        let show = ShowSummary(artist: dead, date: "1977-05-08")
        let period = PeriodRef(id: "1977", label: "1977")
        let playlist = LocalPlaylist(id: "p1", name: "Road trip", createdAt: 0, updatedAt: 0)

        XCTAssertEqual(Route.artists.crumbTitle, "Artists")
        XCTAssertEqual(Route.listening.crumbTitle, "Listening")
        XCTAssertEqual(Route.artist(dead).crumbTitle, "Grateful Dead")
        XCTAssertEqual(Route.period(artist: dead, period: period).crumbTitle, "1977")
        XCTAssertEqual(Route.show(show).crumbTitle, "1977-05-08")
        XCTAssertEqual(Route.localPlaylist(playlist).crumbTitle, "Road trip")
    }

    // ---------------------------------------------------------------- breadcrumb trail

    /// An empty path is Home, which still gets a crumb — the window is never nowhere, and the
    /// root crumb is the affordance that takes you back to it.
    func testEmptyPathIsJustTheAppName() {
        XCTAssertEqual(breadcrumbTrail(path: []), ["Couch Tour"])
    }

    func testTrailIsTheAppNameThenEveryLevelDrilledIn() {
        let period = PeriodRef(id: "1997", label: "1997")
        let trail = breadcrumbTrail(path: [.artists, .artist(dead), .period(artist: dead, period: period)])
        XCTAssertEqual(trail, ["Couch Tour", "Artists", "Grateful Dead", "1997"])
    }

    // ---------------------------------------------------------------- player bar routing

    /// D202 pushed one bare entry; with a single stack the levels above the destination are
    /// synthesized too, so Back walks up through them instead of jumping straight to Home.
    func testPlayerBarShowRouteSynthesizesTheLevelsAboveIt() {
        let show = ShowSummary(artist: dead, date: "1977-05-08")
        XCTAssertEqual(routes(for: .show(show)), [.artists, .artist(dead), .show(show)])
    }

    func testPlayerBarArtistRouteLandsOnTheArtistUnderArtists() {
        XCTAssertEqual(routes(for: .artist(wsp)), [.artists, .artist(wsp)])
    }

    // ---------------------------------------------------------------- identity

    /// The stack diffs by `Hashable` identity, so two routes of the same kind for different
    /// content must not collide — and a show must not collide with its own artist, since the
    /// two return the hierarchy to different depths.
    func testRoutesForDifferentContentAreDistinct() {
        let show = ShowSummary(artist: dead, date: "1977-05-08")
        XCTAssertNotEqual(Route.artist(dead), .artist(wsp))
        XCTAssertNotEqual(Route.show(show), .show(ShowSummary(artist: dead, date: "1977-05-09")))
        XCTAssertNotEqual(Route.show(show), .artist(dead))
        XCTAssertNotEqual(Route.artists, .listening)
    }
}
