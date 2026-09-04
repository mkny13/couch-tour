import Foundation

// Port of Format.kt.

/// Formats a millisecond duration as m:ss, or h:mm:ss once it passes an hour.
public func fmt(_ ms: Int64) -> String {
    let total = (ms < 0 ? 0 : ms) / 1000
    let h = total / 3600
    let m = (total % 3600) / 60
    let s = total % 60
    if h > 0 {
        return String(format: "%d:%02d:%02d", h, m, s)
    }
    return String(format: "%d:%02d", m, s)
}

public func plural(_ n: Int, _ word: String) -> String { n == 1 ? word : "\(word)s" }

/// A short relative-time label ("just now", "5m ago", "3h ago", "2d ago") for last-synced and
/// last-played timestamps. Falls back to an absolute "MMM d" once it's more than a week old,
/// since "47d ago" stops being useful at a glance.
public func relativeTime(_ epochMs: Int64, nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
    guard epochMs > 0 else { return "never" }
    let diffSec = max((nowMs - epochMs) / 1000, 0)
    switch diffSec {
    case ..<60: return "just now"
    case ..<3600: return "\(diffSec / 60)m ago"
    case ..<86400: return "\(diffSec / 3600)h ago"
    case ..<(7 * 86400): return "\(diffSec / 86400)d ago"
    default:
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d"
        return formatter.string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000))
    }
}

/// Maps a horizontal position on a scrubber of `widthPx` to a position in the track. Clamped,
/// so a drag past either edge lands on the start or the end rather than seeking out of bounds.
public func positionAt(x: Double, widthPx: Int, durationMs: Int64) -> Int64 {
    guard widthPx > 0, durationMs > 0 else { return 0 }
    let raw = Int64(x / Double(widthPx) * Double(durationMs))
    return min(max(raw, 0), durationMs)
}

/// Returns a progress fraction clamped between 0.0 and 1.0.
public func progressFraction(positionMs: Int64, durationMs: Int64) -> Double {
    guard durationMs > 0 else { return 0.0 }
    let fraction = Double(positionMs) / Double(durationMs)
    return min(max(fraction, 0.0), 1.0)
}

/// Formats a duration compactly: m:ss for sub-hour (e.g. 1:06), h:mm for hour+ (e.g. 2:41).
public func formatCompactDuration(ms: Int64) -> String {
    let totalSec = max(ms, 0) / 1000
    let hours = totalSec / 3600
    let minutes = (totalSec % 3600) / 60
    let seconds = totalSec % 60
    if hours > 0 {
        return String(format: "%d:%02d", hours, minutes)
    }
    return String(format: "%d:%02d", minutes, seconds)
}

/// Formats remaining track time with a "left" suffix, e.g. "7:32 left".
public func formatRemainingTime(positionMs: Int64, durationMs: Int64) -> String {
    guard durationMs > 0 else { return "0:00 left" }
    let remainingMs = max(durationMs - positionMs, 0)
    return "\(fmt(remainingMs)) left"
}

/// Formats a raw show date string to strict YYYY-MM-DD format (uat-006).
public func formatShowDate(_ raw: String) -> String {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    // Handle YYYY-MM-DD or YYYY/MM/DD
    let parts = trimmed.components(separatedBy: CharacterSet(charactersIn: "-/"))
    if parts.count == 3,
       let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
       y > 1900 && y < 2100 {
        return String(format: "%04d-%02d-%02d", y, m, d)
    }

    // Try standard date formats
    let formats = ["MMMM d, yyyy", "MMM d, yyyy", "yyyy-MM-dd", "yyyy/MM/dd"]
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    for fmtStr in formats {
        formatter.dateFormat = fmtStr
        if let date = formatter.date(from: trimmed) {
            formatter.dateFormat = "yyyy-MM-dd"
            return formatter.string(from: date)
        }
    }
    return trimmed
}
