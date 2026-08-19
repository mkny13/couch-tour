package dev.mike.couchtour

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "liked_tracks"
private const val KEY_TRACKS = "track_ids"

/**
 * Liked Relisten tracks (#11) — a local, account-free mirror of phish.in's built-in
 * likes, which are server-side and gated on [Session.username]. Relisten has no account
 * system, so there's nothing to route through `PhishInApi.like`/`.unlike`; this is a set of
 * [PlayableTrack.id]s (Relisten's track `uuid`s) instead, following [Favorites]'s pattern.
 *
 * Scoped to track rows that already hold a [PlayableTrack] (mirroring where phish.in's
 * `LikeButton` lives, e.g. `RecordingTrackRow`) — the Now Playing screen has no track id to
 * key off yet (`PlayerState`, see ROADMAP.md) and is left as a follow-up.
 */
object LikedTracks {

    private lateinit var prefs: android.content.SharedPreferences

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _ids.value = prefs.getStringSet(KEY_TRACKS, emptySet()).orEmpty().toSet()
    }

    fun toggle(id: String) {
        val updated = if (id in _ids.value) _ids.value - id else _ids.value + id
        _ids.value = updated
        prefs.edit().putStringSet(KEY_TRACKS, updated).apply()
    }
}
