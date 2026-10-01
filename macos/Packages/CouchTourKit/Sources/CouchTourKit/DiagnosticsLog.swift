import Foundation

/// On-device rotating diagnostics log (D315, mirrors Android D292). Two files, 1 MiB each, so the
/// on-disk footprint is bounded at 2 MiB. Writes are queued off the caller's thread and never throw;
/// an I/O failure latches logging off for the rest of the process rather than surfacing to callers.
public final class DiagnosticsLog: @unchecked Sendable {
    public enum Level: String, Sendable { case debug = "DEBUG", info = "INFO", warn = "WARN", error = "ERROR" }

    public static let maxFileBytes: Int64 = 1_048_576
    public static let retentionDays = 7
    public static let truncatedLineCount = 2000
    public static let maxTailBytes = 256 * 1024

    private static let redactedKeywords = ["token", "secret", "password", "passwd", "auth",
                                           "credential", "cookie", "pairing", "code", "key"]

    public static func defaultDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("dev.mike.couchtour/diagnostics", isDirectory: true)
    }

    private let queue = DispatchQueue(label: "dev.mike.couchtour.diagnostics")
    private let directory: URL
    private let current: URL
    private let previous: URL
    private let now: @Sendable () -> Date
    private let fm = FileManager.default
    private let stamp = ISO8601DateFormatter()
    // Everything below is touched only on `queue`.
    private var disabled = false
    private var currentBytes: Int64 = 0
    private var marks: [String: String] = [:]

    public init(directory: URL = DiagnosticsLog.defaultDirectory(),
                now: @escaping @Sendable () -> Date = { Date() }) {
        self.directory = directory
        self.current = directory.appendingPathComponent("diagnostics.log")
        self.previous = directory.appendingPathComponent("diagnostics.log.1")
        self.now = now
        stamp.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        queue.sync {
            do {
                try fm.createDirectory(at: directory, withIntermediateDirectories: true)
                prune(current)
                prune(previous)
                currentBytes = size(of: current)
            } catch {
                disabled = true
            }
        }
    }

    // MARK: Redaction

    /// True when a key's value must not be written. Bare `key`/`code` are allowed (too common to be
    /// secrets); compounds like `syncKey` or `access_token` are not.
    static func isRedactedKey(_ key: String) -> Bool {
        let lower = key.lowercased()
        if lower == "key" || lower == "code" { return false }
        return words(in: key).contains { word in
            redactedKeywords.contains { kw in
                word == kw || word == kw + "s" || (kw.count >= 4 && (word.hasPrefix(kw) || word.hasSuffix(kw)))
            }
        }
    }

    /// Splits on non-alphanumerics and camelCase boundaries, lowercased.
    private static func words(in key: String) -> [String] {
        var out: [String] = []
        var word = ""
        let chars = Array(key)
        for (i, c) in chars.enumerated() {
            guard c.isLetter || c.isNumber else {
                if !word.isEmpty { out.append(word.lowercased()); word = "" }
                continue
            }
            if !word.isEmpty, c.isUppercase {
                let prev = chars[i - 1]
                let nextLower = i + 1 < chars.count && chars[i + 1].isLowercase
                if prev.isLowercase || prev.isNumber || (prev.isUppercase && nextLower) {
                    out.append(word.lowercased()); word = ""
                }
            }
            word.append(c)
        }
        if !word.isEmpty { out.append(word.lowercased()) }
        return out
    }

    private static func sanitize(_ value: String) -> String {
        value.replacingOccurrences(of: "\r\n", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\t", with: " ")
    }

    static func formatLine(timestamp: String, level: Level, event: String,
                           fields: [(String, String)]) -> String {
        var line = "\(timestamp)\t\(level.rawValue)\t\(sanitize(event))"
        if !fields.isEmpty {
            line += "\t" + fields.map { key, value in
                "\(sanitize(key))=\(isRedactedKey(key) ? "***" : sanitize(value))"
            }.joined(separator: " ")
        }
        return line + "\n"
    }

    // MARK: Writing

    /// Non-blocking, non-throwing.
    public func log(_ level: Level = .info, _ event: String, _ fields: [(String, String)] = []) {
        let date = now()
        queue.async { [self] in
            let line = Self.formatLine(timestamp: stamp.string(from: date), level: level,
                                       event: event, fields: fields)
            append(line, date: date)
        }
    }

    public func mark(key: String, value: String) {
        queue.async { [self] in
            marks[key] = Self.isRedactedKey(key) ? "***" : Self.sanitize(value)
        }
    }

    /// Blocks until queued writes have landed. For tests and for export paths.
    public func flush() { queue.sync {} }

    private func append(_ line: String, date: Date) {
        guard !disabled else { return }
        do {
            if currentBytes >= Self.maxFileBytes {
                try? fm.removeItem(at: previous)
                try fm.moveItem(at: current, to: previous)
                currentBytes = 0
                try write(Self.formatLine(timestamp: stamp.string(from: date), level: .info,
                                          event: "log.rotated", fields: []))
            }
            try write(line)
        } catch {
            disabled = true
        }
    }

    private func write(_ line: String) throws {
        let data = Data(line.utf8)
        if !fm.fileExists(atPath: current.path) {
            guard fm.createFile(atPath: current.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
            currentBytes = 0
        }
        let handle = try FileHandle(forWritingTo: current)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: data)
        currentBytes += Int64(data.count)
    }

    // MARK: Retention

    private func prune(_ url: URL) {
        guard fm.fileExists(atPath: url.path), let data = try? Data(contentsOf: url) else { return }
        let cutoff = now().addingTimeInterval(-Double(Self.retentionDays) * 86_400)
        var lines = String(decoding: data, as: UTF8.self).split(separator: "\n", omittingEmptySubsequences: true)
        lines = lines.filter { line in
            let ts = line.prefix { $0 != "\t" }
            guard let date = stamp.date(from: String(ts)) else { return true }
            return date >= cutoff
        }
        var kept = lines.joined(separator: "\n")
        if !kept.isEmpty { kept += "\n" }
        if Int64(kept.utf8.count) >= Self.maxFileBytes {
            kept = lines.suffix(Self.truncatedLineCount).joined(separator: "\n") + "\n"
        }
        if kept.isEmpty {
            try? fm.removeItem(at: url)
        } else if kept.utf8.count != data.count {
            try? Data(kept.utf8).write(to: url, options: .atomic)
        }
    }

    // MARK: Inspection

    private func size(of url: URL) -> Int64 {
        ((try? fm.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

    public func onDiskBytes() -> Int64 {
        queue.sync { size(of: current) + size(of: previous) }
    }

    private func lineCount(_ url: URL) -> Int {
        guard let data = try? Data(contentsOf: url) else { return 0 }
        return data.reduce(0) { $0 + ($1 == 0x0A ? 1 : 0) }
    }

    public func summaryLines() -> String {
        queue.sync {
            var out = marks.keys.sorted().map { "\($0): \(marks[$0] ?? "")" }
            out.append("Entries: \(lineCount(current) + lineCount(previous))")
            out.append("Size: \(size(of: current) + size(of: previous)) bytes")
            return out.joined(separator: "\n")
        }
    }

    /// Reads at most `maxBytes` from the end of the file, dropping a leading partial line.
    private func tailData(_ url: URL, maxBytes: Int) -> [Substring] {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return [] }
        defer { try? handle.close() }
        guard let end = try? handle.seekToEnd() else { return [] }
        let start = end > UInt64(maxBytes) ? end - UInt64(maxBytes) : 0
        guard (try? handle.seek(toOffset: start)) != nil,
              let data = try? handle.readToEnd() else { return [] }
        var lines = String(decoding: data, as: UTF8.self).split(separator: "\n", omittingEmptySubsequences: true)
        if start > 0, !lines.isEmpty { lines.removeFirst() }
        return lines
    }

    /// Last `n` lines in chronological order, reading at most 256 KiB in total.
    public func tailLines(_ n: Int) -> [String] {
        guard n > 0 else { return [] }
        return queue.sync {
            var lines = tailData(current, maxBytes: Self.maxTailBytes)
            if lines.count < n {
                let budget = max(0, Self.maxTailBytes - Int(min(size(of: current), Int64(Self.maxTailBytes))))
                if budget > 0 { lines = tailData(previous, maxBytes: budget) + lines }
            }
            return lines.suffix(n).map(String.init)
        }
    }

    public func exportText() -> String {
        queue.sync {
            [previous, current].compactMap { try? Data(contentsOf: $0) }
                .map { String(decoding: $0, as: UTF8.self) }.joined()
        }
    }

    public func clear() {
        queue.sync {
            try? fm.removeItem(at: current)
            try? fm.removeItem(at: previous)
            currentBytes = 0
            marks = [:]
        }
    }
}
