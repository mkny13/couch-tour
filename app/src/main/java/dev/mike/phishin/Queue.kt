package dev.mike.phishin

enum class QueueKind { SHOW, PLAYLIST }

/**
 * Playback progress is stored under a namespaced key so shows and playlists can share one
 * table: "show:1997-02-13", "playlist:some-slug".
 */
data class QueueRef(val kind: QueueKind, val id: String) {
    val key: String get() = when (kind) {
        QueueKind.SHOW -> showQueueKey(id)
        QueueKind.PLAYLIST -> playlistQueueKey(id)
    }
}

fun showQueueKey(date: String) = "show:$date"

fun playlistQueueKey(slug: String) = "playlist:$slug"

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

    else -> null
}

private const val SHOW_PREFIX = "show:"
private const val PLAYLIST_PREFIX = "playlist:"
