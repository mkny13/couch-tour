package dev.mike.couchtour

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "playback_settings"
private const val KEY_SKIP_FILLER = "skip_filler"
private const val KEY_GAPLESS = "gapless"
private const val KEY_AUDIO_QUALITY = "audio_quality"

/**
 * Which encoding a stream that offers both should play (#141). Only tapes with a
 * `flac_url` can honour [LOSSLESS] — phish.in itself serves MP3 — so the preference is
 * "prefer lossless", not a guarantee.
 */
enum class AudioQuality(val storageValue: String) {
    LOSSLESS("lossless"),
    COMPRESSED("compressed");

    companion object {
        fun fromStorage(value: String?): AudioQuality {
            return entries.firstOrNull { it.storageValue == value } ?: LOSSLESS
        }
    }
}

/**
 * Persistent playback preferences (#49).
 *
 * Backed by plain `SharedPreferences` (matching [Favorites.kt] / [LikedTracks.kt]).
 */
object PlaybackSettings {

    private lateinit var prefs: android.content.SharedPreferences

    private val _skipFiller = MutableStateFlow(false)
    val skipFiller: StateFlow<Boolean> = _skipFiller.asStateFlow()

    /**
     * Gapless segue between tracks (#141). Applied by [PlaybackService] to the local
     * player as a preload configuration; see that wiring for what "gapless" can honestly
     * mean in Media3's decode path.
     */
    private val _gapless = MutableStateFlow(true)
    val gapless: StateFlow<Boolean> = _gapless.asStateFlow()

    private val _audioQuality = MutableStateFlow(AudioQuality.LOSSLESS)
    val audioQuality: StateFlow<AudioQuality> = _audioQuality.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _skipFiller.value = prefs.getBoolean(KEY_SKIP_FILLER, false)
        _gapless.value = prefs.getBoolean(KEY_GAPLESS, true)
        _audioQuality.value = AudioQuality.fromStorage(prefs.getString(KEY_AUDIO_QUALITY, null))
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

    fun setGapless(enabled: Boolean) {
        _gapless.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_GAPLESS, enabled).apply()
        }
    }

    fun setAudioQuality(quality: AudioQuality) {
        _audioQuality.value = quality
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_AUDIO_QUALITY, quality.storageValue).apply()
        }
    }
}
