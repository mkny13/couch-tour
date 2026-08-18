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

    /// Hand-written rather than synthesized so nil optionals are sent as explicit `null`
    /// instead of being dropped: Swift's synthesized `encode(to:)` uses `encodeIfPresent`
    /// for Optionals, which omits the key entirely. The server treats a missing key and an
    /// explicit null the same way now, but sending the documented shape keeps the wire format
    /// identical to Android's and to `ProgressFields` in sync/src/types.ts.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(queueKey, forKey: .queueKey)
        try c.encode(title, forKey: .title)
        try c.encode(subtitle, forKey: .subtitle)
        try c.encode(artUrl, forKey: .artUrl)
        try c.encode(trackIndex, forKey: .trackIndex)
        try c.encode(positionMs, forKey: .positionMs)
        try c.encode(trackTitle, forKey: .trackTitle)
        try c.encode(updatedAt, forKey: .updatedAt)
        try c.encode(finished, forKey: .finished)
        try c.encode(dismissed, forKey: .dismissed)
        try c.encode(artist, forKey: .artist)
        try c.encode(deletedAt, forKey: .deletedAt)
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

public struct SyncException: Error, LocalizedError {
    public let message: String
    public let code: Int

    public init(_ message: String, code: Int = 0) {
        self.message = message
        self.code = code
    }

    // Without this, `error.localizedDescription` falls back to Swift's generic bridged-NSError
    // text ("The operation couldn't be completed. (CouchTourKit.SyncException error 1.)") for
    // every failure alike — found live, catching a plain "incorrect code" 401 that looked
    // identical to a network failure until this was added.
    public var errorDescription: String? { message }

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
        static let lastSyncedAt = "sync.lastSyncedAt"
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

    /// Wall-clock time of the last successful sync round trip, for the "Last synced" UI. 0
    /// means never.
    public var lastSyncedAt: Int64 {
        get { Int64(defaults.integer(forKey: Key.lastSyncedAt)) }
        set { defaults.set(Int(newValue), forKey: Key.lastSyncedAt) }
    }

    public func clear() {
        keychain.set(nil, forKey: Key.deviceToken)
        keychain.set(nil, forKey: Key.deviceId)
        defaults.removeObject(forKey: Key.lastSeq)
        defaults.removeObject(forKey: Key.lastPushWatermark)
        defaults.removeObject(forKey: Key.lastSyncedAt)
    }
}

/// Pairing and the push/pull sync cycle. A device with no stored token is simply unpaired —
/// `sync` is then a no-op, not an error, so it's always safe to call from a launch hook or a
/// timer without checking `paired` first.
public final class SyncSession: ObservableObject {
    private let store: SyncTokenStore

    @Published public private(set) var paired: Bool
    @Published public private(set) var isSyncing = false
    /// `nil` means never synced.
    @Published public private(set) var lastSyncedAt: Int64?

    public init(store: SyncTokenStore = SyncTokenStore()) {
        self.store = store
        self.paired = store.deviceToken != nil
        self.lastSyncedAt = store.lastSyncedAt > 0 ? store.lastSyncedAt : nil
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
        lastSyncedAt = nil
    }

    /// Push-then-pull until the local backlog is drained. Pushes every local row touched since
    /// the last successful push (tombstones included — see `ProgressStore.changedSince`),
    /// applies whatever the server sends back, and advances both cursors only on success.
    ///
    /// Usually one round trip: only a first pair (watermark 0, so the whole progress table is
    /// "changed") has enough backlog to need more than one.
    public func sync(_ progressStore: ProgressStore) async throws {
        guard let token = store.deviceToken else { return }

        isSyncing = true
        defer { isSyncing = false }
        do {
            while try await syncOnce(token: token, progressStore) {}
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

    /// One push-then-pull round trip. Returns true when the push hit `maxPushBatch` and more
    /// local rows are still waiting, so `sync` knows to come back for them.
    private func syncOnce(token: String, _ progressStore: ProgressStore) async throws -> Bool {
        let pending = try progressStore.changedSince(store.lastPushWatermark)
            .sorted { $0.updatedAt < $1.updatedAt }
        let chunk = Self.chunkToPush(pending)
        let toPush = chunk.map { $0.toWire() }

        let (response, rotatedToken) = try await SyncAPI.sync(token: token, since: store.lastSeq, changes: toPush)
        if let rotatedToken { store.deviceToken = rotatedToken }

        for change in response.changes { try progressStore.put(change.toEntity()) }
        store.lastSeq = response.seq
        if let maxUpdatedAt = toPush.map({ $0.updatedAt }).max() {
            store.lastPushWatermark = maxUpdatedAt
        }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        store.lastSyncedAt = now
        lastSyncedAt = now

        return pending.count > chunk.count
    }

    /// How many rows one push may carry. Deliberately under the server's own 500-entry cap
    /// (`MAX_CHANGES_PER_SYNC` in sync/src/index.ts), which exists because D1 allows only 100
    /// bound parameters per query — before both limits, a first pair with 100+ rows of history
    /// 500'd the endpoint outright. Mirrors Android's `MAX_PUSH_BATCH`.
    static let maxPushBatch = 400

    /// The next batch to push, from rows already sorted by `updatedAt` ascending.
    ///
    /// Never splits a run of rows sharing one `updatedAt`: the watermark advances to the
    /// batch's highest `updatedAt`, and `ProgressStore.changedSince` is strictly `>`, so any
    /// leftover row with that same millisecond would never be offered again — a silent lost
    /// write in the one table this app exists to never lose. Trimming back to the run boundary
    /// keeps them for the next batch; a single millisecond holding more rows than the batch
    /// size is sent whole rather than stalling forever, which the gap to the server's cap
    /// leaves room for. Mirrors Android's `chunkToPush`.
    static func chunkToPush(_ pending: [PlaybackProgress]) -> [PlaybackProgress] {
        guard pending.count > maxPushBatch else { return pending }
        let capped = pending.prefix(maxPushBatch)
        guard let lastAt = capped.last?.updatedAt else { return Array(capped) }
        // Only trim when the run actually continues past the cut; otherwise the boundary
        // already falls between two distinct timestamps and the full batch is safe to send.
        guard pending[maxPushBatch].updatedAt == lastAt else { return Array(capped) }
        let trimmed = capped.prefix { $0.updatedAt != lastAt }
        return trimmed.isEmpty ? Array(pending.prefix { $0.updatedAt == lastAt }) : Array(trimmed)
    }

    private var pushTask: Task<Void, Never>?

    /// Debounced push after a play/pause/track-change event, so a phone-to-Mac handoff
    /// mid-listen doesn't have to wait for the next launch/foreground/15-minute timer.
    /// Coalesces bursts — `Player`'s observers fire more than once per real event — into a
    /// single push. `delay` is overridable so tests don't have to wait out the real debounce
    /// window.
    public func requestDebouncedPush(_ progressStore: ProgressStore, delay: Duration = .seconds(2)) {
        pushTask?.cancel()
        pushTask = Task {
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            try? await sync(progressStore)
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
