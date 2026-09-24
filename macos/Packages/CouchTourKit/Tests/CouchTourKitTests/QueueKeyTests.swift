import XCTest
@testable import CouchTourKit

final class QueueKeyTests: XCTestCase {

    func testBuildsNamespacedKeys() {
        XCTAssertEqual("show:1997-02-13", showQueueKey("1997-02-13"))
        XCTAssertEqual("playlist:phishnet-key-jams-pt-1", playlistQueueKey("phishnet-key-jams-pt-1"))
    }

    func testRoundTripsAShowKey() {
        let ref = parseQueueKey(showQueueKey("1997-02-13"))
        XCTAssertEqual(QueueRef(kind: .show, id: "1997-02-13"), ref)
        XCTAssertEqual("show:1997-02-13", ref!.key)
    }

    func testRoundTripsAPlaylistKey() {
        let ref = parseQueueKey(playlistQueueKey("some-slug"))
        XCTAssertEqual(QueueRef(kind: .playlist, id: "some-slug"), ref)
        XCTAssertEqual("playlist:some-slug", ref!.key)
    }

    func testRejectsUnknownOrMalformedKeysInsteadOfGuessing() {
        // Playing an unrecognised key as the wrong kind would fetch the wrong thing.
        XCTAssertNil(parseQueueKey(""))
        XCTAssertNil(parseQueueKey("1997-02-13"))
        XCTAssertNil(parseQueueKey("album:1997-02-13"))
        XCTAssertNil(parseQueueKey("show:"))
        XCTAssertNil(parseQueueKey("playlist:"))
    }

    func testKeepsColonsInsideAPlaylistSlug() {
        let ref = parseQueueKey("playlist:odd:slug")
        XCTAssertEqual(QueueRef(kind: .playlist, id: "odd:slug"), ref)
    }

    func testDoesNotConfuseAShowSlugThatStartsWithTheOtherPrefix() {
        // A playlist literally called "show:..." must still parse as a playlist.
        XCTAssertEqual(
            QueueRef(kind: .playlist, id: "show:1997-02-13"),
            parseQueueKey("playlist:show:1997-02-13")
        )
    }

    // ------------------------------------------------------------- recordings

    func testBuildsARecordingKeyFromItsThreeParts() {
        XCTAssertEqual(
            "relisten:grateful-dead/1977-05-08/2ab1c5f0-9b1e-4f7a-8c3d-1e2f3a4b5c6d",
            recordingQueueKey("grateful-dead", "1977-05-08", "2ab1c5f0-9b1e-4f7a-8c3d-1e2f3a4b5c6d")
        )
    }

    func testRoundTripsARecordingKey() {
        let key = recordingQueueKey("wsp", "2001-04-22", "src-uuid")
        let ref = parseQueueKey(key)
        XCTAssertEqual(QueueRef(kind: .recording, id: "wsp/2001-04-22/src-uuid"), ref)
        XCTAssertEqual(key, ref!.key)
        XCTAssertEqual(RecordingId(artistSlug: "wsp", date: "2001-04-22", sourceId: "src-uuid"), parseRecordingId(ref!.id))
    }

    func testRejectsARecordingKeyThatIsMissingParts() {
        // Two tapes of one show have different track boundaries, so a key without its source
        // would resume a stored index against the wrong track list.
        XCTAssertNil(parseQueueKey("relisten:"))
        XCTAssertNil(parseQueueKey("relisten:wsp"))
        XCTAssertNil(parseQueueKey("relisten:wsp/2001-04-22"))
        XCTAssertNil(parseQueueKey("relisten:wsp/2001-04-22/src/extra"))
        XCTAssertNil(parseQueueKey("relisten:wsp//src-uuid"))
        XCTAssertNil(parseQueueKey("relisten://2001-04-22/src-uuid"))
    }

    func testParsesARecordingIdIndependentlyOfItsPrefix() {
        XCTAssertEqual(RecordingId(artistSlug: "phish", date: "1997-11-17", sourceId: "u"), parseRecordingId("phish/1997-11-17/u"))
        XCTAssertNil(parseRecordingId(""))
        XCTAssertNil(parseRecordingId("phish/1997-11-17"))
    }

