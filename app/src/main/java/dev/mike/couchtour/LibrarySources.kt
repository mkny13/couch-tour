package dev.mike.couchtour

enum class LibraryFilter {
    ALL, PLAYLISTS, SHOWS, TRACKS
}

enum class LibrarySortMode(val label: String) {
    RECENTLY_ADDED("Recently added"),
    TITLE_ASC("Title (A–Z)"),
    TITLE_DESC("Title (Z–A)"),
}

sealed interface LibraryTarget {
    data class LocalPlaylist(val id: String) : LibraryTarget
    data class Show(val date: String) : LibraryTarget
    data class Recording(val id: RecordingId) : LibraryTarget
}

data class LibraryItem(
    val key: String,
    val rawKey: String? = null,
    val badge: String,
    val title: String,
    val subtitle: String,
    val addedAt: Long?,
    val sortKey: String,
    val target: LibraryTarget,
    val trailingText: String? = null,
    val durationMs: Long = 0L,
    val searchTerms: List<String> = emptyList(),
)

private val QUEUE_PREFIXES = listOf("show:", "relisten:", "playlist:", "local-playlist:", "youtube:")

fun parseShowDate(rawDate: String): String? {
    val formatted = formatShowDate(rawDate)
    val parts = formatted.split('-')
    if (parts.size == 3 && parts[0].length == 4 && parts[1].length == 2 && parts[2].length == 2) {
        val y = parts[0].toIntOrNull()
        val m = parts[1].toIntOrNull()
        val d = parts[2].toIntOrNull()
        if (y != null && m != null && d != null && y in 1901..2099 && m in 1..12 && d in 1..31) {
            return formatted
        }
    }
    return null
}

fun savedShowItems(keys: Set<String>): List<LibraryItem> =
    keys.mapNotNull { rawKey ->
        val trimmed = rawKey.trim()
        val (date, target) = when {
            trimmed.startsWith("relisten:") -> {
                val rec = parseRecordingId(trimmed.removePrefix("relisten:")) ?: return@mapNotNull null
                val validDate = parseShowDate(rec.date) ?: return@mapNotNull null
                validDate to LibraryTarget.Recording(rec)
            }
            trimmed.startsWith("show:") -> {
                val validDate = parseShowDate(trimmed.removePrefix("show:")) ?: return@mapNotNull null
                validDate to LibraryTarget.Show(validDate)
            }
            QUEUE_PREFIXES.any { trimmed.startsWith(it) } -> return@mapNotNull null
            else -> {
                val validDate = parseShowDate(trimmed) ?: return@mapNotNull null
                validDate to LibraryTarget.Show(validDate)
            }
        }
        LibraryItem(
            key = "show_$rawKey",
            rawKey = rawKey,
            badge = "SHOW",
            title = date,
            subtitle = "Saved show",
            addedAt = null,
            sortKey = date,
            target = target,
        )
    }.sortedByDescending { it.sortKey }

fun playlistItems(local: List<LocalPlaylistEntity>): List<LibraryItem> =
    local.sortedByDescending { it.updatedAt }.map { playlist ->
        LibraryItem(
            key = "pl_${playlist.id}",
            rawKey = playlist.id,
            badge = "LIST",
            title = playlist.name,
            subtitle = "${playlist.trackCount} ${plural(playlist.trackCount, "track")}",
            addedAt = playlist.updatedAt,
            sortKey = playlist.name,
            target = LibraryTarget.LocalPlaylist(playlist.id),
        )
    }

fun trackItems(local: List<LocalPlaylistTrackEntity>): List<LibraryItem> =
    local.sortedByDescending { it.rowId }.map { track ->
        val trackSubtitle = listOfNotNull(
            track.showDate.ifBlank { null },
            track.venueName?.ifBlank { null }
        ).joinToString(" · ").ifBlank { track.backend }
        LibraryItem(
            key = "trk_${track.rowId}",
            rawKey = track.rowId.toString(),
            badge = "TRACK",
            title = track.title,
            subtitle = trackSubtitle,
            addedAt = null,
            sortKey = track.title,
            target = LibraryTarget.LocalPlaylist(track.playlistId),
            trailingText = if (track.durationMs > 0) fmt(track.durationMs) else null,
            durationMs = track.durationMs,
            searchTerms = listOfNotNull(track.showDate, track.venueName, track.backend),
        )
    }

fun sortLibraryItems(items: List<LibraryItem>, sortMode: LibrarySortMode): List<LibraryItem> =
    when (sortMode) {
        LibrarySortMode.RECENTLY_ADDED -> {
            val dated = items.filter { it.addedAt != null }.sortedByDescending { it.addedAt }
            val undated = items.filter { it.addedAt == null }
            dated + undated
        }
        LibrarySortMode.TITLE_ASC -> items.sortedBy { it.sortKey.lowercase() }
        LibrarySortMode.TITLE_DESC -> items.sortedByDescending { it.sortKey.lowercase() }
    }

fun filterLibraryItems(items: List<LibraryItem>, query: String): List<LibraryItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return items
    return items.filter { item ->
        item.title.contains(trimmed, ignoreCase = true) ||
        item.subtitle.contains(trimmed, ignoreCase = true) ||
        item.searchTerms.any { it.contains(trimmed, ignoreCase = true) }
    }
}

fun historyDisplayTitle(title: String, queueKey: String): String {
    val trimmed = title.trim()
    val hasPrefix = QUEUE_PREFIXES.any { trimmed.startsWith(it) }
    if (trimmed.isNotEmpty() && !hasPrefix) {
        return trimmed
    }
    val ref = parseQueueKey(queueKey) ?: return "Removed show"
    return when (ref.kind) {
        QueueKind.SHOW -> parseShowDate(ref.id) ?: "Removed show"
        QueueKind.RECORDING -> parseRecordingId(ref.id)?.date?.let { parseShowDate(it) } ?: "Removed show"
        else -> "Removed show"
    }
}
