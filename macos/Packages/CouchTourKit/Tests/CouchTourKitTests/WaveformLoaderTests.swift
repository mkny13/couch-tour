import XCTest
@testable import CouchTourKit

final class WaveformLoaderTests: XCTestCase {
    func testArchiveOrgWaveformURLDerivation() {
        let mp3URL = "https://archive.org/download/gd77-05-08.maizner.hicks.5002.sbeok.shnf/gd77-05-08aud-d1t01.mp3"
        let expected = "https://archive.org/download/gd77-05-08.maizner.hicks.5002.sbeok.shnf/gd77-05-08aud-d1t01.png"
        XCTAssertEqual(WaveformLoader.archiveOrgWaveformURL(from: mp3URL), expected)

        // Non-archive.org URL returns nil
        XCTAssertNil(WaveformLoader.archiveOrgWaveformURL(from: "https://phish.in/blob/audio.mp3"))
        // Non-mp3 returns nil
        XCTAssertNil(WaveformLoader.archiveOrgWaveformURL(from: "https://archive.org/download/item/file.flac"))
    }

    func testExtractHeightsFromPhishInFixture() throws {
        let data = try fixtureData("phishin_waveform.png")
        guard let heights = WaveformLoader.extractHeights(from: data, sampleCount: 95) else {
            XCTFail("Failed to extract heights from phish.in fixture")
            return
        }

        XCTAssertEqual(heights.count, 95)
        // All heights should be bounded within [0.08, 0.95]
        for h in heights {
            XCTAssertGreaterThanOrEqual(h, 0.08)
            XCTAssertLessThanOrEqual(h, 0.95)
        }
        // Should have high peaks (>= 0.8) and dynamic variation
        let maxPeak = heights.max() ?? 0
        XCTAssertGreaterThan(maxPeak, 0.8)
        let minPeak = heights.min() ?? 1
        XCTAssertNotEqual(maxPeak, minPeak)
    }

    func testExtractHeightsFromArchiveOrgFixture() throws {
        let data = try fixtureData("archive_waveform.png")
        guard let heights = WaveformLoader.extractHeights(from: data, sampleCount: 95) else {
            XCTFail("Failed to extract heights from archive.org fixture")
            return
        }

        XCTAssertEqual(heights.count, 95)
        for h in heights {
            XCTAssertGreaterThanOrEqual(h, 0.08)
            XCTAssertLessThanOrEqual(h, 0.95)
        }
        let maxPeak = heights.max() ?? 0
        XCTAssertGreaterThan(maxPeak, 0.8)
    }

    func testRelistenSourceTrackPopulatesWaveformURL() {
        let track = RelistenSourceTrack(
            uuid: "t1",
            title: "Help on the Way",
            duration: 300,
            mp3Url: "https://archive.org/download/gd75-08-13.sbd/track01.mp3"
        )
        let artist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
        let playable = track.toPlayableTrack(artist: artist, showDate: "1975-08-13", venueName: "Great American", setName: "Set 1")

        XCTAssertEqual(playable.waveformURL, "https://archive.org/download/gd75-08-13.sbd/track01.png")
    }

    func testExtractEnvelopeFromPhishInFixture() throws {
        let data = try fixtureData("phishin_waveform.png")
        guard let envelope = WaveformLoader.extractEnvelope(from: data, sampleCount: 200) else {
            XCTFail("Failed to extract envelope from phish.in fixture")
            return
        }

        XCTAssertEqual(envelope.top.count, 200)
        XCTAssertEqual(envelope.bottom.count, 200)
        for val in envelope.top + envelope.bottom {
            XCTAssertGreaterThanOrEqual(val, 0.04)
            XCTAssertLessThanOrEqual(val, 0.96)
        }
        let maxPeak = max(envelope.top.max() ?? 0, envelope.bottom.max() ?? 0)
        XCTAssertGreaterThan(maxPeak, 0.8)
    }

    func testExtractEnvelopeFromArchiveOrgFixture() throws {
        let data = try fixtureData("archive_waveform.png")
        guard let envelope = WaveformLoader.extractEnvelope(from: data, sampleCount: 200) else {
            XCTFail("Failed to extract envelope from archive.org fixture")
            return
        }

        XCTAssertEqual(envelope.top.count, 200)
        XCTAssertEqual(envelope.bottom.count, 200)
        for val in envelope.top + envelope.bottom {
            XCTAssertGreaterThanOrEqual(val, 0.04)
            XCTAssertLessThanOrEqual(val, 0.96)
        }
        let maxPeak = max(envelope.top.max() ?? 0, envelope.bottom.max() ?? 0)
        XCTAssertGreaterThan(maxPeak, 0.8)
    }
}
