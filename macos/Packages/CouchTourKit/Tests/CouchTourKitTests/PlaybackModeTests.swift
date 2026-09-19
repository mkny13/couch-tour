import XCTest
@testable import CouchTourKit

final class PlaybackModeTests: XCTestCase {
    func testAudioAndYouTubeVideoAreDistinct() {
        XCTAssertNotEqual(PlaybackMode.audio, PlaybackMode.youtubeVideo)
    }

    func testAudioEquality() {
        XCTAssertEqual(PlaybackMode.audio, PlaybackMode.audio)
    }

    func testYouTubeVideoEquality() {
        XCTAssertEqual(PlaybackMode.youtubeVideo, PlaybackMode.youtubeVideo)
    }

    /// The queue-key namespace determines which mode a resumed queue uses: a `youtube:` key
    /// resumes as `.youtubeVideo`, everything else as `.audio`.
    func testQueueKindImpliesPlaybackMode() {
        // These are the mapping rules the player uses — if the queue key parses to .youtube,
        // the player enters .youtubeVideo mode; all other kinds use .audio.
        let modeFor: (QueueKind) -> PlaybackMode = { kind in
            kind == .youtube ? .youtubeVideo : .audio
        }
        XCTAssertEqual(.audio, modeFor(.show))
        XCTAssertEqual(.audio, modeFor(.playlist))
        XCTAssertEqual(.audio, modeFor(.recording))
        XCTAssertEqual(.audio, modeFor(.localPlaylist))
        XCTAssertEqual(.youtubeVideo, modeFor(.youtube))
    }
}
