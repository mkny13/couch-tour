import CouchTourKit
import XCTest

final class ArtworkTests: XCTestCase {

    func testDeterministicHashConsistency() {
        let input1 = "grateful-dead:1977-05-08"
        let hash1 = ShowArtworkGenerator.deterministicHash(input1)
        let hash2 = ShowArtworkGenerator.deterministicHash(input1)

        XCTAssertEqual(hash1, hash2, "Deterministic hash must produce identical results across calls")
        XCTAssertNotEqual(hash1, 0, "Hash of non-empty string should not be 0")

        let input2 = "grateful-dead:1977-05-09"
        let hash3 = ShowArtworkGenerator.deterministicHash(input2)
        XCTAssertNotEqual(hash1, hash3, "Different inputs should produce different hashes")

        // FNV-1a offset basis for empty string
        let emptyHash = ShowArtworkGenerator.deterministicHash("")
        XCTAssertEqual(emptyHash, 0xcbf29ce484222325)
    }

    func testCuratedPalettes() {
        let gdPalette = ShowArtworkGenerator.palette(forArtist: "Grateful Dead", date: "1977-05-08")
        XCTAssertGreaterThan(gdPalette.primaryColor.red, 0.0)
        XCTAssertGreaterThan(gdPalette.secondaryColor.blue, 0.0)
        XCTAssertEqual(gdPalette.primaryColor.opacity, 1.0)

        let phishPalette = ShowArtworkGenerator.palette(forArtist: "Phish", date: "1997-12-07")
        XCTAssertGreaterThan(phishPalette.primaryColor.blue, 0.0)

        let goosePalette = ShowArtworkGenerator.palette(forArtist: "Goose", date: "2023-03-08")
        XCTAssertGreaterThan(goosePalette.primaryColor.green, 0.0)

        let billyPalette = ShowArtworkGenerator.palette(forArtist: "Billy Strings", date: "2022-10-31")
        XCTAssertGreaterThan(billyPalette.primaryColor.red, 0.0)

        let jgbPalette = ShowArtworkGenerator.palette(forArtist: "Jerry Garcia Band", date: "1990-09-01")
        XCTAssertGreaterThan(jgbPalette.primaryColor.red, 0.0)
        XCTAssertGreaterThan(jgbPalette.secondaryColor.red, 0.0)
    }

    func testProceduralPaletteGeneration() {
        let palette1 = ShowArtworkGenerator.palette(forArtist: "Arbitrary Indie Band", date: "2024-01-15")
        let palette2 = ShowArtworkGenerator.palette(forArtist: "Arbitrary Indie Band", date: "2024-01-15")

        XCTAssertEqual(palette1, palette2, "Procedural palette must be strictly deterministic")

        // Verify RGB ranges are clamped between 0.0 and 1.0
        XCTAssertTrue((0.0...1.0).contains(palette1.primaryColor.red))
        XCTAssertTrue((0.0...1.0).contains(palette1.primaryColor.green))
        XCTAssertTrue((0.0...1.0).contains(palette1.primaryColor.blue))
        XCTAssertTrue((0.0...1.0).contains(palette1.secondaryColor.red))
        XCTAssertTrue((0.0...1.0).contains(palette1.secondaryColor.green))
        XCTAssertTrue((0.0...1.0).contains(palette1.secondaryColor.blue))
        XCTAssertTrue((0.0...1.0).contains(palette1.accentColor.red))
        XCTAssertTrue((0.0...1.0).contains(palette1.accentColor.green))
        XCTAssertTrue((0.0...1.0).contains(palette1.accentColor.blue))
        XCTAssertTrue((0.0...1.0).contains(palette1.backgroundColor.red))
        XCTAssertTrue((0.0...1.0).contains(palette1.backgroundColor.green))
        XCTAssertTrue((0.0...1.0).contains(palette1.backgroundColor.blue))
    }

    func testHSVToRGBConversion() {
        // Red (0°)
        let red = ShowArtworkGenerator.hsvToRGB(h: 0, s: 1.0, v: 1.0)
        XCTAssertEqual(red.red, 1.0, accuracy: 0.01)
        XCTAssertEqual(red.green, 0.0, accuracy: 0.01)
        XCTAssertEqual(red.blue, 0.0, accuracy: 0.01)

        // Green (120°)
        let green = ShowArtworkGenerator.hsvToRGB(h: 120, s: 1.0, v: 1.0)
        XCTAssertEqual(green.red, 0.0, accuracy: 0.01)
        XCTAssertEqual(green.green, 1.0, accuracy: 0.01)
        XCTAssertEqual(green.blue, 0.0, accuracy: 0.01)

        // Blue (240°)
        let blue = ShowArtworkGenerator.hsvToRGB(h: 240, s: 1.0, v: 1.0)
        XCTAssertEqual(blue.red, 0.0, accuracy: 0.01)
        XCTAssertEqual(blue.green, 0.0, accuracy: 0.01)
        XCTAssertEqual(blue.blue, 1.0, accuracy: 0.01)
    }

    func testMonogramExtraction() {
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Grateful Dead"), "GD")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Jerry Garcia Band"), "JGB")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Phish"), "PH")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Goose"), "GOOSE")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Billy Strings"), "BMFS")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Widespread Panic"), "WSP")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Dead & Company"), "D&C")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Joe Russo's Almost Dead"), "JRAD")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "King Gizzard & The Lizard Wizard"), "KGLW")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "The Disco Biscuits"), "tDB")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Umphrey's McGee"), "UM")

        // Arbitrary multi-word
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "My Morning Jacket"), "MMJ")
        // Arbitrary single word
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "Aqueous"), "AQU")
        // Nil and empty
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: nil), "CT")
        XCTAssertEqual(ShowArtworkGenerator.monogram(for: "   "), "CT")
    }

    func testDateExtractionAndBadging() {
        XCTAssertEqual(ShowArtworkGenerator.year(from: "1977-05-08"), "1977")
        XCTAssertEqual(ShowArtworkGenerator.monthDay(from: "1977-05-08"), "05/08")
        XCTAssertEqual(ShowArtworkGenerator.dateBadge(from: "1977-05-08"), "1977 · 05/08")

        XCTAssertEqual(ShowArtworkGenerator.year(from: "1997-12-07"), "1997")
        XCTAssertEqual(ShowArtworkGenerator.monthDay(from: "1997-12-07"), "12/07")
        XCTAssertEqual(ShowArtworkGenerator.dateBadge(from: "1997-12-07"), "1997 · 12/07")

        XCTAssertEqual(ShowArtworkGenerator.year(from: "1989"), "1989")
        XCTAssertNil(ShowArtworkGenerator.monthDay(from: "1989"))
        XCTAssertEqual(ShowArtworkGenerator.dateBadge(from: "1989"), "1989")

        XCTAssertNil(ShowArtworkGenerator.year(from: nil))
        XCTAssertNil(ShowArtworkGenerator.monthDay(from: nil))
        XCTAssertEqual(ShowArtworkGenerator.dateBadge(from: nil), "LIVE")
    }
}
