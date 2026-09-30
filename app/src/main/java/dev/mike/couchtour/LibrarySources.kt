package dev.mike.couchtour

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
    data class Playlist(val slug: String) : LibraryTarget
    data class Show(val date: String) : LibraryTarget
    data class Recording(val id: RecordingId) : LibraryTarget
    data class AccountTrack(val track: Track) : LibraryTarget
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
    val backend: String? = null,
    val trackId: String? = null,
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
            backend = track.backend,
            trackId = track.trackId,
        )
    }

data class LibraryAccountData(
    val playlists: List<Playlist> = emptyList(),
    val shows: List<Show> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val loaded: Boolean,
    val error: Boolean = false,
)

suspend fun loadLibraryAccount(
    api: PhishInApi = PhishInApi,
    username: String? = Session.username.value
): LibraryAccountData {
    if (username.isNullOrBlank()) {
        return LibraryAccountData(
            playlists = emptyList(),
            shows = emptyList(),
            tracks = emptyList(),
            loaded = true,
            error = false,
        )
    }
    return try {
        coroutineScope {
            val minePlaylistsDeferred = async { api.playlists(filter = "mine") }
            val likedPlaylistsDeferred = async { api.playlists(filter = "liked") }
            val showsDeferred = async { api.likedShows() }
            val tracksDeferred = async { api.likedTracks() }

            val playlists = (minePlaylistsDeferred.await() + likedPlaylistsDeferred.await())
                .distinctBy { it.slug }
            val shows = showsDeferred.await()
            val tracks = tracksDeferred.await()

            LibraryAccountData(
                playlists = playlists,
                shows = shows,
                tracks = tracks,
                loaded = true,
                error = false,
            )
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        LibraryAccountData(
            playlists = emptyList(),
            shows = emptyList(),
            tracks = emptyList(),
            loaded = true,
            error = true,
        )
    }
}

internal fun sanitizeLibraryTitle(title: String, fallback: String): String {
    var trimmed = title.trim()
    for (prefix in QUEUE_PREFIXES) {
        if (trimmed.startsWith(prefix)) {
            trimmed = trimmed.removePrefix(prefix).trim()
        }
    }
    if (trimmed.isEmpty()) {
        val fb = QUEUE_PREFIXES.fold(fallback.trim()) { acc, p -> acc.removePrefix(p).trim() }
        return fb.ifEmpty { "Item" }
    }
    return trimmed
}

fun accountPlaylistItems(account: List<Playlist>): List<LibraryItem> =
    account.distinctBy { it.slug }.map { playlist ->
        val cleanTitle = sanitizeLibraryTitle(playlist.name, "Playlist")
        LibraryItem(
            key = "pl_${playlist.slug}",
            rawKey = null,
            badge = "LIST",
            title = cleanTitle,
            subtitle = "phish.in · ${playlist.tracksCount} ${plural(playlist.tracksCount, "track")}",
            addedAt = null,
            sortKey = cleanTitle,
            target = LibraryTarget.Playlist(playlist.slug),
            durationMs = playlist.duration,
            searchTerms = listOfNotNull(cleanTitle, playlist.description, playlist.username, "phish.in"),
        )
    }

fun accountShowItems(account: List<Show>): List<LibraryItem> =
    account.map { show ->
        val rawDate = sanitizeLibraryTitle(show.date, "Show")
        val dateTitle = parseShowDate(rawDate) ?: formatShowDate(rawDate)
        val cleanTitle = sanitizeLibraryTitle(dateTitle, rawDate)
        val subtitle = listOfNotNull(
            show.venueName?.trim()?.ifBlank { null },
            show.location?.trim()?.ifBlank { null }
        ).joinToString(" · ").ifBlank { "phish.in" }
        LibraryItem(
            key = "show_${cleanTitle}",
            rawKey = null,
            badge = "SHOW",
            title = cleanTitle,
            subtitle = subtitle,
            addedAt = null,
            sortKey = cleanTitle,
            target = LibraryTarget.Show(cleanTitle),
            durationMs = show.duration,
            searchTerms = listOfNotNull(cleanTitle, show.venueName, show.location, show.tourName, "phish.in"),
        )
    }

fun accountTrackItems(account: List<Track>): List<LibraryItem> =
    account.map { track ->
        val cleanTitle = sanitizeLibraryTitle(track.title, "Track")
        val trackSubtitle = listOfNotNull(
            track.showDate?.trim()?.ifBlank { null },
            track.venueName?.trim()?.ifBlank { null },
            track.venueLocation?.trim()?.ifBlank { null }
        ).joinToString(" · ").ifBlank { "phish.in" }
        LibraryItem(
            key = "trk_phishin_${track.id}",
            rawKey = null,
            badge = "TRACK",
            title = cleanTitle,
            subtitle = trackSubtitle,
            addedAt = null,
            sortKey = cleanTitle,
            target = LibraryTarget.AccountTrack(track),
            trailingText = if (track.duration > 0) fmt(track.duration) else null,
            durationMs = track.duration,
            searchTerms = listOfNotNull(cleanTitle, track.showDate, track.venueName, track.venueLocation, "phish.in"),
            backend = "phishin",
            trackId = track.id.toString(),
        )
    }

fun relistenLikedTrackItems(entries: List<LikedTrackRef>): List<LibraryItem> =
    entries.sortedByDescending { it.likedAt }.map { ref ->
        val cleanTitle = sanitizeLibraryTitle(ref.title, "Track")
        val subtitle = listOfNotNull(
            ref.artistName.trim().ifBlank { null },
            ref.showDate.trim().ifBlank { null },
            ref.venueName?.trim()?.ifBlank { null }
        ).joinToString(" · ").ifBlank { ref.backend.ifBlank { Backend.RELISTEN.id } }
        val target = if (!ref.recordingId.isNullOrBlank() && ref.artistSlug.isNotBlank()) {
            LibraryTarget.Recording(RecordingId(ref.artistSlug, ref.showDate, ref.recordingId))
        } else {
            LibraryTarget.Show(ref.showDate)
        }
        LibraryItem(
            key = "trk_relisten_${ref.id}",
            rawKey = null,
            badge = "TRACK",
            title = cleanTitle,
            subtitle = subtitle,
            addedAt = if (ref.likedAt > 0) ref.likedAt else null,
            sortKey = cleanTitle,
            target = target,
            trailingText = if (ref.durationMs > 0) fmt(ref.durationMs) else null,
            durationMs = ref.durationMs,
            searchTerms = listOfNotNull(cleanTitle, ref.artistName, ref.showDate, ref.venueName, ref.backend),
            backend = ref.backend.ifBlank { Backend.RELISTEN.id },
            trackId = ref.id,
        )
    }

fun mergeLibraryPlaylists(local: List<LibraryItem>, account: List<LibraryItem>): List<LibraryItem> {
    val items = local + account
    val seen = mutableSetOf<String>()
    val result = mutableListOf<LibraryItem>()
    val sorted = items.sortedWith(compareByDescending { it.addedAt ?: Long.MIN_VALUE })
    for (item in sorted) {
        val dedupeKey = when (val t = item.target) {
            is LibraryTarget.Playlist -> "slug:${t.slug}"
            is LibraryTarget.LocalPlaylist -> "local:${t.id}"
            else -> item.key
        }
        if (seen.add(dedupeKey)) {
            result.add(item)
        }
    }
    return result
}

fun mergeLibraryShows(local: List<LibraryItem>, account: List<LibraryItem>): List<LibraryItem> {
    val items = local + account
    val groups = LinkedHashMap<String, MutableList<LibraryItem>>()
    for (item in items) {
        val key = when (val t = item.target) {
            is LibraryTarget.Show -> "show:${t.date}"
            is LibraryTarget.Recording -> "relisten:${t.id.artistSlug}/${t.id.date}"
            else -> item.key
        }
        groups.getOrPut(key) { mutableListOf() }.add(item)
    }
    return groups.values.map { group ->
        if (group.size == 1) {
            group.first()
        } else {
            val dated = group.filter { it.addedAt != null }.maxByOrNull { it.addedAt!! }
            if (dated != null) {
                val rawKey = dated.rawKey ?: group.firstOrNull { it.rawKey != null }?.rawKey
                dated.copy(rawKey = rawKey)
            } else {
                val accountShow = group.firstOrNull { it.subtitle != "Saved show" } ?: group.first()
                val rawKey = accountShow.rawKey ?: group.firstOrNull { it.rawKey != null }?.rawKey
                accountShow.copy(rawKey = rawKey)
            }
        }
    }
}

fun mergeLibraryTracks(
    local: List<LibraryItem>,
    account: List<LibraryItem>,
    relisten: List<LibraryItem> = emptyList()
): List<LibraryItem> {
    val items = local + account + relisten
    val groups = LinkedHashMap<String, MutableList<LibraryItem>>()
    for (item in items) {
        val key = if (item.backend != null && item.trackId != null) {
            "${item.backend}:${item.trackId}"
        } else {
            item.key
        }
        groups.getOrPut(key) { mutableListOf() }.add(item)
    }
    return groups.values.map { group ->
        if (group.size == 1) {
            group.first()
        } else {
            val dated = group.filter { it.addedAt != null }.maxByOrNull { it.addedAt!! }
            dated ?: group.first()
        }
    }
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
