import Foundation

/// Unique application ID for Google's standard Default Media Receiver.
public let defaultCastReceiverAppId = "CC1AD845"

/// Identifies an external Google Cast device discovered on the local network.
public struct CastDevice: Identifiable, Hashable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let modelName: String?
    public let host: String
    public let port: Int

    public init(id: String, name: String, modelName: String? = nil, host: String, port: Int = 8009) {
        self.id = id
        self.name = name
        self.modelName = modelName
        self.host = host
        self.port = port
    }
}

/// Custom data dictionary keys embedded in Cast `MediaInfo` items.
public enum CastKeys {
    public static let mediaId = "media_id"
    public static let uri = "uri"
    public static let artwork = "artwork"
    public static let queueKey = "queue_key"
    public static let mp3Url = "mp3_url"
    public static let flacUrl = "flac_url"
    public static let backend = "backend"
    public static let trackId = "track_id"
    public static let liked = "liked"
    public static let likesCount = "likes_count"
    public static let showDate = "show_date"
    public static let venueName = "venue_name"
}

/// Converts native `PlayableTrack` and `ShowSummary` into Cast Media structures.
public enum CastItemConverter {
    /// Formats a track and show summary into the JSON `MediaInfo` dictionary expected by
    /// the Cast Default Media Receiver.
    ///
    /// Following D187: if the track is FLAC (`flacUrl` or .flac extension), it falls back to
    /// `mp3Url` / `url` for the Cast receiver stream while preserving the lossless `flacUrl`
    /// in `customData` so it can be restored when playback returns to the local device.
    public static func toMediaInfo(
        track: PlayableTrack,
        show: ShowSummary?,
        queueKey: String?
    ) -> [String: Any] {
        let isFlac = (track.flacUrl?.isEmpty == false) || track.url.lowercased().hasSuffix(".flac")
        let streamUrl = isFlac ? track.url : (track.flacUrl?.isEmpty == false ? track.flacUrl! : track.url)
        let fallbackMp3Url = track.url

        var metadata: [String: Any] = [
            "metadataType": 3, // MUSIC_TRACK
            "title": track.title,
        ]

        if let show {
            metadata["artist"] = show.artist.name
            let album = [track.showDate ?? show.date, track.venueName ?? show.where_]
                .compactMap { $0?.isEmpty == false ? $0 : nil }
                .joined(separator: " · ")
            if !album.isEmpty {
                metadata["albumName"] = album
            }
            if !show.where_.isEmpty {
                metadata["subtitle"] = show.where_
            }
        }

        let artUrl = track.artURL ?? show?.artURL
        if let artUrl, !artUrl.isEmpty {
            metadata["images"] = [["url": artUrl]]
        }

        var customData: [String: Any] = [
            CastKeys.mediaId: track.id,
            CastKeys.uri: streamUrl,
            CastKeys.mp3Url: fallbackMp3Url,
        ]
        if let queueKey {
            customData[CastKeys.queueKey] = queueKey
        }
        if let flacUrl = track.flacUrl, !flacUrl.isEmpty {
            customData[CastKeys.flacUrl] = flacUrl
        }
        if let artUrl, !artUrl.isEmpty {
            customData[CastKeys.artwork] = artUrl
        }
        if let show {
            customData[CastKeys.backend] = show.artist.backend.rawValue
        }
        if let showDate = track.showDate {
            customData[CastKeys.showDate] = showDate
        }
        if let venueName = track.venueName {
            customData[CastKeys.venueName] = venueName
        }
        if track.likesCount > 0 {
            customData[CastKeys.likesCount] = track.likesCount
        }
        if track.likedByUser {
            customData[CastKeys.liked] = true
        }

        return [
            "contentId": streamUrl,
            "streamType": "BUFFERED",
            "contentType": "audio/mp3",
            "duration": Double(track.durationMs) / 1000.0,
            "metadata": metadata,
            "customData": customData
        ]
    }

    /// Converts a list of playable tracks and show summary into an array of Cast `MediaQueueItem` dictionaries.
    public static func toQueueItems(
        tracks: [PlayableTrack],
        show: ShowSummary?,
        queueKey: String?,
        startIndex: Int = 0
    ) -> [[String: Any]] {
        return tracks.enumerated().map { index, track in
            let mediaInfo = toMediaInfo(track: track, show: show, queueKey: queueKey)
            return [
                "itemId": index + 1,
                "media": mediaInfo,
                "autoplay": true
            ]
        }
    }
}

/// Standard Cast protocol namespaces.
public enum CastNamespace {
    public static let connection = "urn:x-cast:com.google.cast.tp.connection"
    public static let heartbeat = "urn:x-cast:com.google.cast.tp.heartbeat"
    public static let receiver = "urn:x-cast:com.google.cast.receiver"
    public static let media = "urn:x-cast:com.google.cast.media"
}

/// Parsed Media Status from a Cast Receiver.
public struct CastMediaStatus: Equatable, Sendable {
    public enum PlayerState: String, Sendable {
        case idle = "IDLE"
        case playing = "PLAYING"
        case paused = "PAUSED"
        case buffering = "BUFFERING"
        case unknown = "UNKNOWN"
    }

    public enum IdleReason: String, Sendable {
        case cancelled = "CANCELLED"
        case interrupted = "INTERRUPTED"
        case finished = "FINISHED"
        case error = "ERROR"
    }

    public let mediaSessionId: Int?
    public let playerState: PlayerState
    public let idleReason: IdleReason?
    public let currentTime: Double
    public let duration: Double?
    public let volumeLevel: Double?
    public let isMuted: Bool?
    public let currentItemId: Int?
    public let loadingItemId: Int?
    public let contentId: String?
    public let customData: [String: AnySendable]?

    public init(
        mediaSessionId: Int? = nil,
        playerState: PlayerState = .unknown,
        idleReason: IdleReason? = nil,
        currentTime: Double = 0,
        duration: Double? = nil,
        volumeLevel: Double? = nil,
        isMuted: Bool? = nil,
        currentItemId: Int? = nil,
        loadingItemId: Int? = nil,
        contentId: String? = nil,
        customData: [String: AnySendable]? = nil
    ) {
        self.mediaSessionId = mediaSessionId
        self.playerState = playerState
        self.idleReason = idleReason
        self.currentTime = currentTime
        self.duration = duration
        self.volumeLevel = volumeLevel
        self.isMuted = isMuted
        self.currentItemId = currentItemId
        self.loadingItemId = loadingItemId
        self.contentId = contentId
        self.customData = customData
    }
}

/// Sendable wrapper for heterogeneous dictionary values from JSON.
public struct AnySendable: Equatable, @unchecked Sendable {
    public let value: Any

    public init(_ value: Any) {
        self.value = value
    }

    public static func == (lhs: AnySendable, rhs: AnySendable) -> Bool {
        if let l = lhs.value as? String, let r = rhs.value as? String { return l == r }
        if let l = lhs.value as? Int, let r = rhs.value as? Int { return l == r }
        if let l = lhs.value as? Double, let r = rhs.value as? Double { return l == r }
        if let l = lhs.value as? Bool, let r = rhs.value as? Bool { return l == r }
        return false
    }
}
