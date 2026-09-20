import XCTest
@testable import CouchTourKit

/// Pins the `PlaybackMode` model the player branches on (#231, D256). YouTube playback is
/// visible-only — there is no audio-only/background mode to switch to, so the enum has
/// exactly two cases and only the `youtube:` queue kind maps to the video surface.
final class PlaybackModeTests: XCTestCase {

    func testYouTubeKindMapsToTheVideoSurface() {
        XCTAssertEqual(.youtubeVideo, playbackMode(for: .youtube))
    }

    func testAudioKindsMapToTheAudioSurface() {
        for kind in [QueueKind.show, .playlist, .recording, .localPlaylist] {
            XCTAssertEqual(.audio, playbackMode(for: kind), "\(kind) must stay on the audio path")
        }
    }

    func testTheTwoModesAreDistinct() {
        // The reason there is no toggle: audio-only YouTube was dropped (owner decision on
        // #231), so .audio and .youtubeVideo are the only states and they never compare equal.
        XCTAssertNotEqual(PlaybackMode.audio, PlaybackMode.youtubeVideo)
        XCTAssertEqual([PlaybackMode.audio, .youtubeVideo], [PlaybackMode.audio, .youtubeVideo])
    }
}