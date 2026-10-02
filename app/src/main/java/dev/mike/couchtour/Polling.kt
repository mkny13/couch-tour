package dev.mike.couchtour

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** Backoff for the Sync screen's device-list refresh (#209), kept pure so it can be tested. */
object DeviceListBackoff {
    const val INITIAL_MS = 5_000L
    const val MAX_MS = 60_000L

    /** Quiet polls slow down by 1.5x up to [MAX_MS]; any change snaps back to [INITIAL_MS]. */
    fun next(currentMs: Long, changed: Boolean): Long =
        if (changed) INITIAL_MS else (currentMs * 1.5).toLong().coerceAtMost(MAX_MS)
}

/**
 * Calls [save] every [tickMs] while [playing] is true and sleeps with no timer at all otherwise,
 * so a paused or idle service doesn't wake every few seconds for nothing (#505). The tick
 * restarts from zero each time playback resumes; the play/pause edge itself is saved by the
 * player listener, so at most [tickMs] of progress is lost while playing.
 */
suspend fun runWhilePlaying(playing: Flow<Boolean>, tickMs: Long, save: () -> Unit) {
    playing.collectLatest { isPlaying ->
        if (!isPlaying) return@collectLatest
        while (true) {
            delay(tickMs)
            save()
        }
    }
}
