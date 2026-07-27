package dev.mike.phishin

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keys stuffed into MediaMetadata.extras so the service can attribute a track to its queue. */
object Keys {
    const val QUEUE_KEY = "queue_key"
    const val QUEUE_TITLE = "queue_title"
    const val QUEUE_SUBTITLE = "queue_subtitle"
    const val QUEUE_ART = "queue_art"
    const val WAVEFORM = "waveform"
}

/**
 * Foreground media service. This is the piece a WebView can't provide: it owns the
 * MediaSession that Android surfaces on the lockscreen, in the notification shade, and
 * to Bluetooth / headset buttons.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scrobbler by lazy {
        Scrobbler { pending ->
            if (LastFmSession.connected && LastFmApi.configured) {
                ScrobbleQueue.enqueue(applicationContext, pending)
            }
        }
    }

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

        session = MediaSession.Builder(this, player).build()

        // Save on the events that matter immediately...
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveNow()
                scrobbler.onPlayingChanged(isPlaying, System.currentTimeMillis())
                if (isPlaying) ScrobbleQueue.flush(applicationContext)
                if (isPlaying) nowPlaying()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                saveNow()
                scrobbler.onTrackChanged(
                    title = item?.mediaMetadata?.title?.toString(),
                    albumTitle = item?.mediaMetadata?.albumTitle?.toString().orEmpty(),
                    durationMs = 0,
                    nowMs = System.currentTimeMillis(),
                )
                nowPlaying()
            }

            override fun onPlaybackStateChanged(state: Int) {
                saveNow()
                if (state == Player.STATE_ENDED) {
                    scrobbler.onStopped(System.currentTimeMillis())
                }
            }
        })

        // ...and on a slow tick while playing, so a crash or swipe-away loses at most 5s.
        scope.launch {
            while (true) {
                delay(5_000)
                if (player.isPlaying) {
                    saveNow()
                    // The duration isn't known at track-transition time, so it's refreshed
                    // on the tick once the player has prepared the media.
                    scrobbler.onDuration(player.duration)
                    scrobbler.onTick(System.currentTimeMillis())
                }
            }
        }
    }

    private fun nowPlaying() {
        val key = LastFmSession.sessionKey ?: return
        if (!LastFmApi.configured) return
        val player = session?.player ?: return
        val meta = player.currentMediaItem?.mediaMetadata ?: return
        val title = meta.title?.toString() ?: return
        val duration = player.duration.coerceAtLeast(0)
        scope.launch {
            runCatching {
                LastFmApi.updateNowPlaying(
                    key,
                    Scrobble(
                        artist = "Phish",
                        track = title,
                        album = meta.albumTitle?.toString().orEmpty(),
                        durationSec = (duration / 1000).toInt(),
                        timestampSec = System.currentTimeMillis() / 1000,
                    ),
                )
            }
        }
    }

    private fun saveNow() {
        val player = session?.player ?: return
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

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveNow()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
