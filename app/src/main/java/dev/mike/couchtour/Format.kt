package dev.mike.couchtour

/** Formats a millisecond duration as m:ss, or h:mm:ss once it passes an hour. */
fun fmt(ms: Long): String {
    val total = (if (ms < 0) 0 else ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"

/**
 * A short relative-time label ("just now", "5m ago", "3h ago", "2d ago") for last-synced and
 * last-played timestamps. Falls back to an absolute "MMM d" once it's more than a week old,
 * since "47d ago" stops being useful at a glance.
 */
fun relativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0) return "never"
    val diffSec = ((nowMs - epochMs) / 1000).coerceAtLeast(0)
    return when {
        diffSec < 60 -> "just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        diffSec < 7 * 86400 -> "${diffSec / 86400}d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
    }
}

/**
 * Maps a horizontal touch on a scrubber of [widthPx] to a position in the track.
 * Clamped, so a drag past either edge lands on the start or the end rather than
 * seeking out of bounds.
 */
fun positionAt(x: Float, widthPx: Int, durationMs: Long): Long {
    if (widthPx <= 0 || durationMs <= 0) return 0
    return (x / widthPx.toFloat() * durationMs).toLong().coerceIn(0L, durationMs)
}
