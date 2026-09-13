import XCTest
@testable import CouchTourKit

final class SecurityValidatorTests: XCTestCase {
    
    func testCastModelsRejectsNonHTTPS() {
        let track = PlayableTrack(
            id: "1",
            title: "Dangerous",
            setName: "Set 1",
            position: 1,
            durationMs: 1000,
            url: "http://insecure.com/stream.mp3",
            waveformURL: nil,
            showDate: "2000-01-01",
            venueName: "Venue",
            flacUrl: "file:///etc/passwd",
            tags: []
        )
        
        let mediaInfo = CastItemConverter.toMediaInfo(track: track, show: nil, queueKey: nil)
        
        XCTAssertEqual(mediaInfo["contentId"] as? String, "https://invalid.local/blocked")
        
        if let customData = mediaInfo["customData"] as? [String: Any] {
            XCTAssertEqual(customData[CastKeys.uri] as? String, "https://invalid.local/blocked")
            XCTAssertEqual(customData[CastKeys.mp3Url] as? String, "https://invalid.local/blocked")
        } else {
            XCTFail("Missing customData")
        }
    }
    
    func testWaveformLoaderRejectsNonHTTPS() {
        XCTAssertNil(WaveformLoader.archiveOrgWaveformURL(from: "http://archive.org/download/test/test.mp3"))
        XCTAssertEqual(
            WaveformLoader.archiveOrgWaveformURL(from: "https://archive.org/download/test/test.mp3"),
            "https://archive.org/download/test/test.png"
        )
    }
}
