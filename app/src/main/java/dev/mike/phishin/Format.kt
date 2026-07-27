package dev.mike.phishin

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
 * Maps a horizontal touch on a scrubber of [widthPx] to a position in the track.
 * Clamped, so a drag past either edge lands on the start or the end rather than
 * seeking out of bounds.
 */
fun positionAt(x: Float, widthPx: Int, durationMs: Long): Long {
    if (widthPx <= 0 || durationMs <= 0) return 0
    return (x / widthPx.toFloat() * durationMs).toLong().coerceIn(0L, durationMs)
}
