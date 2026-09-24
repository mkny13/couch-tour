// Port of Queue.kt. This is the sync contract between the Android and macOS clients: the
// strings produced here must be byte-identical to the Kotlin originals, because they are the
// primary key of the `progress` table both clients will eventually share.

public enum QueueKind: Equatable {
    case show
    case playlist
    case recording
    /// A device-local playlist (#59) — namespaced separately from `.playlist` (a phish.in
    /// server playlist slug) specifically so a local id never round-trips through
    /// `PhishInApi.playlist(id)`, matching Android's `Queue.kt` (D161 in DECISIONS.md).
    case localPlaylist
    /// A YouTube video played through the official IFrame embed (D256, #231). The id is
    /// the YouTube video id, not a show date — an Android client that does not yet
    /// recognize the prefix skips the row (returns nil) rather than mis-playing it, which
    /// is what makes this safe for the eventual shared `progress` table.
    case youtube
}

/// Playback progress is stored under a namespaced key so every kind of queue can share one
/// table: "show:1997-02-13", "playlist:some-slug", "relisten:grateful-dead/1977-05-08/<uuid>".
///
/// The namespacing is why adding a second backend needed no migration on Android — a Relisten
/// key cannot collide with a phish.in one, so existing rows keep working untouched.
public struct QueueRef: Equatable {
    public let kind: QueueKind
    public let id: String

    public init(kind: QueueKind, id: String) {
        self.kind = kind
        self.id = id
    }

    public var key: String {
        switch kind {
        case .show: return showQueueKey(id)
        case .playlist: return playlistQueueKey(id)
        case .recording: return recordingPrefix + id
        case .localPlaylist: return localPlaylistQueueKey(id)
        case .youtube: return youtubeQueueKey(id)
        }
    }
}

/// The three things needed to fetch a Relisten queue back.
///
/// The source matters as much as the date: Relisten carries around nine tapes of an average
/// Grateful Dead show, and two tapes of one date split the music into different tracks. A key
/// without its source would resume a stored index against the wrong track list.
public struct RecordingId: Equatable, Sendable {
    public let artistSlug: String
    public let date: String
    public let sourceId: String

    public init(artistSlug: String, date: String, sourceId: String) {
        self.artistSlug = artistSlug
        self.date = date
        self.sourceId = sourceId
    }

    public var id: String { "\(artistSlug)/\(date)/\(sourceId)" }
}

public func showQueueKey(_ date: String) -> String { "show:\(date)" }

public func playlistQueueKey(_ slug: String) -> String { "playlist:\(slug)" }

public func localPlaylistQueueKey(_ id: String) -> String { localPlaylistPrefix + id }

public func recordingQueueKey(_ artistSlug: String, _ date: String, _ sourceId: String) -> String {
    recordingPrefix + RecordingId(artistSlug: artistSlug, date: date, sourceId: sourceId).id
}

/// A recording key with its tape id dropped — the show it's a tape of. Every tape of the
/// same night shares this, which is what "have I played this show?" actually asks (#22).
public func recordingShowKey(_ artistSlug: String, _ date: String) -> String {
    recordingPrefix + "\(artistSlug)/\(date)"
}

public func youtubeQueueKey(_ videoId: String) -> String { youtubePrefix + videoId }

/// Splits a stored key back into its parts. Returns nil for anything unrecognised rather than
/// guessing — an unknown key should be skipped, not played as the wrong thing.
public func parseQueueKey(_ raw: String) -> QueueRef? {
    if raw.hasPrefix(localPlaylistPrefix) {
        let rest = String(raw.dropFirst(localPlaylistPrefix.count))
        return rest.isEmpty ? nil : QueueRef(kind: .localPlaylist, id: rest)
    }
    if raw.hasPrefix(playlistPrefix) {
        let rest = String(raw.dropFirst(playlistPrefix.count))
        return rest.isEmpty ? nil : QueueRef(kind: .playlist, id: rest)
    }
    if raw.hasPrefix(showPrefix) {
        let rest = String(raw.dropFirst(showPrefix.count))
        return rest.isEmpty ? nil : QueueRef(kind: .show, id: rest)
    }
    // Checked before its own prefix list matters for future-proofing only: "youtube:" shares
    // no prefix with "playlist:"/"show:", but ordering youtube first keeps a hypothetical
    // future id containing a colon from ever re-parsing as another namespace.
    if raw.hasPrefix(youtubePrefix) {
        let rest = String(raw.dropFirst(youtubePrefix.count))
        return rest.isEmpty ? nil : QueueRef(kind: .youtube, id: rest)
    }
    // Validated on the way in, unlike the other two: a recording id that isn't all three
    // parts is unusable, and failing here beats failing at fetch time. Note that
    // `recordingShowKey` produces a two-part key ("relisten:artist/date"); rejecting it here
    // in `parseQueueKey` is intentional because a two-part key cannot identify a specific tape
    // to play/resume.
    if raw.hasPrefix(recordingPrefix) {
        let rest = String(raw.dropFirst(recordingPrefix.count))
        guard let recordingId = parseRecordingId(rest) else { return nil }
        return QueueRef(kind: .recording, id: recordingId.id)
    }
    return nil
}

/// Parts are split on "/" rather than ":" so the first-colon-only rule that show and playlist
/// keys live under — a playlist slug may contain a colon — never has to apply here. Relisten
/// slugs and UUIDs contain no slashes.
public func parseRecordingId(_ raw: String) -> RecordingId? {
    let parts = raw.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
    guard parts.count == 3, parts.allSatisfy({ !$0.isEmpty }) else { return nil }
    return RecordingId(artistSlug: parts[0], date: parts[1], sourceId: parts[2])
}

private let showPrefix = "show:"
private let playlistPrefix = "playlist:"
private let recordingPrefix = "relisten:"
private let localPlaylistPrefix = "local-playlist:"
private let youtubePrefix = "youtube:"
