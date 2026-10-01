package dev.mike.couchtour

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "favorites"
private const val KEY_ARTISTS = "artist_keys"
private const val KEY_ARTIST_ROWS = "artist_rows_json"

@Serializable
data class FavoriteArtistSyncRow(
    val artistKey: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * Favorited artists (#14) — a set of [ArtistRef.key]s, surfaced on the Home screen and used
 * to reorder the browse-artists list (see [mergeArtists]).
 *
 * Plain `SharedPreferences`, not [PhishInDb]: this is low-cardinality preference data, not
 * something relational, so it skips Room's migration ceremony entirely (CLAUDE.md). Unlike
 * [TokenStore] it isn't encrypted — an artist name a user likes isn't a credential.
 */
object Favorites {

    private lateinit var prefs: android.content.SharedPreferences
    private var initialized = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var rows: MutableMap<String, FavoriteArtistSyncRow> = mutableMapOf()

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        initialized = true
        synchronized(lock) {
            rows = loadRows().associateBy { it.artistKey }.toMutableMap()
            publishLocked()
        }
    }

    fun toggle(key: String) {
        val now = System.currentTimeMillis()
        val on: Boolean
        synchronized(lock) {
            val existing = rows[key]
            on = existing?.deletedAt != null || existing == null
            rows[key] = if (on) {
                FavoriteArtistSyncRow(artistKey = key, updatedAt = now, deletedAt = null)
            } else {
                FavoriteArtistSyncRow(artistKey = key, updatedAt = now, deletedAt = now)
            }
            persistLocked()
            publishLocked()
        }
        DiagnosticsLog.log("library.favorite", "kind" to "artist", "key" to key, "on" to on)
    }

    fun changedSince(since: Long): List<FavoriteArtistSyncRow> = synchronized(lock) {
        if (!initialized) return emptyList()
        rows.values.filter { it.updatedAt > since }.sortedBy { it.updatedAt }
    }

    fun applyFromSync(changes: List<FavoriteArtistSyncRow>): Int = synchronized(lock) {
        if (!initialized) return 0
        var accepted = 0
        for (change in changes) {
            val existing = rows[change.artistKey]
            if (existing == null || change.updatedAt >= existing.updatedAt) {
                rows[change.artistKey] = change
                accepted++
            }
        }
        if (accepted > 0) {
            persistLocked()
            publishLocked()
        }
        accepted
    }

    private fun liveKeysLocked(): Set<String> =
        rows.values.filter { it.deletedAt == null }.map { it.artistKey }.toSet()

    private fun publishLocked() {
        _keys.value = liveKeysLocked()
    }

    private fun persistLocked() {
        val live = liveKeysLocked()
        prefs.edit()
            .putString(KEY_ARTIST_ROWS, json.encodeToString(rows.values.sortedBy { it.artistKey }))
            .putStringSet(KEY_ARTISTS, live)
            .apply()
    }

    private fun loadRows(): List<FavoriteArtistSyncRow> {
        val rawRows = prefs.getString(KEY_ARTIST_ROWS, null)
        if (rawRows != null) {
            return runCatching { json.decodeFromString<List<FavoriteArtistSyncRow>>(rawRows) }
                .getOrElse { emptyList() }
        }

        val legacyLive = prefs.getStringSet(KEY_ARTISTS, emptySet()).orEmpty()
        if (legacyLive.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return legacyLive.map { FavoriteArtistSyncRow(artistKey = it, updatedAt = now, deletedAt = null) }
    }
}
