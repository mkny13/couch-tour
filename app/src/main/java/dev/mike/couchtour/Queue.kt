package dev.mike.couchtour

enum class QueueKind { SHOW, PLAYLIST, RECORDING }

/**
 * Playback progress is stored under a namespaced key so every kind of queue can share one
 * table: "show:1997-02-13", "playlist:some-slug", "relisten:grateful-dead/1977-05-08/<uuid>".
 *
 * The namespacing is why adding a second backend needs no migration — a Relisten key cannot
 * collide with a phish.in one, so existing rows keep working untouched.
 */
data class QueueRef(val kind: QueueKind, val id: String) {
    val key: String get() = when (kind) {
        QueueKind.SHOW -> showQueueKey(id)
        QueueKind.PLAYLIST -> playlistQueueKey(id)
        QueueKind.RECORDING -> RECORDING_PREFIX + id
    }
}

/**
 * The three things needed to fetch a Relisten queue back.
 *
 * The source matters as much as the date: Relisten carries around nine tapes of an average
 * Grateful Dead show, and two tapes of one date split the music into different tracks. A key
 * without its source would resume a stored index against the wrong track list.
 */
data class RecordingId(val artistSlug: String, val date: String, val sourceId: String) {
    val id: String get() = "$artistSlug/$date/$sourceId"
}

fun showQueueKey(date: String) = "show:$date"

fun playlistQueueKey(slug: String) = "playlist:$slug"

fun recordingQueueKey(artistSlug: String, date: String, sourceId: String) =
    RECORDING_PREFIX + RecordingId(artistSlug, date, sourceId).id

/**
 * Splits a stored key back into its parts. Returns null for anything unrecognised rather
 * than guessing — an unknown key should be skipped, not played as the wrong thing.
 */
fun parseQueueKey(raw: String): QueueRef? = when {
    raw.startsWith(PLAYLIST_PREFIX) ->
        raw.removePrefix(PLAYLIST_PREFIX).takeIf { it.isNotEmpty() }
            ?.let { QueueRef(QueueKind.PLAYLIST, it) }

    raw.startsWith(SHOW_PREFIX) ->
        raw.removePrefix(SHOW_PREFIX).takeIf { it.isNotEmpty() }
            ?.let { QueueRef(QueueKind.SHOW, it) }

    // Validated on the way in, unlike the other two: a recording id that isn't all three
    // parts is unusable, and failing here beats failing at fetch time.
    raw.startsWith(RECORDING_PREFIX) ->
        parseRecordingId(raw.removePrefix(RECORDING_PREFIX))?.let { QueueRef(QueueKind.RECORDING, it.id) }

    else -> null
}

/**
 * Parts are split on "/" rather than ":" so the first-colon-only rule that show and playlist
 * keys live under — a playlist slug may contain a colon — never has to apply here. Relisten
 * slugs and UUIDs contain no slashes.
 */
fun parseRecordingId(raw: String): RecordingId? {
    val parts = raw.split('/')
    if (parts.size != 3 || parts.any { it.isEmpty() }) return null
    return RecordingId(parts[0], parts[1], parts[2])
}

private const val SHOW_PREFIX = "show:"
private const val PLAYLIST_PREFIX = "playlist:"
private const val RECORDING_PREFIX = "relisten:"