    func testRecordingShowKeyBuildsTwoPartKeyAndParseQueueKeyRejectsIt() {
        // recordingShowKey produces a two-part key ("relisten:artistSlug/date") for show-level matching.
        let showKey = recordingShowKey("grateful-dead", "1977-05-08")
        XCTAssertEqual("relisten:grateful-dead/1977-05-08", showKey)

        // parseQueueKey must reject two-part show keys: a recording queue cannot be resumed
        // without a specific source/tape id.
        XCTAssertNil(parseQueueKey(showKey), "parseQueueKey must reject two-part recording show keys")
    }

    func testRejectsMalformedRecordingIds() {
        // Leading or trailing slashes, empty components, or wrong part counts must fail parseRecordingId.
        XCTAssertNil(parseRecordingId("/artist/1977-05-08/src"))
        XCTAssertNil(parseRecordingId("artist/1977-05-08/src/"))
        XCTAssertNil(parseRecordingId("artist//src"))
        XCTAssertNil(parseRecordingId("artist/1977-05-08/src/extra"))
        XCTAssertNil(parseRecordingId("/"))
        XCTAssertNil(parseRecordingId("//"))
    }

    func testAColonInsideARecordingPartIsNotADelimiter() {
        // Recording parts are split on "/" precisely so the first-colon-only rule that show
        // and playlist keys live under never applies here.
        XCTAssertEqual(
            QueueRef(kind: .recording, id: "odd:slug/1977-05-08/src"),
            parseQueueKey("relisten:odd:slug/1977-05-08/src")
        )
    }

    func testRecordingKeysDoNotCollideWithThePhishInPrefixes() {
        // The whole reason no migration is needed: an existing "show:" row is untouched.
        XCTAssertEqual(QueueKind.show, parseQueueKey("show:1997-02-13")!.kind)
        XCTAssertEqual(QueueKind.playlist, parseQueueKey("playlist:relisten:x")!.kind)
        XCTAssertEqual(QueueKind.recording, parseQueueKey("relisten:a/b/c")!.kind)
    }

    // ------------------------------------------------------------- local playlists (#59)

    func testBuildsALocalPlaylistKey() {
        XCTAssertEqual("local-playlist:abc-123", localPlaylistQueueKey("abc-123"))
    }

    func testRoundTripsALocalPlaylistKey() {
        let ref = parseQueueKey(localPlaylistQueueKey("abc-123"))
        XCTAssertEqual(QueueRef(kind: .localPlaylist, id: "abc-123"), ref)
        XCTAssertEqual("local-playlist:abc-123", ref!.key)
    }

    func testALocalPlaylistKeyDoesNotCollideWithAServerPlaylistKey() {
        // "local-playlist:" doesn't start with "playlist:", so a local id can never
        // round-trip through PhishInAPI.playlist(id) — see Catalog.swift's QueueKind doc.
        XCTAssertEqual(QueueKind.localPlaylist, parseQueueKey("local-playlist:x")!.kind)
        XCTAssertNil(parseQueueKey("local-playlist:"))
    }

    // ------------------------------------------------------------- youtube (#231, D256)

    func testBuildsAYouTubeKey() {
        XCTAssertEqual("youtube:dQw4w9WgXcQ", youtubeQueueKey("dQw4w9WgXcQ"))
    }

    func testRoundTripsAYouTubeKey() {
        let ref = parseQueueKey(youtubeQueueKey("dQw4w9WgXcQ"))
        XCTAssertEqual(QueueRef(kind: .youtube, id: "dQw4w9WgXcQ"), ref)
        XCTAssertEqual("youtube:dQw4w9WgXcQ", ref!.key)
    }

    func testRejectsAnEmptyYouTubeKey() {
        XCTAssertNil(parseQueueKey("youtube:"))
    }

    func testYouTubeKeysDoNotCollideWithOtherPrefixes() {
        // An existing "show:" row must still be a show, not suddenly parse as youtube —
        // and a playlist slug that happens to start with "youtube" stays a playlist.
        XCTAssertEqual(QueueKind.show, parseQueueKey("show:1997-02-13")!.kind)
        XCTAssertEqual(QueueKind.youtube, parseQueueKey("youtube:dQw4w9WgXcQ")!.kind)
        XCTAssertEqual(QueueKind.playlist, parseQueueKey("playlist:youtube:x")!.kind)
        XCTAssertNil(parseQueueKey("youtube:"))
        XCTAssertNil(parseQueueKey("notyoutube:x"))
    }
}
