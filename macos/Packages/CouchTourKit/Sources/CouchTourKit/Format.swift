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
