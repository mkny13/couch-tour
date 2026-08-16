import Foundation

// Port of Sync.kt for the sync backend (sync/, D119-D127) at
// https://couch-tour-sync.mkastellec.workers.dev. Field names and shapes mirror
// sync/src/types.ts's ProgressFields exactly, same contract both clients and the Worker
// share.
//
// D103's Swift trap applies throughout: synthesized `Decodable` does NOT apply a property's
// default when a JSON key is simply absent, unlike kotlinx.serialization on the Android side.
// Every type below with an optional-looking field carries a hand-written `init(from decoder:)`.

public struct SyncProgressWire: Codable, Equatable {
    public let queueKey: String
    public let title: String
    public let subtitle: String
    public let artUrl: String?
    public let trackIndex: Int
    public let positionMs: Int64
    public let trackTitle: String
    public let updatedAt: Int64
    public let finished: Bool
    public let dismissed: Bool
    public let artist: String
    public let deletedAt: Int64?

    enum CodingKeys: String, CodingKey {
        case queueKey, title, subtitle, artUrl, trackIndex, positionMs, trackTitle
        case updatedAt, finished, dismissed, artist, deletedAt
    }

    public init(
        queueKey: String, title: String, subtitle: String, artUrl: String? = nil, trackIndex: Int,
        positionMs: Int64, trackTitle: String, updatedAt: Int64, finished: Bool, dismissed: Bool,
        artist: String, deletedAt: Int64? = nil
    ) {
        self.queueKey = queueKey
        self.title = title
        self.subtitle = subtitle
        self.artUrl = artUrl
        self.trackIndex = trackIndex
        self.positionMs = positionMs
        self.trackTitle = trackTitle
        self.updatedAt = updatedAt
        self.finished = finished
        self.dismissed = dismissed
        self.artist = artist
        self.deletedAt = deletedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        queueKey = try c.decode(String.self, forKey: .queueKey)
        title = try c.decode(String.self, forKey: .title)
        subtitle = try c.decode(String.self, forKey: .subtitle)
        artUrl = try c.decodeIfPresent(String.self, forKey: .artUrl)
        trackIndex = try c.decode(Int.self, forKey: .trackIndex)
        positionMs = try c.decode(Int64.self, forKey: .positionMs)
        trackTitle = try c.decode(String.self, forKey: .trackTitle)
        updatedAt = try c.decode(Int64.self, forKey: .updatedAt)
        finished = try c.decode(Bool.self, forKey: .finished)
        dismissed = try c.decode(Bool.self, forKey: .dismissed)
        artist = try c.decode(String.self, forKey: .artist)
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
    }
}

private struct PairStartRequest: Encodable {
    let deviceName: String
    let platform: String
}

public struct PairStartResponse: Decodable {
    public let code: String
    public let expiresAt: Int64
    public let deviceId: String?
    public let deviceToken: String?

    enum CodingKeys: String, CodingKey { case code, expiresAt, deviceId, deviceToken }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        code = try c.decode(String.self, forKey: .code)
        expiresAt = try c.decode(Int64.self, forKey: .expiresAt)
        deviceId = try c.decodeIfPresent(String.self, forKey: .deviceId)
        deviceToken = try c.decodeIfPresent(String.self, forKey: .deviceToken)
    }
}

/// Looked up server-side by the code alone (D127) — no separate pairing id, so the whole
/// thing is short enough for a human to type.
private struct PairClaimRequest: Encodable {
    let code: String
    let deviceName: String
    let platform: String
}

public struct PairClaimResponse: Decodable {
    public let deviceId: String
    public let deviceToken: String
}

private struct SyncRequest: Encodable {
    let since: Int64
    let changes: [SyncProgressWire]
}

public struct SyncResponse: Decodable {
    public let seq: Int64
    public let changes: [SyncProgressWire]
}

public struct DeviceInfo: Decodable, Identifiable {
    public let deviceId: String
    public let name: String
    public let platform: String
    public let createdAt: Int64
    public let lastSeenAt: Int64?
    public let isSelf: Bool

    public var id: String { deviceId }

    enum CodingKeys: String, CodingKey { case deviceId, name, platform, createdAt, lastSeenAt, isSelf }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try c.decode(String.self, forKey: .deviceId)
        name = try c.decode(String.self, forKey: .name)
        platform = try c.decode(String.self, forKey: .platform)
        createdAt = try c.decode(Int64.self, forKey: .createdAt)
        lastSeenAt = try c.decodeIfPresent(Int64.self, forKey: .lastSeenAt)
        isSelf = try c.decode(Bool.self, forKey: .isSelf)
    }
}

private struct DevicesResponse: Decodable {
    let devices: [DeviceInfo]
}

private struct ErrorResponse: Decodable {
    let error: String
}

