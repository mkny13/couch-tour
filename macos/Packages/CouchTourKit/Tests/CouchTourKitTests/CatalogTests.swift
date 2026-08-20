import XCTest
@testable import CouchTourKit

/// The phish.in half of the `MusicSource` seam. Mapping is kept in pure functions so it can be
/// checked without a network call — port of CatalogTest.kt.
final class CatalogTests: XCTestCase {

    private func track(
        id: Int64 = 1,
        title: String = "Tweezer",
        setName: String = "Set 2",
        duration: Int64 = 1_200_000,
        mp3Url: String? = "https://phish.in/a.mp3",
        audioStatus: String = "complete",
        waveformImageUrl: String? = "https://phish.in/w.png",
        showDate: String? = "1997-11-17",
        venueName: String? = "McNichols Arena",
        showAlbumCoverUrl: String? = nil
    ) -> Track {
        Track(
            id: id, title: title, position: 0, duration: duration, setName: setName,
            audioStatus: audioStatus, mp3Url: mp3Url, waveformImageUrl: waveformImageUrl,
            showDate: showDate, venueName: venueName, showAlbumCoverUrl: showAlbumCoverUrl
        )
    }

    private func show(
        date: String = "1997-11-17",
        audioStatus: String = "complete",
        tracks: [Track]? = nil,
        albumCoverUrl: String? = "https://phish.in/cover.jpg"
    ) -> Show {
        Show(
            date: date, venueName: "McNichols Arena", tourName: "1997 Fall Tour",
            audioStatus: audioStatus, albumCoverUrl: albumCoverUrl,
            venue: Venue(name: "McNichols Arena", location: "Denver, CO"),
            tracks: tracks ?? [track()]
        )
    }

    // ---------------------------------------------------------------- backend

    func testBackendIdsRoundTrip() {
        for b in Backend.allCases {
            XCTAssertEqual(b, Backend.from(b.rawValue))
        }
        XCTAssertNil(Backend.from("archive"))
        XCTAssertNil(Backend.from(""))
    }

    // ---------------------------------------------------------------- periods

    func testAPhishInPeriodKeepsItsOwnStringAsItsId() {
        // "1983-1987" is not a year, and showsForPeriod needs it back verbatim to pick
        // year_range= over year= (D11).
        let ref = Period(period: "1983-1987", showsWithAudioCount: 12).toPeriodRef()
        XCTAssertEqual("1983-1987", ref.id)
        XCTAssertEqual("1983-1987", ref.label)
        XCTAssertEqual(12, ref.showCount)
    }

    func testAPeriodTakesItsArtFromTheCoverArtUrls() {
        let ref = Period(period: "1997", coverArtUrls: CoverArt(medium: "m.jpg")).toPeriodRef()
        XCTAssertEqual("m.jpg", ref.artURL)
    }

    // ------------------------------------------------------------------ shows

    func testAShowMapsToASummaryWithItsVenueAndLocationSplit() {
        let s = show().toShowSummary()
        XCTAssertEqual(PHISH, s.artist)
        XCTAssertEqual("1997-11-17", s.date)
        XCTAssertEqual("McNichols Arena", s.venue)
        XCTAssertEqual("Denver, CO", s.location)
        XCTAssertEqual("1997 Fall Tour", s.tourName)
        XCTAssertEqual("McNichols Arena · Denver, CO", s.where_)
    }

    func testOnlyAPartialShowIsFlaggedPartial() {
        XCTAssertTrue(show(audioStatus: "partial").toShowSummary().partial)
        XCTAssertFalse(show(audioStatus: "complete").toShowSummary().partial)
    }

    func testPhishInHasExactlyOneRecordingPerShow() {
        // The concept that makes Relisten different. phish.in has one audio per date, so
        // there is no tape to choose and nothing to switch between.
        let detail = show().toShowDetail()
        XCTAssertEqual(1, detail.summary.recordingCount)
        XCTAssertNil(detail.recording)
        XCTAssertTrue(detail.alternates.isEmpty)
    }

