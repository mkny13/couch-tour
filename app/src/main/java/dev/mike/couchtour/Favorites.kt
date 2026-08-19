package dev.mike.couchtour

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "favorites"
private const val KEY_ARTISTS = "artist_keys"

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

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _keys.value = prefs.getStringSet(KEY_ARTISTS, emptySet()).orEmpty().toSet()
    }

    fun toggle(key: String) {
        val updated = if (key in _keys.value) _keys.value - key else _keys.value + key
        _keys.value = updated
        prefs.edit().putStringSet(KEY_ARTISTS, updated).apply()
    }
}