public struct SyncException: Error {
    public let message: String
    public let code: Int

    public init(_ message: String, code: Int = 0) {
        self.message = message
        self.code = code
    }

    public var unauthorized: Bool { code == 401 }
    public var gone: Bool { code == 410 }
}

/// Client for the sync backend. Deliberately its own `URLSession` request layer and
/// `Authorization: Bearer` scheme, separate from `PhishInAPI`/`RelistenAPI` — an unrelated
/// service with an unrelated identity.
public enum SyncAPI {
    private static let defaultBase = URL(string: "https://couch-tour-sync.mkastellec.workers.dev")!

    /// Overridden by tests to point at a local mock server.
    public static var baseURL: URL = defaultBase

    private static let decoder = JSONDecoder()
    private static let encoder = JSONEncoder()

    private struct HTTPResult {
        let code: Int
        let data: Data
        let rotatedToken: String?
    }

    private static func path(_ segments: String...) -> URL {
        var url = baseURL
        for segment in segments { url.appendPathComponent(segment) }
        return url
    }

    private static func execute(_ request: URLRequest) async throws -> HTTPResult {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw SyncException("No HTTP response") }
        let rotated = http.value(forHTTPHeaderField: "X-Sync-Token-Rotated")
        return HTTPResult(code: http.statusCode, data: data, rotatedToken: rotated)
    }

    private static func request(_ url: URL, method: String, token: String?, body: Data? = nil) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        return request
    }

    private static func orThrow(_ result: HTTPResult) throws -> HTTPResult {
        guard (200...299).contains(result.code) else {
            let message = (try? decoder.decode(ErrorResponse.self, from: result.data))?.error
            throw SyncException(message?.isEmpty == false ? message! : "HTTP \(result.code)", code: result.code)
        }
        return result
    }

    public static func pairStart(deviceName: String, platform: String, existingToken: String?) async throws -> PairStartResponse {
        let body = try encoder.encode(PairStartRequest(deviceName: deviceName, platform: platform))
        let result = try orThrow(try await execute(request(path("pair", "start"), method: "POST", token: existingToken, body: body)))
        return try decoder.decode(PairStartResponse.self, from: result.data)
    }

    public static func pairClaim(code: String, deviceName: String, platform: String) async throws -> PairClaimResponse {
        let body = try encoder.encode(PairClaimRequest(code: code, deviceName: deviceName, platform: platform))
        let result = try orThrow(try await execute(request(path("pair", "claim"), method: "POST", token: nil, body: body)))
        return try decoder.decode(PairClaimResponse.self, from: result.data)
    }

    /// The rotated-token half is non-nil only when the server issued a fresh one.
    public static func sync(token: String, since: Int64, changes: [SyncProgressWire]) async throws -> (SyncResponse, String?) {
        let body = try encoder.encode(SyncRequest(since: since, changes: changes))
        let result = try orThrow(try await execute(request(path("sync"), method: "POST", token: token, body: body)))
        return (try decoder.decode(SyncResponse.self, from: result.data), result.rotatedToken)
    }

    public static func devices(token: String) async throws -> [DeviceInfo] {
        let result = try orThrow(try await execute(request(path("devices"), method: "GET", token: token)))
        return try decoder.decode(DevicesResponse.self, from: result.data).devices
    }

    public static func revokeDevice(token: String, deviceId: String) async throws {
        _ = try orThrow(try await execute(request(path("devices", deviceId), method: "DELETE", token: token)))
    }
}

/// Encrypted-token-adjacent storage for the sync device token: the token itself lives in
/// Keychain (see `KeychainStoring`); the sync cursors are plain, non-sensitive integers in
/// `UserDefaults`. Its own store, not shared with any phish.in credential — pairing and
/// signing out of phish.in are unrelated.
public final class SyncTokenStore {
    private let keychain: KeychainStoring
    private let defaults: UserDefaults

    private enum Key {
        static let deviceToken = "sync.deviceToken"
        static let deviceId = "sync.deviceId"
        static let lastSeq = "sync.lastSeq"
        static let lastPushWatermark = "sync.lastPushWatermark"
    }

    public init(keychain: KeychainStoring = SystemKeychain(), defaults: UserDefaults = .standard) {
        self.keychain = keychain
        self.defaults = defaults
    }

    public var deviceToken: String? {
        get { keychain.get(forKey: Key.deviceToken) }
        set { keychain.set(newValue, forKey: Key.deviceToken) }
    }

    public var deviceId: String? {
        get { keychain.get(forKey: Key.deviceId) }
        set { keychain.set(newValue, forKey: Key.deviceId) }
    }

    /// The pull cursor: the highest `seq` this device has already applied.
    public var lastSeq: Int64 {
        get { Int64(defaults.integer(forKey: Key.lastSeq)) }
        set { defaults.set(Int(newValue), forKey: Key.lastSeq) }
    }

