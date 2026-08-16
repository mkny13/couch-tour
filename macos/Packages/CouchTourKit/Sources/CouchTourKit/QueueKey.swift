// Port of Queue.kt. This is the sync contract between the Android and macOS clients: the
// strings produced here must be byte-identical to the Kotlin originals, because they are the
// primary key of the `progress` table both clients will eventually share.

public enum QueueKind: Equatable {
    case show
    case playlist
    case recording
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
        }
    }
}

/// The three things needed to fetch a Relisten queue back.
///
/// The source matters as much as the date: Relisten carries around nine tapes of an average
/// Grateful Dead show, and two tapes of one date split the music into different tracks. A key
/// without its source would resume a stored index against the wrong track list.
public struct RecordingId: Equatable {
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

public func recordingQueueKey(_ artistSlug: String, _ date: String, _ sourceId: String) -> String {
    recordingPrefix + RecordingId(artistSlug: artistSlug, date: date, sourceId: sourceId).id
}

/// Splits a stored key back into its parts. Returns nil for anything unrecognised rather than
/// guessing — an unknown key should be skipped, not played as the wrong thing.
public func parseQueueKey(_ raw: String) -> QueueRef? {
    if raw.hasPrefix(playlistPrefix) {
        let rest = String(raw.dropFirst(playlistPrefix.count))
        return rest.isEmpty ? nil : QueueRef(kind: .playlist, id: rest)
    }
    if raw.hasPrefix(showPrefix) {
        let rest = String(raw.dropFirst(showPrefix.count))
        return rest.isEmpty ? nil : QueueRef(kind: .show, id: rest)
    }
    // Validated on the way in, unlike the other two: a recording id that isn't all three
    // parts is unusable, and failing here beats failing at fetch time.
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
