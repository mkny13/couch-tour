package dev.mike.phishin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Last.fm connection, restored from encrypted storage at startup. */
object LastFmSession {

    private var store: TokenStore? = null

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    var sessionKey: String? = null
        private set

    val connected: Boolean get() = sessionKey != null

    fun init(context: Context) {
        val tokenStore = TokenStore(context)
        store = tokenStore
        sessionKey = tokenStore.lastFmKey
        _username.value = tokenStore.lastFmUser
    }

    fun connect(key: String, user: String) {
        sessionKey = key
        _username.value = user
        store?.lastFmKey = key
        store?.lastFmUser = user
    }

    fun disconnect() {
        sessionKey = null
        _username.value = null
        store?.lastFmKey = null
        store?.lastFmUser = null
    }
}

/**
 * Watches one track at a time and decides when it has been played enough to count.
 *
 * Accumulates *listened* time rather than reading the playhead, so seeking to the end of a
 * track doesn't fake a scrobble, and pausing doesn't keep counting.
 */
class Scrobbler(private val submit: (PendingScrobble) -> Unit) {

    private var artist: String = "Phish"
    private var track: String? = null
    private var album: String = ""
    private var durationMs: Long = 0
    private var startedAtSec: Long = 0
    private var listenedMs: Long = 0
    private var lastTickMs: Long? = null
    private var submitted = false

    /** Called on every track change. Submits the outgoing track first if it earned it. */
    fun onTrackChanged(title: String?, albumTitle: String, durationMs: Long, nowMs: Long) {
        // Moving playback to a Chromecast (or back) re-announces the track that is already
        // playing. Restarting the clock there would make a long jam scrobble twice: once on
        // the phone before the handoff, once on the TV four minutes later.
        if (title != null && title == track && albumTitle == album) return

        finishCurrent(nowMs)

        track = title
        album = albumTitle
        this.durationMs = durationMs
        startedAtSec = nowMs / 1000
        listenedMs = 0
        lastTickMs = null
        submitted = false
    }

    /**
     * The player doesn't know a track's length at transition time, only once it has
     * prepared the media, so the duration arrives late and is filled in here.
     */
    fun onDuration(durationMs: Long) {
        if (durationMs > 0) this.durationMs = durationMs
    }

    fun onPlayingChanged(isPlaying: Boolean, nowMs: Long) {
        accumulate(nowMs)
        lastTickMs = if (isPlaying) nowMs else null
    }

    /** Called on the periodic tick while playing. */
    fun onTick(nowMs: Long) {
        accumulate(nowMs)
        lastTickMs = nowMs
        maybeSubmit()
    }

    fun onStopped(nowMs: Long) = finishCurrent(nowMs)

    private fun accumulate(nowMs: Long) {
        lastTickMs?.let { listenedMs += (nowMs - it).coerceAtLeast(0) }
    }

    private fun finishCurrent(nowMs: Long) {
        accumulate(nowMs)
        lastTickMs = null
        maybeSubmit()
    }

    private fun maybeSubmit() {
        if (submitted) return
        val title = track ?: return
        if (!ScrobblePolicy.shouldScrobble(listenedMs, durationMs)) return
        submitted = true
        submit(
            PendingScrobble(
                artist = artist,
                track = title,
                album = album,
                durationSec = (durationMs / 1000).toInt(),
                timestampSec = startedAtSec,
            )
        )
    }
}

/** Persists scrobbles and drains them whenever the network cooperates. */
object ScrobbleQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(context: Context, scrobble: PendingScrobble) {
        scope.launch {
            PhishInDb.get(context).scrobbleDao().add(scrobble)
            drain(context)
        }
    }

    /** Called when playback starts, to clear anything stranded by an earlier outage. */
    fun flush(context: Context) {
        scope.launch { drain(context) }
    }

    private suspend fun drain(context: Context) {
        val key = LastFmSession.sessionKey ?: return
        if (!LastFmApi.configured) return
        val dao = PhishInDb.get(context).scrobbleDao()
        for (pending in dao.oldest()) {
            try {
                LastFmApi.scrobble(key, pending.toScrobble())
                dao.remove(pending.id)
            } catch (e: Exception) {
                // Offline or rate-limited: leave it queued and stop, rather than
                // hammering the API or dropping the play.
                Log.w("ScrobbleQueue", "Scrobble deferred: ${e.message}")
                return
            }
        }
    }
}
