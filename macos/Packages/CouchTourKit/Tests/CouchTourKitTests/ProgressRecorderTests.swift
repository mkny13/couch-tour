import XCTest
import GRDB
@testable import CouchTourKit

@MainActor
final class ProgressRecorderTests: XCTestCase {

    private var store: ProgressStore!
    private var recorder: ProgressRecorder!

    private let sampleArtist = ArtistRef(backend: .phishin, id: "phish", name: "Phish")
    private var sampleShow: ShowSummary {
        ShowSummary(artist: sampleArtist, date: "2026-07-29", venue: "MSG", location: "New York, NY")
    }

    private func sampleTrack(id: String = "t1", title: String = "Harpua") -> PlayableTrack {
        PlayableTrack(id: id, title: title, url: "https://example.com/audio.mp3")
    }

    override func setUpWithError() throws {
        try super.setUpWithError()
        store = try ProgressStore.inMemory()
        recorder = ProgressRecorder(store: store)
    }

    func testSavesInitialProgress() throws {
        let saved = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(),
            trackIndex: 9,
            positionMs: 10_000,
            artURL: nil,
            force: true
        )

        XCTAssertTrue(saved)
        let row = try store.get(key: "show:2026-07-29")
        XCTAssertNotNil(row)
        XCTAssertEqual(row?.trackTitle, "Harpua")
        XCTAssertEqual(row?.trackIndex, 9)
        XCTAssertEqual(row?.positionMs, 10_000)
    }

    func testSkipsSaveWhenPositionAndTrackUnchangedEvenWithForce() throws {
        let firstSaved = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(),
            trackIndex: 9,
            positionMs: 855_846,
            artURL: nil,
            force: true
        )
        XCTAssertTrue(firstSaved)
        let firstRow = try store.get(key: "show:2026-07-29")!
        let firstUpdatedAt = firstRow.updatedAt

        // Simulate sleep, audio route change, or redundant pause triggering saveTick with force=true
        let secondSaved = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(),
            trackIndex: 9,
            positionMs: 855_846,
            artURL: nil,
            force: true
        )

        XCTAssertFalse(secondSaved)
        let secondRow = try store.get(key: "show:2026-07-29")!
        XCTAssertEqual(secondRow.updatedAt, firstUpdatedAt, "Must not re-stamp updatedAt when position has not changed")
    }

    func testSavesWhenPositionChanges() throws {
        _ = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(),
            trackIndex: 9,
            positionMs: 10_000,
            artURL: nil,
            force: true
        )

        let advancedSaved = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(),
            trackIndex: 9,
            positionMs: 15_000,
            artURL: nil,
            force: true
        )

        XCTAssertTrue(advancedSaved)
        let row = try store.get(key: "show:2026-07-29")!
        XCTAssertEqual(row.positionMs, 15_000)
    }

    func testSavesWhenTrackChanges() throws {
        _ = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(id: "t1", title: "Harpua"),
            trackIndex: 9,
            positionMs: 855_846,
            artURL: nil,
            force: true
        )

        let nextTrackSaved = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(id: "t2", title: "You Enjoy Myself"),
            trackIndex: 10,
            positionMs: 0,
            artURL: nil,
            force: true
        )

        XCTAssertTrue(nextTrackSaved)
        let row = try store.get(key: "show:2026-07-29")!
        XCTAssertEqual(row.trackTitle, "You Enjoy Myself")
        XCTAssertEqual(row.trackIndex, 10)
        XCTAssertEqual(row.positionMs, 0)
    }

    func testSavesWhenQueueKeyChanges() throws {
        _ = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(),
            trackIndex: 0,
            positionMs: 5_000,
            artURL: nil,
            force: true
        )

        let otherShow = ShowSummary(artist: sampleArtist, date: "2026-07-31", venue: "MSG", location: "New York, NY")
        let otherSaved = recorder.saveTick(
            queueKey: "show:2026-07-31",
            show: otherShow,
            track: sampleTrack(),
            trackIndex: 0,
            positionMs: 5_000,
            artURL: nil,
            force: true
        )

        XCTAssertTrue(otherSaved)
        XCTAssertNotNil(try store.get(key: "show:2026-07-31"))
    }

    func testMarkFinishedResetsDeduplicationCache() throws {
        _ = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(),
            trackIndex: 9,
            positionMs: 855_846,
            artURL: nil,
            force: true
        )

        recorder.markFinished(queueKey: "show:2026-07-29")
        let finishedRow = try store.get(key: "show:2026-07-29")!
        XCTAssertTrue(finishedRow.finished)

        // Playing again from the same position should be permitted because markFinished cleared cache
        let replayedSaved = recorder.saveTick(
            queueKey: "show:2026-07-29",
            show: sampleShow,
            track: sampleTrack(),
            trackIndex: 9,
            positionMs: 855_846,
            artURL: nil,
            force: true
        )
        XCTAssertTrue(replayedSaved)
    }
}