    /// The push watermark: the highest local `updatedAt` already sent to the server.
    public var lastPushWatermark: Int64 {
        get { Int64(defaults.integer(forKey: Key.lastPushWatermark)) }
        set { defaults.set(Int(newValue), forKey: Key.lastPushWatermark) }
    }

    public func clear() {
        keychain.set(nil, forKey: Key.deviceToken)
        keychain.set(nil, forKey: Key.deviceId)
        defaults.removeObject(forKey: Key.lastSeq)
        defaults.removeObject(forKey: Key.lastPushWatermark)
    }
}

/// Pairing and the push/pull sync cycle. A device with no stored token is simply unpaired —
/// `sync` is then a no-op, not an error, so it's always safe to call from a launch hook or a
/// timer without checking `paired` first.
public final class SyncSession: ObservableObject {
    private let store: SyncTokenStore

    @Published public private(set) var paired: Bool

    public init(store: SyncTokenStore = SyncTokenStore()) {
        self.store = store
        self.paired = store.deviceToken != nil
    }

    /// Bootstraps a new group if unpaired, or mints a fresh code inside the existing group if
    /// already paired (adding a further device). Either way, returns the code to show.
    public func startPairing(deviceName: String, platform: String) async throws -> PairStartResponse {
        let response = try await SyncAPI.pairStart(deviceName: deviceName, platform: platform, existingToken: store.deviceToken)
        if let token = response.deviceToken, let deviceId = response.deviceId {
            store.deviceToken = token
            store.deviceId = deviceId
            paired = true
        }
        return response
    }

    /// Claims a code shown on another device, joining its group.
    public func claimPairing(code: String, deviceName: String, platform: String) async throws {
        let response = try await SyncAPI.pairClaim(code: code, deviceName: deviceName, platform: platform)
        store.deviceToken = response.deviceToken
        store.deviceId = response.deviceId
        store.lastSeq = 0
        store.lastPushWatermark = 0
        paired = true
    }

    public func devices() async throws -> [DeviceInfo] {
        guard let token = store.deviceToken else { return [] }
        return try await SyncAPI.devices(token: token)
    }

    /// Revokes any device in the group, including this one, from the settings screen.
    public func revoke(deviceId: String) async throws {
        guard let token = store.deviceToken else { return }
        try await SyncAPI.revokeDevice(token: token, deviceId: deviceId)
        if deviceId == store.deviceId { unlink() }
    }

    /// Wipes local pairing state without contacting the server — "unlink this device".
    public func unlink() {
        store.clear()
        paired = false
    }

    /// One push-then-pull cycle. Pushes every local row touched since the last successful
    /// push (tombstones included — see `ProgressStore.changedSince`), applies whatever the
    /// server sends back, and advances both cursors only on success.
    public func sync(_ progressStore: ProgressStore) async throws {
        guard let token = store.deviceToken else { return }
        let toPush = try progressStore.changedSince(store.lastPushWatermark).map { $0.toWire() }

        do {
            let (response, rotatedToken) = try await SyncAPI.sync(token: token, since: store.lastSeq, changes: toPush)
            if let rotatedToken { store.deviceToken = rotatedToken }

            for change in response.changes { try progressStore.put(change.toEntity()) }
            store.lastSeq = response.seq
            if let maxUpdatedAt = toPush.map({ $0.updatedAt }).max() {
                store.lastPushWatermark = maxUpdatedAt
            }
        } catch let error as SyncException {
            if error.unauthorized {
                // Revoked from another device (or the token is simply bad): stop trying
                // until the user re-pairs, rather than retrying a request that can't succeed.
                unlink()
            } else if error.gone {
                // Cursor predates the tombstone retention floor: start over from scratch.
                // since = 0 never 410s (D126), so this terminates in one extra round trip.
                store.lastSeq = 0
                try await sync(progressStore)
            } else {
                throw error
            }
        }
    }
}

private extension SyncProgressWire {
    func toEntity() -> PlaybackProgress {
        PlaybackProgress(
            queueKey: queueKey, title: title, subtitle: subtitle, artUrl: artUrl,
            trackIndex: trackIndex, positionMs: positionMs, trackTitle: trackTitle,
            updatedAt: updatedAt, finished: finished, dismissed: dismissed, artist: artist,
            deletedAt: deletedAt
        )
    }
}

private extension PlaybackProgress {
    func toWire() -> SyncProgressWire {
        SyncProgressWire(
            queueKey: queueKey, title: title, subtitle: subtitle, artUrl: artUrl,
            trackIndex: trackIndex, positionMs: positionMs, trackTitle: trackTitle,
            updatedAt: updatedAt, finished: finished, dismissed: dismissed, artist: artist,
            deletedAt: deletedAt
        )
    }
}