    // ----------------------------------------------------------------- tracks

    func testPhishInDurationsAreAlreadyMillisecondsAndAreNotConverted() {
        // Relisten's are seconds; this is the pair that makes the conversion easy to get
        // backwards, so both halves are pinned.
        XCTAssertEqual(1_200_000, track(duration: 1_200_000).toPlayableTrack(showArt: nil).durationMs)
    }

    func testUnplayableTracksAreDropped() {
        // D12: the queue index refers to the filtered list, so the UI and the queue builder
        // have to filter identically. Mapping here is what makes that automatic.
        let detail = show(tracks: [
            track(id: 1, title: "Tweezer"),
            track(id: 2, title: "Missing", mp3Url: nil),
            track(id: 3, title: "Also missing", audioStatus: "missing"),
            track(id: 4, title: "Reprise"),
        ]).toShowDetail()
        XCTAssertEqual(["Tweezer", "Reprise"], detail.tracks.map { $0.title })
    }

    func testATrackFallsBackToTheShowArtWhenItHasNoneOfItsOwn() {
        XCTAssertEqual("show.jpg", track(showAlbumCoverUrl: nil).toPlayableTrack(showArt: "show.jpg").artURL)
        XCTAssertEqual("own.jpg", track(showAlbumCoverUrl: "own.jpg").toPlayableTrack(showArt: "show.jpg").artURL)
    }

    func testATrackKeepsTheShowItWasPlayedAt() {
        // The scrobbled album is built out of these two (D50).
        let t = track().toPlayableTrack(showArt: nil)
        XCTAssertEqual("1997-11-17", t.showDate)
        XCTAssertEqual("McNichols Arena", t.venueName)
        XCTAssertEqual("https://phish.in/w.png", t.waveformURL)
    }

    // -------------------------------------------------------------- queue key

    func testAPhishInShowDetailKeysItselfExactlyAsBefore() {
        // The existing key, unchanged — this is what keeps the queue-key grammar identical to
        // Android's, so a row written by one client would still match on the other.
        XCTAssertEqual("show:1997-11-17", show().toShowDetail().queueKey)
    }

    func testARelistenShowDetailKeysItselfByArtistDateAndTape() {
        let artist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
        let detail = ShowDetail(
            summary: ShowSummary(artist: artist, date: "1977-05-08"),
            recording: RecordingRef(id: "src-uuid", label: "SBD")
        )
        XCTAssertEqual("relisten:grateful-dead/1977-05-08/src-uuid", detail.queueKey)
    }

    func testARelistenShowWithNoTapeHasNoKeyRatherThanABrokenOne() {
        // Better to record nothing than to write a key that parses back to nothing —
        // the same call shuffle makes on Android (D42).
        let artist = ArtistRef(backend: .relisten, id: "wsp", name: "Widespread Panic")
        let detail = ShowDetail(summary: ShowSummary(artist: artist, date: "2001-04-22"))
        XCTAssertNil(detail.queueKey)
    }

    // -------------------------------------------------------------- source picker

    func testLooksLikeMatrixMatchesInTaper() {
        XCTAssertTrue(RecordingRef(id: "1", label: "x", taper: "SBD/AUD Matrix").looksLikeMatrix)
    }

    func testLooksLikeMatrixMatchesInLineage() {
        XCTAssertTrue(RecordingRef(id: "1", label: "x", lineage: "DAT > Matrix > FLAC").looksLikeMatrix)
    }

    func testLooksLikeMatrixIsCaseInsensitive() {
        XCTAssertTrue(RecordingRef(id: "1", label: "x", taper: "MATRIX mix").looksLikeMatrix)
    }

    func testLooksLikeMatrixIsFalseWithNoMatchingText() {
        XCTAssertFalse(RecordingRef(id: "1", label: "x", taper: "Jim Wise", lineage: "DAT > FLAC").looksLikeMatrix)
        XCTAssertFalse(RecordingRef(id: "1", label: "x").looksLikeMatrix)
    }
}
