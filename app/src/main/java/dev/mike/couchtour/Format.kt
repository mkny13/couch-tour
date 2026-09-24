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
 * Calculates playback progress fraction clamped strictly between 0.0 and 1.0.
 */
fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * Formats a duration compactly: m:ss for sub-hour (e.g. "0:45", "1:06"), h:mm for hour+ (e.g. "1:35", "2:41").
 * Dropping seconds past an hour keeps show and set totals compact in headers and badges.
 */
fun formatCompactDuration(ms: Long): String {
    val totalSec = (if (ms < 0) 0 else ms) / 1000
    val totalMin = totalSec / 60
    val h = totalMin / 60
    val m = totalMin % 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d".format(h, m)
    } else {
        "%d:%02d".format(m, s)
    }
}

/**
 * Ensures show date strictly adheres to YYYY-MM-DD (uat-006).
 * Accepts YYYY-MM-DD, YYYY/MM/DD, unpadded month/day (e.g. "1997-5-8"),
 * and standard text date formats (e.g. "May 8, 1977").
 * Out-of-range months (not 1..12) and days (not 1..31) are rejected from formatting
 * and returned as raw strings.
 */
fun formatShowDate(rawDate: String): String {
    val trimmed = rawDate.trim()
    val parts = trimmed.split('-', '/')
    if (parts.size == 3) {
        val y = parts[0].toIntOrNull()
        val m = parts[1].toIntOrNull()
        val d = parts[2].toIntOrNull()
        if (y != null && m != null && d != null &&
            y in 1901..2099 &&
            m in 1..12 &&
            d in 1..31
        ) {
            return "%04d-%02d-%02d".format(y, m, d)
        }
    }

    // Try standard date formats
    val formats = listOf("MMMM d, yyyy", "MMM d, yyyy", "yyyy-MM-dd", "yyyy/MM/dd")
    for (fmtStr in formats) {
        try {
            val formatter = java.time.format.DateTimeFormatter.ofPattern(fmtStr, java.util.Locale.US)
            val date = java.time.LocalDate.parse(trimmed, formatter)
            return date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
        }
    }
    return trimmed
}

/**
 * Formats remaining track time with a "left" suffix, e.g. "7:32 left".
 */
fun formatRemainingTime(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return "0:00 left"
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    return "${fmt(remainingMs)} left"
}

