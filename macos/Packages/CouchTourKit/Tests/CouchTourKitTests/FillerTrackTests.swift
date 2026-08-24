import CouchTourKit
import XCTest

final class FillerTrackTests: XCTestCase {

    func testIdentifiesStandardFillerTitles() {
        // Intros
        XCTAssertTrue(isFillerTrack("Intro"))
        XCTAssertTrue(isFillerTrack("intro"))
        XCTAssertTrue(isFillerTrack("Introduction"))
        XCTAssertTrue(isFillerTrack("Band Intro"))
        XCTAssertTrue(isFillerTrack("Band Introductions"))
        XCTAssertTrue(isFillerTrack("Crowd Intro"))
        XCTAssertTrue(isFillerTrack("Intro ->"))

        // Outros
        XCTAssertTrue(isFillerTrack("Outro"))
        XCTAssertTrue(isFillerTrack("Outroduction"))
        XCTAssertTrue(isFillerTrack("Band Outro"))

        // Tuning & Dead air
        XCTAssertTrue(isFillerTrack("Tuning"))
        XCTAssertTrue(isFillerTrack("Stage Tuning"))
        XCTAssertTrue(isFillerTrack("Tuning / Dead Air"))
        XCTAssertTrue(isFillerTrack("Tuning/Dead Air"))
        XCTAssertTrue(isFillerTrack("Dead Air"))
        XCTAssertTrue(isFillerTrack("Tuning ->"))
        XCTAssertTrue(isFillerTrack("Tuning >"))

        // Banter & Talk
        XCTAssertTrue(isFillerTrack("Banter"))
        XCTAssertTrue(isFillerTrack("Stage Banter"))
        XCTAssertTrue(isFillerTrack("Chatter"))
        XCTAssertTrue(isFillerTrack("Stage Talk"))

        // Crowd & Announcements
        XCTAssertTrue(isFillerTrack("Crowd"))
        XCTAssertTrue(isFillerTrack("Crowd Noise"))
        XCTAssertTrue(isFillerTrack("Crowd / Applause"))
        XCTAssertTrue(isFillerTrack("Applause"))
        XCTAssertTrue(isFillerTrack("Take A Step Back"))
        XCTAssertTrue(isFillerTrack("Take A Step Back / Tuning"))
        XCTAssertTrue(isFillerTrack("Stage Announcement"))
        XCTAssertTrue(isFillerTrack("Encore Break"))
    }

    func testDoesNotIdentifyGenuineSongsAsFiller() {
        XCTAssertFalse(isFillerTrack("Divided Sky"))
        XCTAssertFalse(isFillerTrack("The Curtain With"))
        XCTAssertFalse(isFillerTrack("Tweezer Reprise"))
        XCTAssertFalse(isFillerTrack("Drums"))
        XCTAssertFalse(isFillerTrack("Space"))
        XCTAssertFalse(isFillerTrack("Playing in the Band"))
        XCTAssertFalse(isFillerTrack("Estimated Prophet"))
        XCTAssertFalse(isFillerTrack("St. Stephen"))
        XCTAssertFalse(isFillerTrack("Scarlet Begonias"))
        XCTAssertFalse(isFillerTrack("Morning Dew"))
        XCTAssertFalse(isFillerTrack("Dark Star"))
    }

    private func makeTrack(id: String, title: String) -> PlayableTrack {
        PlayableTrack(
            id: id,
            title: title,
            setName: "Set 1",
            durationMs: 180000,
            url: "https://example.com/\(id).mp3"
        )
    }

    func testFilterPlaybackTracksWhenDisabledReturnsOriginal() {
        let tracks = [
            makeTrack(id: "1", title: "Intro"),
            makeTrack(id: "2", title: "Divided Sky"),
            makeTrack(id: "3", title: "Tuning"),
            makeTrack(id: "4", title: "Tweezer"),
        ]

        let result = filterPlaybackTracks(tracks: tracks, startIndex: 0, skipFiller: false)
        XCTAssertEqual(result.tracks.count, 4)
        XCTAssertEqual(result.startIndex, 0)
    }

    func testFilterPlaybackTracksWhenEnabledStartsAtFirstNonFiller() {
        let tracks = [
            makeTrack(id: "1", title: "Intro"),
            makeTrack(id: "2", title: "Divided Sky"),
            makeTrack(id: "3", title: "Tuning"),
            makeTrack(id: "4", title: "Tweezer"),
            makeTrack(id: "5", title: "Outro"),
        ]

        let result = filterPlaybackTracks(tracks: tracks, startIndex: 0, skipFiller: true)
        XCTAssertEqual(result.tracks.map(\.title), ["Divided Sky", "Tweezer"])
        XCTAssertEqual(result.startIndex, 0)
    }

    func testFilterPlaybackTracksWhenTappedNonFillerMaintainsCorrectIndex() {
        let tracks = [
            makeTrack(id: "1", title: "Intro"),
            makeTrack(id: "2", title: "Divided Sky"),
            makeTrack(id: "3", title: "Tuning"),
            makeTrack(id: "4", title: "Tweezer"),
        ]

        // User tapped Tweezer (index 3 in original list)
        let result = filterPlaybackTracks(tracks: tracks, startIndex: 3, skipFiller: true)
        XCTAssertEqual(result.tracks.map(\.title), ["Divided Sky", "Tweezer"])
        XCTAssertEqual(result.startIndex, 1) // Tweezer is index 1 in filtered list
    }

    func testFilterPlaybackTracksWhenExplicitlyTappedFillerPlaysThatFiller() {
        let tracks = [
            makeTrack(id: "1", title: "Intro"),
            makeTrack(id: "2", title: "Divided Sky"),
            makeTrack(id: "3", title: "Take A Step Back"),
            makeTrack(id: "4", title: "Tweezer"),
            makeTrack(id: "5", title: "Outro"),
        ]

        // User explicitly tapped "Take A Step Back" (index 2)
        let result = filterPlaybackTracks(tracks: tracks, startIndex: 2, skipFiller: true)
        XCTAssertEqual(result.tracks.map(\.title), ["Divided Sky", "Take A Step Back", "Tweezer"])
        XCTAssertEqual(result.startIndex, 1) // "Take A Step Back" is index 1 in filtered list
    }
}
