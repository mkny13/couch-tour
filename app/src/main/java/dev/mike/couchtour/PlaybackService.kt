package dev.mike.couchtour

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Keys stuffed into MediaMetadata.extras so the service can attribute a track to its queue. */
object Keys {
    const val QUEUE_KEY = "queue_key"
    const val QUEUE_TITLE = "queue_title"
    const val QUEUE_SUBTITLE = "queue_subtitle"
    const val QUEUE_ART = "queue_art"
    const val WAVEFORM = "waveform"

    /** Cast has to be told what to carry across the wire; nothing else enumerates these. */
    val ALL = listOf(QUEUE_KEY, QUEUE_TITLE, QUEUE_SUBTITLE, QUEUE_ART, WAVEFORM)
}

/**
 * Everything needed to pick playback up on another player: the queue, where in it we are,
 * and whether it was running. Captured continuously, because a cast session that drops has
 * already thrown its queue away by the time we're told about it.
 */
private data class Handoff(
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
)

/**
 * Foreground media service. This is the piece a WebView can't provide: it owns the
 * MediaSession that Android surfaces on the lockscreen, in the notification shade, and
 * to Bluetooth / headset buttons.
 *
 * It owns both players — the local ExoPlayer and, once Cast is available, a CastPlayer —
 * and hands the session whichever one is live. Everything above it (the UI's
 * MediaController, the progress writer) is deliberately unaware of which.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var localPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var handoff: Handoff? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        localPlayer = player

        // Tapping the notification opens the app, which then navigates to whatever is
        // playing at that moment. The PendingIntent is built once and the queue changes as
        // playback moves, so the destination is resolved on arrival rather than baked in.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()

        // Save on the events that matter immediately. The same listener goes on the cast
        // player too, so casting scrobbles and records progress exactly like local playback.
        player.addListener(playerListener)

        // ...and on a slow tick while playing, so a crash or swipe-away loses at most 5s.
        scope.launch {
            while (true) {
                delay(5_000)
                val active = session?.player ?: continue
                if (active.isPlaying) {
                    saveNow()
                }
            }
        }

        // Cast initialises off the main thread and may never arrive at all, so the player
        // is attached whenever it turns up rather than waited for.
        scope.launch { attachCast(Casting.castContext.filterNotNull().first()) }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveNow()
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            saveNow()
        }

        override fun onPlaybackStateChanged(state: Int) {
            saveNow()
        }
    }

    // -------------------------------------------------------------------- cast

    private fun attachCast(castContext: CastContext) {
        // The context argument is only there to fill in the player's DeviceInfo, which is
        // what makes the system volume panel name the TV instead of the phone. Everything
        // after it is the default the shorter constructors pass anyway.
        val cast = CastPlayer(
            /* context = */ this,
            castContext,
            CastItemConverter(),
            C.DEFAULT_SEEK_BACK_INCREMENT_MS,
            C.DEFAULT_SEEK_FORWARD_INCREMENT_MS,
            C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS,
        )
        cast.addListener(playerListener)
        cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchTo(cast)
            override fun onCastSessionUnavailable() {
                localPlayer?.let { switchTo(it) }
            }
        })
        castPlayer = cast
        // The service can be created while a cast session is already running — the app was
        // killed and relaunched, or the session was resumed by the framework.
        if (cast.isCastSessionAvailable) switchTo(cast)
    }

    /**
     * Move playback between the phone and the TV.
     *
     * Note what this deliberately does *not* do: it never clears the outgoing player's
     * queue. Clearing empties the timeline, which puts the player in `STATE_ENDED`, which
     * the progress writer reads as "played through to the encore" (D20) — a show would mark
     * itself finished every time you cast it. Stopping leaves the queue alone and lands in
     * `STATE_IDLE` instead.
     */
    private fun switchTo(next: Player) {
        val session = session ?: return
        val current = session.player
        if (current === next) return

        // Where the outgoing player actually is. A cast player that has just lost its
        // session still reports its last queue and position — but not when the receiver
        // cleared the queue first, which is what happens when someone else takes the TV.
        // The last tick's copy covers that case.
        val from = snapshot(current) ?: handoff
        (current as? ExoPlayer)?.stop()

        // Loaded before the session is handed over, so controllers and the notification
        // see the queue arrive rather than blinking empty in between.
        if (from != null && from.items.isNotEmpty()) {
            next.setMediaItems(from.items, from.index, from.positionMs)
            next.prepare()
            // Casting picks up where the phone left off, playing. Coming back the other way
            // it waits: the cast session usually ends because someone else took the TV, and
            // the phone suddenly playing out loud in that room is not what anyone wanted.
            next.playWhenReady = from.playWhenReady && next !== localPlayer
        }
        session.setPlayer(next)
        // Kept, not replaced: a cast player reports an empty queue until the receiver has
        // loaded it, and overwriting the fallback with that would lose the queue outright
        // if the session then failed.
        snapshot(next)?.let { handoff = it }
    }

    private fun snapshot(player: Player): Handoff? {
        val count = player.mediaItemCount
        if (count == 0) return null
        return Handoff(
            items = (0 until count).map { player.getMediaItemAt(it) },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0),
            playWhenReady = player.playWhenReady,
        )
    }

    private fun saveNow() {
        val player = session?.player ?: return
        snapshot(player)?.let { handoff = it }
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val key = extras.getString(Keys.QUEUE_KEY) ?: return

        val progress = Progress(
            queueKey = key,
            title = extras.getString(Keys.QUEUE_TITLE).orEmpty(),
            subtitle = extras.getString(Keys.QUEUE_SUBTITLE).orEmpty(),
            artUrl = extras.getString(Keys.QUEUE_ART),
            trackIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0),
            trackTitle = item.mediaMetadata.title?.toString().orEmpty(),
            updatedAt = System.currentTimeMillis(),
            // Derived, never passed in: STATE_ENDED means the queue ran out, i.e. the show
            // played through its encore. Deriving it here means the several listeners that
            // all fire at the end of a queue can't race to clobber each other's value, and
            // any later play of the same show clears the flag on its own.
            finished = player.playbackState == Player.STATE_ENDED,
        )
        scope.launch { PhishInDb.get(applicationContext).progressDao().put(progress) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveNow()
        castPlayer?.run {
            setSessionAvailabilityListener(null)
            release()
        }
        // Both players are released by hand rather than through session.player, which is
        // only ever one of them.
        localPlayer?.release()
        session?.release()
        castPlayer = null
        localPlayer = null
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
