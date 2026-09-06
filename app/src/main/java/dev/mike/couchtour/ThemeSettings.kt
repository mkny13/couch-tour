package dev.mike.couchtour

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val storageValue: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(value: String?): ThemeMode {
            return entries.firstOrNull { it.storageValue == value } ?: AUTO
        }
    }
}

private const val PREFS = "theme_settings"
private const val KEY_THEME_MODE = "theme_mode"

/**
 * Persistent theme preferences.
 *
 * Backed by plain `SharedPreferences` (matching [PlaybackSettings] / [Favorites]).
 */
object ThemeSettings {

    private lateinit var prefs: SharedPreferences

    private val _themeMode = MutableStateFlow(ThemeMode.AUTO)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_THEME_MODE, null)
        _themeMode.value = ThemeMode.fromStorage(saved)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_THEME_MODE, mode.storageValue).apply()
        }
    }
}
