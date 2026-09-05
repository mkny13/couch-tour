package dev.mike.couchtour

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "saved_shows"
private const val KEY_SHOWS = "show_keys"

/**
 * Saved/bookmarked shows — account-free local storage of saved show identifiers
 * (such as "1997-11-17" or relisten recording keys), mirroring [LikedTracks] and [Favorites].
 */
object SavedShows {

    private lateinit var prefs: android.content.SharedPreferences

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _keys.value = prefs.getStringSet(KEY_SHOWS, emptySet()).orEmpty().toSet()
    }

    fun toggle(key: String) {
        val updated = if (key in _keys.value) _keys.value - key else _keys.value + key
        _keys.value = updated
        prefs.edit().putStringSet(KEY_SHOWS, updated).apply()
    }

    fun contains(key: String): Boolean = key in _keys.value
}
