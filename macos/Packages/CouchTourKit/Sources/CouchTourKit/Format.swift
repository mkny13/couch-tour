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

/// Maps a horizontal position on a scrubber of `widthPx` to a position in the track. Clamped,
/// so a drag past either edge lands on the start or the end rather than seeking out of bounds.
public func positionAt(x: Double, widthPx: Int, durationMs: Int64) -> Int64 {
    guard widthPx > 0, durationMs > 0 else { return 0 }
    let raw = Int64(x / Double(widthPx) * Double(durationMs))
    return min(max(raw, 0), durationMs)
}
