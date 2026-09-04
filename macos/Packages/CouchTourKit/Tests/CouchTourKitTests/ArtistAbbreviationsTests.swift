import XCTest
@testable import CouchTourKit

final class ArtistAbbreviationsTests: XCTestCase {
    func testAbbreviationsMapping() {
        XCTAssertEqual(ArtistAbbreviations.label(for: "Phish", fits: false), "PH")
        XCTAssertEqual(ArtistAbbreviations.label(for: "Phish", fits: true), "Phish")
        XCTAssertEqual(ArtistAbbreviations.label(for: "Grateful Dead", fits: false), "GD")
        XCTAssertEqual(ArtistAbbreviations.label(for: "Gov't Mule", fits: false), "mule")
        XCTAssertEqual(ArtistAbbreviations.label(for: "Unknown Band", fits: false), "Unknown Band")
    }

    func testMoeFormatting() {
        XCTAssertEqual(ArtistAbbreviations.label(for: "moe.", fits: false), "moe.")
        XCTAssertEqual(ArtistAbbreviations.label(for: "Moe", fits: false), "moe.")
        XCTAssertEqual(ArtistAbbreviations.label(for: "MOE.", fits: true), "moe.")
    }
}
