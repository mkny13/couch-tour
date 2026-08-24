package dev.mike.couchtour

/**
 * Pure filler-track detection and playback queue filtering (#49).
 *
 * Many shows on phish.in and Relisten include non-music tracks for intros, outros,
 * tuning, stage banter, crowd noise, and announcements. When the user enables the
 * "skip filler tracks" preference, these tracks are bypassed automatically during
 * playback advancement without altering how the setlist renders in the browse UI.
 */

private val singleFillerTerms = setOf(
    "intro",
    "introduction",
    "intro.",
    "band intro",
    "band intros",
    "band introduction",
    "band introductions",
    "crowd intro",
    "outro",
    "outroduction",
    "outro.",
    "band outro",
    "tuning",
    "stage tuning",
    "tuning / dead air",
    "tuning/dead air",
    "dead air",
    "banter",
    "stage banter",
    "chat",
    "chatter",
    "stage talk",
    "talk",
    "crowd",
    "crowd noise",
    "crowd / applause",
    "applause",
    "cheering",
    "take a step back",
    "take a step back / tuning",
    "take a step back/tuning",
    "announcement",
    "announcements",
    "stage announcement",
    "stage announcements",
    "mc",
    "encore break",
    "encore call",
    "encore break / tuning",
)

private val fillerPrefixes = listOf(
    "tuning -", "tuning:", "tuning /",
    "stage banter -", "stage banter:", "stage banter /",
    "band intros -", "band intros:", "band introductions -", "band introductions:",
    "stage announcement -", "stage announcement:", "stage announcements -", "stage announcements:",
    "crowd noise -", "crowd noise:",
    "encore break -", "encore break:",
)

/** Returns `true` if the given track title matches a known non-music filler pattern. */
fun isFillerTrack(title: String): Boolean {
    var cleaned = title.trim().lowercase()
    if (cleaned.isEmpty()) return false

    // Strip trailing transition symbols like "->", ">", "...", etc.
    while (cleaned.endsWith("->") || cleaned.endsWith(">") || cleaned.endsWith("-") || cleaned.endsWith(".")) {
        cleaned = if (cleaned.endsWith("->")) {
            cleaned.removeSuffix("->").trim()
        } else if (cleaned.endsWith(">")) {
            cleaned.removeSuffix(">").trim()
        } else if (cleaned.endsWith("-")) {
            cleaned.removeSuffix("-").trim()
        } else {
            cleaned.removeSuffix(".").trim()
        }
    }

    if (cleaned in singleFillerTerms) return true

    // Check compound titles where every segment is a filler term (e.g. "Tuning / Dead Air", "Crowd & Tuning")
    val parts = cleaned.split(Regex("[/&+|,]+")).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size > 1 && parts.all { it in singleFillerTerms }) {
        return true
    }

    // Prefixes
    for (prefix in fillerPrefixes) {
        if (cleaned.startsWith(prefix)) return true
    }

    return false
}

data class FilteredTracks<T>(
    val items: List<T>,
    val startIndex: Int,
)

/**
 * Filters a list of tracks when building a playback queue with filler skipping enabled.
 *
 * Rules:
 * - If `skipFiller` is `false`, returns the original track list and start index unchanged.
 * - If starting from index 0 and track 0 is filler, advances `startIndex` to the first non-filler track.
 * - If the user explicitly tapped a filler track (`startIndex > 0` and `tracks[startIndex]` is filler),
 *   that track is kept so it plays, but subsequent filler tracks are skipped.
 * - All other filler tracks are omitted from the resulting playback queue.
 */
fun <T> filterPlaybackTracks(
    tracks: List<T>,
    startIndex: Int,
    skipFiller: Boolean,
    titleOf: (T) -> String,
): FilteredTracks<T> {
    if (!skipFiller || tracks.isEmpty() || startIndex !in tracks.indices) {
        return FilteredTracks(tracks, startIndex)
    }

    var effectiveStartIndex = startIndex
    // If started from top (0) and track 0 is filler, find the first non-filler track
    if (effectiveStartIndex == 0 && isFillerTrack(titleOf(tracks[0]))) {
        val firstNonFiller = tracks.indices.firstOrNull { !isFillerTrack(titleOf(tracks[it])) }
        if (firstNonFiller != null) {
            effectiveStartIndex = firstNonFiller
        }
    }

    val filtered = mutableListOf<T>()
    var newStartIndex = 0

    for ((idx, track) in tracks.withIndex()) {
        val isTapped = (idx == effectiveStartIndex)
        val isFiller = isFillerTrack(titleOf(track))

        if (isTapped || !isFiller) {
            if (isTapped) {
                newStartIndex = filtered.size
            }
            filtered.add(track)
        }
    }

    if (filtered.isEmpty()) {
        return FilteredTracks(tracks, startIndex)
    }

    return FilteredTracks(filtered, newStartIndex)
}
