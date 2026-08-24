package dev.mike.couchtour

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "playback_settings"
private const val KEY_SKIP_FILLER = "skip_filler"

/**
 * Persistent playback preferences (#49).
 *
 * Backed by plain `SharedPreferences` (matching [Favorites.kt] / [LikedTracks.kt]).
 */
object PlaybackSettings {

    private lateinit var prefs: android.content.SharedPreferences

    private val _skipFiller = MutableStateFlow(false)
    val skipFiller: StateFlow<Boolean> = _skipFiller.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _skipFiller.value = prefs.getBoolean(KEY_SKIP_FILLER, false)
    }

    fun setSkipFiller(enabled: Boolean) {
        _skipFiller.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_SKIP_FILLER, enabled).apply()
        }
    }

    fun toggle() {
        setSkipFiller(!_skipFiller.value)
    }
}
