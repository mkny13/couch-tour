import Foundation

/// Process-wide hook the client subsystems record into (D317). `log` stays nil until the app
/// installs one, so unit tests and previews never touch the real on-disk log unless they opt in.
public enum Diagnostics {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _log: DiagnosticsLog?

    public static var log: DiagnosticsLog? {
        get { lock.lock(); defer { lock.unlock() }; return _log }
        set { lock.lock(); defer { lock.unlock() }; _log = newValue }
    }

    public static func installDefault() {
        if log == nil { log = DiagnosticsLog() }
    }

    static func event(_ level: DiagnosticsLog.Level = .info, _ name: String, _ fields: [(String, String)] = []) {
        log?.log(level, name, fields)
    }

    static func mark(_ key: String, _ value: String) {
        log?.mark(key: key, value: value)
    }

    /// Path only — the query string can carry tokens or search terms, so it never reaches the log.
    static func path(of request: URLRequest) -> String {
        guard let url = request.url,
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return "?" }
        let p = comps.percentEncodedPath
        return p.isEmpty ? "/" : p
    }

    /// Short stable code for an error; never the raw description, which can embed URLs.
    static func code(for error: Error) -> String {
        if let e = error as? SyncException { return code(forStatus: e.code) ?? "server" }
        if let e = error as? APIException { return code(forStatus: e.code) ?? "network" }
        if error is URLError { return "network" }
        if error is DecodingError { return "decode" }
        return "unknown"
    }

    private static func code(forStatus status: Int) -> String? {
        switch status {
        case 401, 403: return "unauthorized"
        case 404: return "not_found"
        case 410: return "gone"
        case 429: return "rate_limited"
        case 500...599: return "server"
        case 0: return nil
        default: return "http_\(status)"
        }
    }

    // MARK: Playback

    public static func playbackStart(show: String?, trackIndex: Int?) {
        playback("playback.start", .info, show: show, trackIndex: trackIndex, extra: [])
    }

    public static func playbackStop(show: String?, trackIndex: Int?, positionMs: Int64) {
        playback("playback.stop", .info, show: show, trackIndex: trackIndex, extra: [("posMs", String(positionMs))])
    }

    /// `error` is reduced to domain and numeric code; its description can embed the stream URL.
    public static func playbackError(show: String?, trackIndex: Int?, error: Error?) {
        var extra: [(String, String)] = []
        if let ns = error as NSError? { extra = [("domain", ns.domain), ("errno", String(ns.code))] }
        playback("playback.error", .error, show: show, trackIndex: trackIndex, extra: extra)
    }

    private static func playback(_ name: String, _ level: DiagnosticsLog.Level, show: String?,
                                 trackIndex: Int?, extra: [(String, String)]) {
        var fields: [(String, String)] = []
        if let show { fields.append(("show", show)) }
        if let trackIndex { fields.append(("track", String(trackIndex))) }
        event(level, name, fields + extra)
        mark("Last playback", "\(ISO8601DateFormatter().string(from: Date())) \(name.dropFirst("playback.".count))")
    }

    /// Runs the request closure, logging api.call start/end/failed with the elapsed time.
    static func timedCall(_ request: URLRequest, _ op: () async throws -> (Data, URLResponse)) async throws -> (Data, URLResponse) {
        let path = path(of: request)
        event(.debug, "api.call", [("path", path), ("phase", "start")])
        let began = Date()
        func ms() -> String { String(Int(Date().timeIntervalSince(began) * 1000)) }
        do {
            let result = try await op()
            var fields = [("path", path), ("phase", "end"), ("ms", ms())]
            if let http = result.1 as? HTTPURLResponse { fields.append(("status", String(http.statusCode))) }
            event(.info, "api.call", fields)
            return result
        } catch {
            event(.warn, "api.call", [("path", path), ("phase", "failed"), ("ms", ms()),
                                      ("error", String(describing: type(of: error)))])
            throw error
        }
    }
}
