package dev.mike.couchtour

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFS = "liked_tracks"
private const val KEY_TRACKS = "track_ids"
private const val KEY_RECORDS = "track_records"

@Serializable
data class LikedTrackRef(
    val id: String,
    val title: String = "",
    val showDate: String = "",
    val venueName: String? = null,
    val durationMs: Long = 0,
    val artistName: String = "",
    val artistSlug: String = "",
    val recordingId: String? = null,
    val artUrl: String? = null,
    val likedAt: Long = 0,
    val backend: String = Backend.RELISTEN.id,
)

internal fun LikedTrackRef.toLocalPlaylistTrackRef(): LocalPlaylistTrackEntity = LocalPlaylistTrackEntity(
    playlistId = "",
    position = 0,
    backend = backend,
    trackId = id,
    showDate = showDate,
    artistSlug = artistSlug.ifEmpty { null },
    recordingId = recordingId,
    title = title,
    durationMs = durationMs,
    venueName = venueName,
    artUrl = artUrl,
)

/**
 * Derives the bare Relisten tape source ID from a player [queueKey], or null if the queue
 * is not a Relisten tape recording (e.g. show, shuffle, local playlist, or null).
 */
internal fun deriveRecordingId(queueKey: String?): String? {
    if (queueKey == null) return null
    val ref = parseQueueKey(queueKey) ?: return null
    if (ref.kind != QueueKind.RECORDING) return null
    return parseRecordingId(ref.id)?.sourceId
}

/**
 * Liked Relisten tracks (#11, #372) — a local, account-free mirror of phish.in's built-in
 * likes, which are server-side and gated on [Session.username]. Relisten has no account
 * system, so there's nothing to route through `PhishInApi.like`/`.unlike`.
 *
 * Stores full display metadata ([LikedTrackRef]) keyed by track UUID, persisted as a JSON map
 * under `track_records` in `SharedPreferences`, with backwards-compatible upgrade from the legacy
 * flat `track_ids` set.
 *
 * Likes can be toggled from both setlist track rows ([RecordingTrackRow]) and the Now Playing
 * screen ([LikeTrackButton]). Now Playing derives tape recording identity from the active
 * [PlayerState.queueKey] when available via [deriveRecordingId].
 *
 * Entries are exposed in reverse-chronological order of [LikedTrackRef.likedAt] via [entries],
 * while [ids] provides a fast lookup set for UI heart states.
 */
object LikedTracks {

    private lateinit var prefs: android.content.SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _records = MutableStateFlow<Map<String, LikedTrackRef>>(emptyMap())

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    private val _entries = MutableStateFlow<List<LikedTrackRef>>(emptyList())
    val entries: StateFlow<List<LikedTrackRef>> = _entries.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_RECORDS, null)
        val loaded: Map<String, LikedTrackRef> = if (!rawJson.isNullOrBlank()) {
            try {
                json.decodeFromString<Map<String, LikedTrackRef>>(rawJson)
            } catch (e: Exception) {
                DiagnosticsLog.log("liked_tracks.corrupt_json", "error" to (e.message ?: "unknown"))
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val legacyIds = prefs.getStringSet(KEY_TRACKS, emptySet()).orEmpty()
        val merged = loaded.toMutableMap()
        for (id in legacyIds) {
            if (id !in merged) {
                merged[id] = LikedTrackRef(id = id, likedAt = 0)
            }
        }

        _records.value = merged
        _ids.value = merged.keys.toSet()
        _entries.value = merged.values.sortedByDescending { it.likedAt }
    }

    fun contains(id: String): Boolean = id in _ids.value

    fun toggle(ref: LikedTrackRef) {
        val id = ref.id
        val on = id !in _records.value
        val updated = if (on) {
            val record = if (ref.likedAt != 0L) ref else ref.copy(likedAt = System.currentTimeMillis())
            _records.value + (id to record)
        } else {
            _records.value - id
        }
        _records.value = updated
        _ids.value = updated.keys.toSet()
        _entries.value = updated.values.sortedByDescending { it.likedAt }

        val legacy = prefs.getStringSet(KEY_TRACKS, null)
        prefs.edit {
            putString(KEY_RECORDS, json.encodeToString(updated))
            if (legacy != null && id in legacy) {
                putStringSet(KEY_TRACKS, legacy - id)
            }
        }

        DiagnosticsLog.log("library.favorite", "kind" to "track", "id" to id, "on" to on)
    }

    fun toggle(id: String) {
        toggle(LikedTrackRef(id = id))
    }
}
